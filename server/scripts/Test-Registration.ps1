param([string]$ServerHost = '127.0.0.1', [int]$Port = 5050)

$ErrorActionPreference = 'Stop'
$connections = [System.Collections.Generic.List[object]]::new()
$clientId = [guid]::NewGuid().ToString()

function Open-Connection {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect($ServerHost, $Port)
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        $stream = $client.GetStream()
        $utf8 = [System.Text.UTF8Encoding]::new($false, $true)
        $reader = [System.IO.StreamReader]::new($stream, $utf8, $false, 1024, $true)
        $writer = [System.IO.StreamWriter]::new($stream, $utf8, 1024, $true)
        $writer.NewLine = [string][char]10
        $writer.AutoFlush = $true
        $connection = [pscustomobject]@{ Client = $client; Reader = $reader; Writer = $writer }
        $connections.Add($connection)
        return $connection
    } catch {
        $client.Dispose()
        throw
    }
}

function Send-Registration($Connection, [string]$Id) {
    $json = @{ version = 1; type = 'REGISTER_CLIENT'; clientId = $Id; name = 'PowerShell receiver' } |
        ConvertTo-Json -Compress
    $Connection.Writer.WriteLine($json)
}

function Read-Reply($Connection) {
    $line = $Connection.Reader.ReadLine()
    if ($null -eq $line) { throw 'Connection closed before a JSON reply arrived' }
    return ($line | ConvertFrom-Json)
}

function Assert-Registered($Reply, [string]$Id) {
    $fields = @($Reply.PSObject.Properties.Name | Sort-Object) -join ','
    if ($Reply.version -ne 1 -or $Reply.type -cne 'REGISTERED' -or
            $Reply.clientId -cne $Id -or $fields -ne 'clientId,type,version') {
        throw 'Reply does not match the documented REGISTERED shape'
    }
}

try {
    $first = Open-Connection
    Send-Registration $first $clientId
    Assert-Registered (Read-Reply $first) $clientId
    'PASS: registration returned the exact REGISTERED fields'

    # Keep the first receiver idle while a second receiver registers.
    $secondId = [guid]::NewGuid().ToString()
    $second = Open-Connection
    Send-Registration $second $secondId
    Assert-Registered (Read-Reply $second) $secondId
    'PASS: a second receiver registered while the first stayed connected'

    $duplicate = Open-Connection
    Send-Registration $duplicate $clientId
    $reply = Read-Reply $duplicate
    if ($reply.type -cne 'ERROR' -or $reply.code -cne 'DUPLICATE_CLIENT') {
        throw 'Expected DUPLICATE_CLIENT for the active ID'
    }
    if ($null -ne $duplicate.Reader.ReadLine()) { throw 'Duplicate connection should close after ERROR' }
    'PASS: duplicate ID rejected and duplicate socket closed'

    # Reject changing the original connection's identity, while keeping it open.
    Send-Registration $first $secondId
    $reply = Read-Reply $first
    if ($reply.type -cne 'ERROR' -or $reply.code -cne 'INVALID_MESSAGE') {
        throw 'Expected INVALID_MESSAGE when changing an already registered identity'
    }
    'PASS: original socket remained open and could not change its identity'

    $first.Client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    if ($null -ne $first.Reader.ReadLine()) { throw 'Expected EOF after receiver disconnect' }
    $first.Client.Dispose()

    # Wire EOF can precede the server's finally block by a scheduling instant.
    $deadline = [DateTime]::UtcNow.AddSeconds(3)
    do {
        $replacement = Open-Connection
        Send-Registration $replacement $clientId
        $reply = Read-Reply $replacement
        if ($reply.type -ceq 'REGISTERED') { break }
        if ($reply.type -cne 'ERROR' -or $reply.code -cne 'DUPLICATE_CLIENT') {
            throw 'Unexpected response while waiting for disconnect cleanup'
        }
        $replacement.Client.Dispose()
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    Assert-Registered $reply $clientId
    'PASS: disconnected client ID registered again on a new socket'
} finally {
    foreach ($connection in $connections) {
        # TcpClient owns the network stream; every write was already flushed.
        $connection.Client.Dispose()
    }
}
