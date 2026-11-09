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
$framePath = Join-Path ([System.IO.Path]::GetTempPath()) ('ack-list-' + [guid]::NewGuid() + '.json')
$statusPath = Join-Path ([System.IO.Path]::GetTempPath()) ('ack-event-' + [guid]::NewGuid() + '.json')
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
function Login-Admin {
    $connection = Open-Connection
    $reply = Exchange $connection @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.Value.type -cne 'LOGIN_RESULT' -or $reply.Value.success -ne $true) { throw 'Admin login failed' }
    return $connection
}
function Register-Receiver([string]$Id) {
    $connection = Open-Connection
    $reply = Exchange $connection @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $Id; name = 'ACK query demo receiver' }
    if ($reply.Value.type -cne 'REGISTERED' -or $reply.Value.clientId -cne $Id) { throw 'Registration failed' }
    return $connection
}
function Query-And-Verify($Connection, [string]$AlertId, [int]$ExpectedCount) {
    $reply = Exchange $Connection @{ version = 1; type = 'GET_ACKS'; alertId = $AlertId }
    $fields = @($reply.Value.PSObject.Properties.Name | Sort-Object) -join ','
    if ($fields -cne 'acknowledgements,alertId,type,version' -or $reply.Value.version -ne 1 -or
            $reply.Value.type -cne 'ACK_LIST' -or $reply.Value.alertId -cne $AlertId -or
            @($reply.Value.acknowledgements).Count -ne $ExpectedCount) { throw 'Incorrect ACK_LIST fields or count' }
    [System.IO.File]::WriteAllText($framePath, $reply.Json, [System.Text.UTF8Encoding]::new($false))
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gson = Join-Path $env:USERPROFILE '.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
    $sqlite = Join-Path $env:USERPROFILE '.m2/repository/org/xerial/sqlite-jdbc/3.51.3.0/sqlite-jdbc-3.51.3.0.jar'
    $classpath = (Join-Path $repoRoot 'server/target/classes') + ';' + (Join-Path $repoRoot 'common/target/classes') + ';' + $gson + ';' + $sqlite
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') --class-path $classpath (Join-Path $PSScriptRoot 'VerifyAckList.java') (Resolve-Path -LiteralPath $DatabasePath).Path $framePath $statusPath $ExpectedCount
    if ($LASTEXITCODE -ne 0) { throw 'ACK_LIST differs from reopened storage or the original timestamp' }
}
try {
    $admin = Login-Admin
    $unknown = [guid]::NewGuid().ToString()
    $stranger = Open-Connection
    Assert-Error (Exchange $stranger @{ version = 1; type = 'GET_ACKS'; alertId = $unknown }) 'UNAUTHORIZED'
    if ($null -ne $stranger.Reader.ReadLine()) { throw 'Unauthorized unregistered socket should close' }
    $firstId = [guid]::NewGuid().ToString()
    $secondId = [guid]::NewGuid().ToString()
    $first = Register-Receiver $firstId
    $second = Register-Receiver $secondId
    Assert-Error (Exchange $first @{ version = 1; type = 'GET_ACKS'; alertId = $unknown }) 'UNAUTHORIZED'
    Assert-Error (Exchange $admin @{ version = 1; type = 'GET_ACKS'; alertId = $unknown }) 'UNKNOWN_ALERT'
    'PASS: only admins may query acknowledgements; unknown alerts are distinct from empty results'

    $invalid = Login-Admin
    Assert-Error (Exchange $invalid @{ version = 1; type = 'GET_ACKS'; alertId = 'invalid-id' }) 'INVALID_MESSAGE'
    if ($null -ne $invalid.Reader.ReadLine()) { throw 'Invalid alert ID should close only its own admin socket' }
    'PASS: invalid alertId rejected using existing UUID validation'

    $accepted = Exchange $admin @{
        version = 1; type = 'CREATE_ALERT'; requestId = [guid]::NewGuid().ToString()
        title = 'GET_ACKS live test'; message = 'Test only: persistent status recovery.'; category = 'GENERAL'; priority = 'LOW'
    }
    if ($accepted.Value.type -cne 'ALERT_ACCEPTED') { throw 'Alert creation failed' }
    $alertId = $accepted.Value.alertId
    foreach ($receiver in @($first, $second)) {
        $live = Read-Frame $receiver
        if ($live.Value.type -cne 'ALERT' -or $live.Value.alert.id -cne $alertId) { throw 'Live alert delivery failed' }
    }
    Query-And-Verify $admin $alertId 0
    'PASS: existing alert returns an exact ACK_LIST with an empty acknowledgements array'

    $reply = Exchange $first @{ version = 1; type = 'ACK'; alertId = $alertId }
    if ($reply.Value.type -cne 'ACK_RESULT' -or $reply.Value.success -ne $true) { throw 'First acknowledgement failed' }
    $status = Read-Frame $admin
    if ($status.Value.type -cne 'ACK_STATUS' -or $status.Value.clientId -cne $firstId) { throw 'Expected first new-acknowledgement event' }
    [System.IO.File]::WriteAllText($statusPath, $status.Json, [System.Text.UTF8Encoding]::new($false))
    Query-And-Verify $admin $alertId 1
    'PASS: GET_ACKS returns the original timestamp from the committed acknowledgement'

    $admin.Client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    if ($null -ne $admin.Reader.ReadLine()) { throw 'Expected admin EOF' }
    $admin.Client.Dispose()
    $reply = Exchange $second @{ version = 1; type = 'ACK'; alertId = $alertId }
    if ($reply.Value.type -cne 'ACK_RESULT' -or $reply.Value.success -ne $true) { throw 'Offline-admin acknowledgement failed' }
    $reconnected = Login-Admin
    Query-And-Verify $reconnected $alertId 2
    Query-And-Verify $reconnected $alertId 2
    Assert-Error (Exchange $reconnected @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE'
    'PASS: reconnected admin recovered the missed acknowledgement; repeated queries emitted no ACK_STATUS'
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
    foreach ($path in @($framePath, $statusPath)) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
}
