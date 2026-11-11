param(
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 5050,
    [string]$Username = $env:EMERGENCY_ADMIN_USERNAME,
    [string]$Password = $env:EMERGENCY_ADMIN_PASSWORD,
    [string]$DatabasePath = $env:EMERGENCY_DB_PATH
)

$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'scripts/Project-Paths.ps1')
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
    throw 'Set EMERGENCY_ADMIN_USERNAME and EMERGENCY_ADMIN_PASSWORD to the server test credentials.'
}
if ([string]::IsNullOrWhiteSpace($DatabasePath)) { $DatabasePath = 'runtime/data/emergency-alerts.db' }
$DatabasePath = Resolve-ConfiguredRuntimePath $DatabasePath
if (-not (Test-Path -LiteralPath $DatabasePath -PathType Leaf)) {
    throw 'Start the local server first and supply its database path.'
}
$connections = [System.Collections.Generic.List[object]]::new()
$expectedPath = Join-Path (New-RuntimeDirectory 'evidence/legacy-checks') ('expected-alert-' + [guid]::NewGuid().ToString() + '.json')
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
function Exchange($Connection, $Message) {
    # LOGIN frames contain passwords: never print outgoing messages.
    $Connection.Writer.WriteLine(($Message | ConvertTo-Json -Compress))
    # Membership snapshots are asynchronous; these regression checks await other reply types.
    do {
        $line = $Connection.Reader.ReadLine()
        if ($null -eq $line) { throw 'Connection closed before a reply' }
        $parsedFrame = $line | ConvertFrom-Json
    } while ($parsedFrame.type -ceq 'CLIENT_LIST')
    if ($null -eq $line) { throw 'Connection closed before a reply' }
    return ($line | ConvertFrom-Json)
}
function New-AlertRequest {
    return [ordered]@{
        version = 1
        type = 'CREATE_ALERT'
        requestId = [guid]::NewGuid().ToString()
        title = 'PowerShell test alert caf' + [char]0xE9
        message = 'Test only: verify persistence.'
        category = 'GENERAL'
        priority = 'LOW'
    }
}
function Assert-Error($Reply, [string]$Code, [string]$RequestId) {
    if ($Reply.version -ne 1 -or $Reply.type -cne 'ERROR' -or $Reply.code -cne $Code -or
            $Reply.requestId -cne $RequestId) { throw "Expected correlated ERROR / $Code" }
}
try {
    $request = New-AlertRequest
    $stranger = Open-Connection
    Assert-Error (Exchange $stranger $request) 'UNAUTHORIZED' $request.requestId
    if ($null -ne $stranger.Reader.ReadLine()) { throw 'Expected unregistered connection to close' }
    'PASS: unauthenticated CREATE_ALERT rejected with request correlation'

    $receiver = Open-Connection
    $clientId = [guid]::NewGuid().ToString()
    $reply = Exchange $receiver @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $clientId; name = 'Test receiver' }
    if ($reply.type -cne 'REGISTERED') { throw 'Receiver registration failed' }
    Assert-Error (Exchange $receiver $request) 'UNAUTHORIZED' $request.requestId
    'PASS: registered receiver cannot create alerts'

    $admin = Open-Connection
    $reply = Exchange $admin @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    if ($reply.type -cne 'LOGIN_RESULT' -or $reply.success -ne $true) { throw 'Test admin login failed' }

    $invalid = New-AlertRequest
    $invalid.title = ' '
    Assert-Error (Exchange $admin $invalid) 'INVALID_ALERT' $invalid.requestId
    'PASS: invalid alert rejected with request correlation'

    $request = New-AlertRequest
    $reply = Exchange $admin $request
    $fields = @($reply.PSObject.Properties.Name | Sort-Object) -join ','
    if ($reply.version -ne 1 -or $reply.type -cne 'ALERT_ACCEPTED' -or
            $reply.requestId -cne $request.requestId -or $fields -ne 'alertId,requestId,type,version') {
        throw 'Incorrect ALERT_ACCEPTED shape or request correlation'
    }
    $alertId = [guid]::Parse($reply.alertId).ToString()
    if ($alertId -ceq $request.requestId) { throw 'Alert ID must be server generated' }
    'PASS: authenticated creation returned exact ALERT_ACCEPTED fields'

    [System.IO.File]::WriteAllText($expectedPath, ($request | ConvertTo-Json -Compress),
        [System.Text.UTF8Encoding]::new($false))
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $gsonJar = Join-Path $env:USERPROFILE '.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar'
    $sqliteJar = Join-Path $env:USERPROFILE '.m2/repository/org/xerial/sqlite-jdbc/3.51.3.0/sqlite-jdbc-3.51.3.0.jar'
    $classpath = (Join-Path $repoRoot 'server/target/classes') + ';' +
        (Join-Path $repoRoot 'common/target/classes') + ';' + $gsonJar + ';' + $sqliteJar
    $verifier = Join-Path $PSScriptRoot 'VerifyAlert.java'
    & "$env:JAVA_HOME/bin/java.exe" --class-path $classpath $verifier (Resolve-Path -LiteralPath $DatabasePath).Path $alertId $expectedPath
    if ($LASTEXITCODE -ne 0) { throw 'Reopened database verification failed' }
    "Created alert ID: $alertId"
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
    if (Test-Path -LiteralPath $expectedPath) { Remove-Item -LiteralPath $expectedPath }
}
