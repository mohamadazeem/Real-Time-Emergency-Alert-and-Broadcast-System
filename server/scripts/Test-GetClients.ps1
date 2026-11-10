param(
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 5050,
    [string]$Username = $env:EMERGENCY_ADMIN_USERNAME,
    [string]$Password = $env:EMERGENCY_ADMIN_PASSWORD,
    [string]$DatabasePath = $env:EMERGENCY_DB_PATH
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) { throw 'Set the same placeholder credentials as the local test server.' }
if ([string]::IsNullOrWhiteSpace($DatabasePath)) { $DatabasePath = 'data/emergency-alerts.db' }
if (-not (Test-Path -LiteralPath $DatabasePath -PathType Leaf)) { throw 'Supply the running local server database path.' }
$connections = [System.Collections.Generic.List[object]]::new()
$framePath = Join-Path ([System.IO.Path]::GetTempPath()) ('live-clients-' + [guid]::NewGuid() + '.json')
function Open-Connection {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect($ServerHost, $Port); $client.ReceiveTimeout = 5000; $client.SendTimeout = 5000
        $stream = $client.GetStream(); $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
        $connection = [pscustomobject]@{
            Client = $client
            Reader = [System.IO.StreamReader]::new($stream, $utf8, $false, 1024, $true)
            Writer = [System.IO.StreamWriter]::new($stream, $utf8, 1024, $true)
        }
        $connection.Writer.NewLine = [string][char]10; $connection.Writer.AutoFlush = $true
        $connections.Add($connection); return $connection
    } catch { $client.Dispose(); throw }
}
function Read-Frame($Connection) {
    $line = $Connection.Reader.ReadLine()
    if ($null -eq $line) { throw 'Connection closed before a reply' }
    if ([System.Text.Encoding]::UTF8.GetByteCount($line) -gt 65536) { throw 'Oversized outgoing frame' }
    return [pscustomobject]@{ Value = ($line | ConvertFrom-Json); Json = $line }
}
function Exchange($Connection, $Message) {
    $Connection.Writer.WriteLine(($Message | ConvertTo-Json -Compress)); return (Read-Frame $Connection)
}
function Assert-Error($Frame, [string]$Code) {
    if ($Frame.Value.type -cne 'ERROR' -or $Frame.Value.code -cne $Code) { throw "Expected ERROR / $Code" }
}
function Assert-List($Frame, [object[]]$Expected) {
    $fields = @($Frame.Value.PSObject.Properties.Name | Sort-Object) -join ','
    if ($fields -cne 'clients,type,version' -or $Frame.Value.version -ne 1 -or $Frame.Value.type -cne 'CLIENT_LIST') { throw 'Incorrect CLIENT_LIST envelope' }
    $actual = @($Frame.Value.clients)
    if ($actual.Count -ne $Expected.Count) { throw 'Unexpected live receiver count; use an isolated server without other clients' }
    for ($index = 0; $index -lt $actual.Count; $index++) {
        if ((@($actual[$index].PSObject.Properties.Name | Sort-Object) -join ',') -cne 'clientId,name' -or
                $actual[$index].clientId -cne $Expected[$index].clientId -or $actual[$index].name -cne $Expected[$index].name) {
            throw 'Client ID, name, fields, or deterministic ordering differs'
        }
    }
}
function Login-Admin {
    $connection = Open-Connection
    $reply = Exchange $connection @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.Value.type -cne 'LOGIN_RESULT' -or $reply.Value.success -ne $true) { throw 'Admin login failed' }
    return $connection
}
function Register-Receiver($Info) {
    $connection = Open-Connection
    $reply = Exchange $connection @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $Info.clientId; name = $Info.name }
    if ($reply.Value.type -cne 'REGISTERED' -or $reply.Value.clientId -cne $Info.clientId) { throw 'Registration failed' }
    return $connection
}
function Close-Gracefully($Connection) {
    $Connection.Client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    if ($null -ne $Connection.Reader.ReadLine()) { throw 'Expected EOF after graceful disconnect' }
    $Connection.Client.Dispose()
}
try {
    $firstAdmin = Login-Admin; $secondAdmin = Login-Admin
    Assert-List (Exchange $firstAdmin @{ version = 1; type = 'GET_CLIENTS' }) @()
    Assert-List (Exchange $secondAdmin @{ version = 1; type = 'GET_CLIENTS' }) @()
    $stranger = Open-Connection
    Assert-Error (Exchange $stranger @{ version = 1; type = 'GET_CLIENTS' }) 'UNAUTHORIZED'
    if ($null -ne $stranger.Reader.ReadLine()) { throw 'Expected unauthorized socket EOF' }
    'PASS: admins query an empty live list; unregistered socket is unauthorized'

    $low = [pscustomobject]@{ clientId = '1' + [guid]::NewGuid().ToString().Substring(1); name = 'Library PC' }
    $high = [pscustomobject]@{ clientId = 'f' + [guid]::NewGuid().ToString().Substring(1); name = 'Caf' + [char]0xE9 + ' PC' }
    $highReceiver = Register-Receiver $high
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-List (Read-Frame $admin) @($high) }
    $lowReceiver = Register-Receiver $low
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-List (Read-Frame $admin) @($low, $high) }
    Assert-List (Exchange $firstAdmin @{ version = 1; type = 'GET_CLIENTS' }) @($low, $high)
    Assert-Error (Exchange $lowReceiver @{ version = 1; type = 'GET_CLIENTS' }) 'UNAUTHORIZED'
    'PASS: both admins receive join updates with exact names and clientId ordering; receivers cannot query'

    $duplicate = Open-Connection
    Assert-Error (Exchange $duplicate @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $low.clientId.ToUpperInvariant(); name = 'Intruder' }) 'DUPLICATE_CLIENT'
    if ($null -ne $duplicate.Reader.ReadLine()) { throw 'Duplicate socket should close' }
    # This harmless invalid server-to-client message is a FIFO barrier: no phantom CLIENT_LIST may precede its ERROR.
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-Error (Exchange $admin @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE' }
    'PASS: duplicate registration produced no false membership update'

    Close-Gracefully $highReceiver
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-List (Read-Frame $admin) @($low) }
    $high.name = 'Renamed receiver'
    $replacement = Register-Receiver $high
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-List (Read-Frame $admin) @($low, $high) }
    'PASS: disconnect removed the active receiver; reconnect published its new name'

    Close-Gracefully $lowReceiver
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-List (Read-Frame $admin) @($high) }
    Close-Gracefully $replacement
    foreach ($admin in @($firstAdmin, $secondAdmin)) { Assert-List (Read-Frame $admin) @() }
    $empty = Exchange $firstAdmin @{ version = 1; type = 'GET_CLIENTS' }; Assert-List $empty @()
    [System.IO.File]::WriteAllText($framePath, $empty.Json, [System.Text.UTF8Encoding]::new($false))
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gson = Join-Path $env:USERPROFILE '.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
    $sqlite = Join-Path $env:USERPROFILE '.m2/repository/org/xerial/sqlite-jdbc/3.51.3.0/sqlite-jdbc-3.51.3.0.jar'
    $classpath = (Join-Path $repoRoot 'server/target/classes') + ';' + (Join-Path $repoRoot 'common/target/classes') + ';' + $gson + ';' + $sqlite
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') --class-path $classpath (Join-Path $PSScriptRoot 'VerifyLiveClients.java') (Resolve-Path -LiteralPath $DatabasePath).Path $framePath $low.clientId $high.clientId $high.name
    if ($LASTEXITCODE -ne 0) { throw 'Stored versus connected client check failed' }
    'PASS: final live list is empty although both disconnected client records remain stored'
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
    if (Test-Path -LiteralPath $framePath) { Remove-Item -LiteralPath $framePath }
}
