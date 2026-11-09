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
$framePath = Join-Path ([System.IO.Path]::GetTempPath()) ('ack-status-' + [guid]::NewGuid() + '.json')
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
    return [pscustomobject]@{ Value = ($line | ConvertFrom-Json); Json = $line }
}
function Exchange($Connection, $Message) {
    $Connection.Writer.WriteLine(($Message | ConvertTo-Json -Compress))
    return (Read-Frame $Connection)
}
function Assert-Error($Frame, [string]$Code) {
    if ($Frame.Value.type -cne 'ERROR' -or $Frame.Value.code -cne $Code) { throw "Expected ERROR / $Code" }
}
function Assert-AckResult($Frame, [string]$AlertId) {
    $fields = @($Frame.Value.PSObject.Properties.Name | Sort-Object) -join ','
    if ($fields -cne 'alertId,success,type,version' -or $Frame.Value.version -ne 1 -or
            $Frame.Value.type -cne 'ACK_RESULT' -or $Frame.Value.alertId -cne $AlertId -or
            $Frame.Value.success -ne $true) { throw 'Incorrect ACK_RESULT fields' }
}
function Verify-Stored([string]$ClientId, [string]$Name) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gsonJar = Join-Path $env:USERPROFILE '.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
    $sqliteJar = Join-Path $env:USERPROFILE '.m2/repository/org/xerial/sqlite-jdbc/3.51.3.0/sqlite-jdbc-3.51.3.0.jar'
    $classpath = (Join-Path $repoRoot 'server/target/classes') + ';' + (Join-Path $repoRoot 'common/target/classes') + ';' + $gsonJar + ';' + $sqliteJar
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') --class-path $classpath (Join-Path $PSScriptRoot 'VerifyAcknowledgement.java') (Resolve-Path -LiteralPath $DatabasePath).Path $framePath $ClientId $Name
    if ($LASTEXITCODE -ne 0) { throw 'Reopened acknowledgement verification failed' }
}
try {
    $admin = Open-Connection
    $reply = Exchange $admin @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.Value.type -cne 'LOGIN_RESULT' -or $reply.Value.success -ne $true) { throw 'Admin login failed' }
    $unknown = [guid]::NewGuid().ToString()
    $stranger = Open-Connection
    Assert-Error (Exchange $stranger @{ version = 1; type = 'ACK'; alertId = $unknown }) 'UNAUTHORIZED'
    if ($null -ne $stranger.Reader.ReadLine()) { throw 'Unauthorized unregistered socket should close' }
    Assert-Error (Exchange $admin @{ version = 1; type = 'ACK'; alertId = $unknown }) 'UNAUTHORIZED'
    'PASS: only registered receivers may acknowledge'

    $id = [guid]::NewGuid().ToString()
    $receiver = Open-Connection
    $reply = Exchange $receiver @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $id; name = 'ACK demo receiver' }
    if ($reply.Value.type -cne 'REGISTERED' -or $reply.Value.clientId -cne $id) { throw 'Registration failed' }
    Assert-Error (Exchange $receiver @{ version = 1; type = 'ACK'; alertId = $unknown }) 'UNKNOWN_ALERT'
    Assert-Error (Exchange $admin @{ version = 1; type = 'GET_ACKS'; alertId = $unknown }) 'UNKNOWN_ALERT'
    'PASS: unknown alert rejected without a status notification for ACK and GET_ACKS'

    $accepted = Exchange $admin @{
        version = 1; type = 'CREATE_ALERT'; requestId = [guid]::NewGuid().ToString()
        title = 'Acknowledgement live test'; message = 'Test only: explicitly acknowledge this alert.'
        category = 'GENERAL'; priority = 'LOW'
    }
    if ($accepted.Value.type -cne 'ALERT_ACCEPTED') { throw 'Alert creation failed' }
    $alertId = $accepted.Value.alertId
    $delivered = Read-Frame $receiver
    if ($delivered.Value.type -cne 'ALERT' -or $delivered.Value.alert.id -cne $alertId) { throw 'Delivery failed' }
    # The delivery alone must not create an ACK_STATUS; this next reply is a FIFO barrier.
    Assert-Error (Exchange $admin @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE'
    'PASS: application delivery alone did not acknowledge the alert'

    Assert-AckResult (Exchange $receiver @{
        version = 1; type = 'ACK'; alertId = $alertId
        clientId = [guid]::NewGuid().ToString(); acknowledgedAt = '2000-01-01T00:00:00Z'
    }) $alertId
    $status = Read-Frame $admin
    $fields = @($status.Value.PSObject.Properties.Name | Sort-Object) -join ','
    if ($fields -cne 'acknowledgedAt,alertId,clientId,type,version' -or $status.Value.version -ne 1 -or
            $status.Value.type -cne 'ACK_STATUS' -or $status.Value.clientId -cne $id -or
            $status.Value.alertId -cne $alertId -or $status.Value.acknowledgedAt -eq '2000-01-01T00:00:00Z') {
        throw 'ACK_STATUS identity, timestamp, or shape is incorrect'
    }
    [System.IO.File]::WriteAllText($framePath, $status.Json, [System.Text.UTF8Encoding]::new($false))
    Verify-Stored $id 'ACK demo receiver'
    'PASS: committed ACK used registration identity and server time; admin received exact ACK_STATUS'

    for ($index = 0; $index -lt 5; $index++) {
        Assert-AckResult (Exchange $receiver @{ version = 1; type = 'ACK'; alertId = $alertId }) $alertId
    }
    Assert-Error (Exchange $admin @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE'
    Verify-Stored $id 'ACK demo receiver'
    'PASS: duplicates succeeded with one stored row, original timestamp, and no duplicate status'

    $receiver.Client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    if ($null -ne $receiver.Reader.ReadLine()) { throw 'Expected receiver EOF' }
    $receiver.Client.Dispose()
    $replacement = Open-Connection
    $reply = Exchange $replacement @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $id; name = 'ACK reconnected receiver' }
    if ($reply.Value.type -cne 'REGISTERED') { throw 'Re-registration after EOF failed' }
    Assert-AckResult (Exchange $replacement @{ version = 1; type = 'ACK'; alertId = $alertId }) $alertId
    Assert-Error (Exchange $admin @{ version = 1; type = 'LOGIN_RESULT'; success = $true }) 'INVALID_MESSAGE'
    Verify-Stored $id 'ACK reconnected receiver'
    'PASS: disconnect cleanup and re-registration preserved the first acknowledgement'
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
    if (Test-Path -LiteralPath $framePath) { Remove-Item -LiteralPath $framePath }
}
