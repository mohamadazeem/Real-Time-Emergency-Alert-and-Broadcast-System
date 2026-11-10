param(
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 5050,
    [string]$Username = $env:EMERGENCY_ADMIN_USERNAME,
    [string]$Password = $env:EMERGENCY_ADMIN_PASSWORD,
    [string]$DatabasePath = $env:EMERGENCY_DB_PATH
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
    throw 'Set the same placeholder credential variables as the local server.'
}
if ([string]::IsNullOrWhiteSpace($DatabasePath)) { $DatabasePath = 'data/emergency-alerts.db' }
if (-not (Test-Path -LiteralPath $DatabasePath -PathType Leaf)) { throw 'Supply the running local server database path.' }
$connections = [System.Collections.Generic.List[object]]::new()
$expectedPath = Join-Path ([System.IO.Path]::GetTempPath()) ('broadcast-request-' + [guid]::NewGuid() + '.json')
$framePath = Join-Path ([System.IO.Path]::GetTempPath()) ('broadcast-frame-' + [guid]::NewGuid() + '.json')
function Open-Connection {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect($ServerHost, $Port)
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        $stream = $client.GetStream()
        $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
        $connection = [pscustomobject]@{
            Client = $client
            Reader = [System.IO.StreamReader]::new($stream, $utf8, $false, 1024, $true)
            Writer = [System.IO.StreamWriter]::new($stream, $utf8, 1024, $true)
        }
        $connection.Writer.NewLine = [string][char]10
        $connection.Writer.AutoFlush = $true
        $connections.Add($connection)
        return $connection
    } catch { $client.Dispose(); throw }
}
function Read-Frame($Connection) {
    # Membership snapshots are asynchronous; these regression checks await other reply types.
    do {
        $line = $Connection.Reader.ReadLine()
        if ($null -eq $line) { throw 'Connection closed before a reply' }
        $parsedFrame = $line | ConvertFrom-Json
    } while ($parsedFrame.type -ceq 'CLIENT_LIST')
    if ($null -eq $line) { throw 'Connection closed before a reply' }
    # Keep raw JSON so PowerShell date conversion cannot lose timestamp precision.
    return [pscustomobject]@{ Value = ($line | ConvertFrom-Json); Json = $line }
}
function Exchange($Connection, $Message) {
    $Connection.Writer.WriteLine(($Message | ConvertTo-Json -Compress))
    return (Read-Frame $Connection)
}
function New-Request {
    return [ordered]@{
        version = 1; type = 'CREATE_ALERT'; requestId = [guid]::NewGuid().ToString()
        title = 'Broadcast test caf' + [char]0xE9
        message = 'Test only: broadcast persistence check.'; category = 'GENERAL'; priority = 'LOW'
    }
}
function Assert-Error($Frame, [string]$Code) {
    if ($Frame.Value.type -cne 'ERROR' -or $Frame.Value.code -cne $Code) { throw "Expected ERROR / $Code" }
}
function Verify-Stored($Request, $Accepted, $Frame) {
    if ($Frame.Value.version -ne 1 -or $Frame.Value.type -cne 'ALERT' -or
            $Frame.Value.alert.id -cne $Accepted.Value.alertId) { throw 'Incorrect ALERT envelope or alert ID' }
    [System.IO.File]::WriteAllText($expectedPath, ($Request | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($framePath, $Frame.Json, [System.Text.UTF8Encoding]::new($false))
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gsonJar = Join-Path $env:USERPROFILE '.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
    $sqliteJar = Join-Path $env:USERPROFILE '.m2/repository/org/xerial/sqlite-jdbc/3.51.3.0/sqlite-jdbc-3.51.3.0.jar'
    $classpath = (Join-Path $repoRoot 'server/target/classes') + ';' + (Join-Path $repoRoot 'common/target/classes') + ';' + $gsonJar + ';' + $sqliteJar
    & "$env:JAVA_HOME/bin/java.exe" --class-path $classpath (Join-Path $PSScriptRoot 'VerifyAlert.java') (Resolve-Path -LiteralPath $DatabasePath).Path $Accepted.Value.alertId $expectedPath $framePath
    if ($LASTEXITCODE -ne 0) { throw 'Broadcast differs from reopened storage' }
}
try {
    $first = Open-Connection
    $firstId = [guid]::NewGuid().ToString()
    $reply = Exchange $first @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $firstId; name = 'First demo receiver' }
    if ($reply.Value.type -cne 'REGISTERED' -or $reply.Value.clientId -cne $firstId) { throw 'First registration failed' }
    $second = Open-Connection
    $secondId = [guid]::NewGuid().ToString()
    $reply = Exchange $second @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $secondId; name = 'Second demo receiver' }
    if ($reply.Value.type -cne 'REGISTERED' -or $reply.Value.clientId -cne $secondId) { throw 'Second registration failed' }
    'PASS: both receivers received REGISTERED before any ALERT'

    $attacker = Open-Connection
    Assert-Error (Exchange $attacker (New-Request)) 'UNAUTHORIZED'
    if ($null -ne $attacker.Reader.ReadLine()) { throw 'Unregistered attacker should close' }
    $attacker.Client.Dispose()
    Assert-Error (Exchange $first (New-Request)) 'UNAUTHORIZED'
    'PASS: unregistered and receiver submissions are unauthorized'

    $admin = Open-Connection
    $reply = Exchange $admin @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.Value.type -cne 'LOGIN_RESULT' -or $reply.Value.success -ne $true) { throw 'Admin login failed' }
    $request = New-Request
    $accepted = Exchange $admin $request
    if ($accepted.Value.type -cne 'ALERT_ACCEPTED' -or $accepted.Value.requestId -cne $request.requestId) { throw 'Acceptance/correlation failed' }
    $firstAlert = Read-Frame $first
    $secondAlert = Read-Frame $second
    if ($firstAlert.Json -cne $secondAlert.Json) { throw 'Receivers got different alert messages' }
    Verify-Stored $request $accepted $firstAlert
    'PASS: two receivers got identical ALERT messages containing the exact stored row'
    Assert-Error (Exchange $admin @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE'

    $first.Client.Dispose()
    $request = New-Request
    $accepted = Exchange $admin $request
    if ($accepted.Value.type -cne 'ALERT_ACCEPTED' -or $accepted.Value.requestId -cne $request.requestId) { throw 'Second acceptance failed' }
    $secondAlert = Read-Frame $second
    Verify-Stored $request $accepted $secondAlert
    'PASS: disconnected receiver did not prevent another persisted alert reaching the healthy receiver'
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
    foreach ($path in @($expectedPath, $framePath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
}
