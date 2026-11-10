param(
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 5050,
    [string]$Username = $env:EMERGENCY_ADMIN_USERNAME,
    [string]$Password = $env:EMERGENCY_ADMIN_PASSWORD
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
    throw 'Set EMERGENCY_ADMIN_USERNAME and EMERGENCY_ADMIN_PASSWORD to the server test credentials.'
}
$connections = [System.Collections.Generic.List[object]]::new()
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
    # Never echo the outgoing JSON: LOGIN contains a password.
    $Connection.Writer.WriteLine(($Message | ConvertTo-Json -Compress))
    $line = $Connection.Reader.ReadLine()
    if ($null -eq $line) { throw 'Connection closed before a reply' }
    return ($line | ConvertFrom-Json)
}
function Assert-LoginResult($Reply, [bool]$Expected) {
    $fields = @($Reply.PSObject.Properties.Name | Sort-Object) -join ','
    if ($Reply.version -ne 1 -or $Reply.type -cne 'LOGIN_RESULT' -or
            $Reply.success -isnot [bool] -or $Reply.success -ne $Expected -or
            $fields -ne 'success,type,version') { throw 'Incorrect LOGIN_RESULT shape or result' }
}
function Assert-Error($Reply, [string]$Code) {
    if ($Reply.version -ne 1 -or $Reply.type -cne 'ERROR' -or $Reply.code -cne $Code) {
        throw "Expected ERROR / $Code"
    }
}
try {
    $admin = Open-Connection
    $reply = Exchange $admin @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password + '-incorrect' }
    Assert-LoginResult $reply $false
    'PASS: incorrect credentials return LOGIN_RESULT false'

    $reply = Exchange $admin @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }
    Assert-LoginResult $reply $true
    'PASS: correct credentials authenticate the same connection'

    $reply = Exchange $admin @{ version = 1; type = 'GET_CLIENTS' }
    if ($reply.type -cne 'CLIENT_LIST') { throw 'Expected CLIENT_LIST for an authenticated admin' }
    'PASS: authenticated admin can query the live client list'

    $stranger = Open-Connection
    Assert-Error (Exchange $stranger @{ version = 1; type = 'GET_CLIENTS' }) 'UNAUTHORIZED'
    if ($null -ne $stranger.Reader.ReadLine()) { throw 'Expected unregistered connection to close' }
    'PASS: another connection does not inherit admin authorization'

    $id = [guid]::NewGuid().ToString()
    Assert-Error (Exchange $admin @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $id; name = 'Disallowed receiver' }) 'INVALID_MESSAGE'
    $reply = Exchange $admin @{ version = 1; type = 'GET_CLIENTS' }
    if ($reply.type -cne 'CLIENT_LIST') { throw 'Admin role changed after rejected registration' }
    'PASS: admin cannot change to receiver and remains authenticated'

    $receiver = Open-Connection
    $reply = Exchange $receiver @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $id; name = 'Test receiver' }
    if ($reply.type -cne 'REGISTERED' -or $reply.clientId -cne $id) { throw 'Registration regression' }
    Assert-Error (Exchange $receiver @{ version = 1; type = 'LOGIN'; username = $Username; password = $Password }) 'INVALID_MESSAGE'
    Assert-Error (Exchange $receiver @{ version = 1; type = 'GET_CLIENTS' }) 'UNAUTHORIZED'
    'PASS: receiver cannot become admin even with correct credentials'
} finally {
    foreach ($connection in $connections) { $connection.Client.Dispose() }
}
