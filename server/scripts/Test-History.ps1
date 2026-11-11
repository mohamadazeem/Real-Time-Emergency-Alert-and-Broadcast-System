param(
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 5050,
    [string]$Username = $env:EMERGENCY_ADMIN_USERNAME,
    [string]$Password = $env:EMERGENCY_ADMIN_PASSWORD,
    [string]$DatabasePath = $env:EMERGENCY_DB_PATH,
    [switch]$RequireEmpty
)
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'scripts/Project-Paths.ps1')
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
    throw 'Set the same placeholder credential variables as the local server.'
}
if ([string]::IsNullOrWhiteSpace($DatabasePath)) { $DatabasePath = 'runtime/data/emergency-alerts.db' }
$DatabasePath = Resolve-ConfiguredRuntimePath $DatabasePath
if (-not (Test-Path -LiteralPath $DatabasePath -PathType Leaf)) { throw 'Supply the running local server database path.' }
$connections = [System.Collections.Generic.List[object]]::new()
$createdIds = [System.Collections.Generic.List[string]]::new()
$framePath = Join-Path (New-RuntimeDirectory 'evidence/legacy-checks') ('history-frame-' + [guid]::NewGuid() + '.json')
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
    if ([System.Text.Encoding]::UTF8.GetByteCount($line) -gt 65536) { throw 'Oversized outgoing frame' }
    return [pscustomobject]@{ Value = ($line | ConvertFrom-Json); Json = $line }
}
function Exchange($Connection, $Message) {
    $Connection.Writer.WriteLine(($Message | ConvertTo-Json -Compress))
    return (Read-Frame $Connection)
}
function Assert-Error($Frame, [string]$Code) {
    if ($Frame.Value.type -cne 'ERROR' -or $Frame.Value.code -cne $Code) { throw "Expected ERROR / $Code" }
}
function Verify-History($Frame, [int]$Limit) {
    $fields = @($Frame.Value.PSObject.Properties.Name | Sort-Object) -join ','
    if ($fields -cne 'alerts,type,version' -or $Frame.Value.version -ne 1 -or $Frame.Value.type -cne 'HISTORY') {
        throw 'Incorrect HISTORY shape'
    }
    # Retain raw JSON so PowerShell date conversion cannot round stored nanoseconds.
    [System.IO.File]::WriteAllText($framePath, $Frame.Json, [System.Text.UTF8Encoding]::new($false))
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gson = Join-Path $env:USERPROFILE '.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
    $sqlite = Join-Path $env:USERPROFILE '.m2/repository/org/xerial/sqlite-jdbc/3.51.3.0/sqlite-jdbc-3.51.3.0.jar'
    $classpath = (Join-Path $repoRoot 'server/target/classes') + ';' + (Join-Path $repoRoot 'common/target/classes') + ';' + $gson + ';' + $sqlite
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') --class-path $classpath (Join-Path $PSScriptRoot 'VerifyHistory.java') (Resolve-Path -LiteralPath $DatabasePath).Path $framePath $Limit ($createdIds -join ',')
    if ($LASTEXITCODE -ne 0) { throw 'History differs from reopened database or retrieval created an acknowledgement' }
}
try {
    $admin = Open-Connection
    $reply = Exchange $admin @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.Value.type -cne 'LOGIN_RESULT' -or $reply.Value.success -ne $true) { throw 'Admin login failed' }
    $stranger = Open-Connection
    Assert-Error (Exchange $stranger @{ version = 1; type = 'GET_HISTORY'; limit = 1 }) 'UNAUTHORIZED'
    if ($null -ne $stranger.Reader.ReadLine()) { throw 'Expected unauthorized unregistered socket to close' }
    'PASS: unregistered history request rejected independently of authenticated admin'

    $receiver = Open-Connection
    $id = [guid]::NewGuid().ToString()
    $reply = Exchange $receiver @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $id; name = 'History demo receiver' }
    if ($reply.Value.type -cne 'REGISTERED' -or $reply.Value.clientId -cne $id) { throw 'Registration failed' }
    $baseline = Exchange $admin @{ version = 1; type = 'GET_HISTORY'; limit = 1 }
    if ($RequireEmpty -and ($baseline.Value.type -cne 'HISTORY' -or @($baseline.Value.alerts).Count -ne 0)) { throw 'Expected an empty disposable database' }
    Verify-History $baseline 1
    $receiverBaseline = Exchange $receiver @{ version = 1; type = 'GET_HISTORY'; limit = 1 }
    if ($baseline.Json -cne $receiverBaseline.Json) { throw 'Admin and receiver histories differ' }
    'PASS: authenticated admin and registered receiver get identical history (empty when requested)'

    $invalid = Open-Connection
    $reply = Exchange $invalid @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.Value.success -ne $true) { throw 'Validation test login failed' }
    Assert-Error (Exchange $invalid @{ version = 1; type = 'GET_HISTORY'; limit = 0 }) 'INVALID_MESSAGE'
    if ($null -ne $invalid.Reader.ReadLine()) { throw 'Invalid inbound request should close only its own socket' }
    'PASS: invalid limit rejected with INVALID_MESSAGE'

    $emoji = [char]::ConvertFromUtf32(0x1F6A8)
    for ($index = 0; $index -lt 4; $index++) {
        $accepted = Exchange $admin @{
            version = 1; type = 'CREATE_ALERT'; requestId = [guid]::NewGuid().ToString()
            title = ($emoji * 120); message = ($emoji * 4000); category = 'GENERAL'; priority = 'LOW'
        }
        if ($accepted.Value.type -cne 'ALERT_ACCEPTED') { throw 'Large valid alert creation failed' }
        $createdIds.Add($accepted.Value.alertId)
        $live = Read-Frame $receiver
        if ($live.Value.type -cne 'ALERT' -or $live.Value.alert.id -cne $accepted.Value.alertId) { throw 'Live alert delivery failed' }
        $latest = Exchange $receiver @{ version = 1; type = 'GET_HISTORY'; limit = 1 }
        if ($latest.Value.type -cne 'HISTORY' -or $latest.Value.alerts[0].id -cne $live.Value.alert.id) {
            throw 'Live and history should contain the same alert ID; receivers must deduplicate it'
        }
    }
    'PASS: valid large alerts broadcast normally; live and history contain the same alert IDs'

    foreach ($connection in @($admin, $receiver)) {
        $tooLarge = Exchange $connection @{ version = 1; type = 'GET_HISTORY'; limit = 4 }
        Assert-Error $tooLarge 'INVALID_MESSAGE'
        if ($tooLarge.Value.message -notmatch '65536' -or $tooLarge.Value.message -notmatch 'smaller limit') { throw 'Missing frame-size explanation' }
        $smaller = Exchange $connection @{ version = 1; type = 'GET_HISTORY'; limit = 3 }
        if (@($smaller.Value.alerts).Count -ne 3) { throw 'Expected the complete newest three alerts' }
        Verify-History $smaller 3
    }
    'PASS: oversized history explained to both roles; smaller UTF-8 responses matched reopened storage'
    $acks = Exchange $admin @{ version = 1; type = 'GET_ACKS'; alertId = $createdIds[0] }
    if ($acks.Value.type -cne 'ACK_LIST' -or @($acks.Value.acknowledgements).Count -ne 0) { throw 'History must not acknowledge alerts' }
    Assert-Error (Exchange $admin @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE'
    'PASS: history produced no acknowledgements or ACK_STATUS; membership updates were consumed independently'
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
    if (Test-Path -LiteralPath $framePath) { Remove-Item -LiteralPath $framePath }
}
