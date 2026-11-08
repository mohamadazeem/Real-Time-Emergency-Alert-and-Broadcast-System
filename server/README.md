# JSON receiver registration milestone

The server uses the existing shared JsonMessageCodec and documented RegisterClient /
Registered records. Java 17, port 5050, four workers, four waiting queue slots, capacity
rejection, and shutdown cleanup are preserved.

Send one UTF-8 JSON object plus LF:

```json
{"version":1,"type":"REGISTER_CLIENT","clientId":"123e4567-e89b-12d3-a456-426614174000","name":"Library PC"}
```

Reply:

```json
{"version":1,"type":"REGISTERED","clientId":"123e4567-e89b-12d3-a456-426614174000"}
```

The socket stays open for subsequent frames. Clean EOF, I/O failure, invalid framing,
or shutdown closes it and removes its registration. Payloads/passwords are not logged.

## Code and ownership

- ServerMain creates one shared ConnectionRegistry and passes it to all handlers.
  Its existing socket set still tracks every accepted socket, including unregistered
  and queued sockets, for shutdown.
- ConnectionRegistry uses ConcurrentHashMap<UUID, Socket>. putIfAbsent atomically reserves
  an ID. UUID keys also reject upper/lowercase variants of the same identity.
  remove(id, socket) removes only the entry owned by that exact connection, protecting
  replacements from stale cleanup.
- ClientHandler owns its socket, one retained BufferedInputStream, and one output stream.
  It repeatedly reads shared messages, registers once, and writes shared replies.
  Its finally block unregisters its own ID and invokes the existing completion callback.
- ProtocolException now exposes an optional recoverable requestId. The codec preserves
  a valid UUID from parseable schema-invalid JSON so ERROR can echo it. Invalid IDs and
  syntactically broken JSON cannot supply a usable correlation ID. No wire shape changed.
- server/pom.xml adds test-scoped JUnit and the Surefire runner. Runtime dependencies
  remain common and its transitive Gson dependency.

## Connection-state policy

The protocol specifies ERROR codes but leaves some closure decisions unspecified.
This milestone uses the following policy:

| Input/state | Reply | Connection |
| --- | --- | --- |
| First valid registration with available ID | REGISTERED | Remains open |
| ID active on another socket | ERROR / DUPLICATE_CLIENT | Newcomer closes; original remains |
| Registration repeated on an already registered socket | ERROR / INVALID_MESSAGE | Original identity and socket remain |
| ACK or GET_HISTORY before registration | ERROR / UNAUTHORIZED | Closes |
| CREATE_ALERT, GET_CLIENTS, GET_ACKS without authenticated admin state | ERROR / UNAUTHORIZED | Unregistered socket closes; registered receiver remains |
| LOGIN, which is not implemented | ERROR / INVALID_MESSAGE | Unregistered socket closes; registered receiver remains |
| Other well-formed unsupported messages, including ACK/GET_HISTORY after registration | ERROR / INVALID_MESSAGE | Unregistered socket closes; registered receiver remains |
| Invalid JSON, fields, UTF-8, version/type, oversized or truncated frame | ERROR / INVALID_MESSAGE when writable | Closes |
| Clean EOF | No reply | Closes and unregisters |

CREATE_ALERT errors echo its valid requestId. Parseable schema-invalid messages echo a
recoverable valid UUID requestId through ProtocolException. Socket failures can prevent
delivery of an ERROR, but cleanup still runs. No authentication, ACK processing, history,
client-list notifications, broadcasting, or database implementation is added.

Disconnect cleanup occurs when the server observes EOF/I/O failure. A connection lost
without FIN/RST can remain active until TCP detects it; no heartbeat or idle timeout is
added. A reconnect racing the old finally block can briefly get DUPLICATE_CLIENT and
should retry after cleanup.

Persistent/idle receivers each occupy one worker. With four occupied workers, four more
sockets wait without being processed; a fifth registration cannot complete until a worker
is freed. Further connections are logged and closed.

## Build and run with runtime dependencies

Run in PowerShell using your existing Temurin JAVA_HOME and normal Maven cache:

```powershell
Set-Location 'C:\Users\ASUS\Desktop\Network Project\Real-Time-Emergency-Alert-and-Broadcast-System'
& "$env:JAVA_HOME\bin\java.exe" -version
mvn clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

$gsonJar = Join-Path $env:USERPROFILE '.m2\repository\com\google\code\gson\gson\2.13.2\gson-2.13.2.jar'
if (-not (Test-Path -LiteralPath $gsonJar)) { throw "Gson JAR not found: $gsonJar" }
$serverClasspath = "server\target\classes;common\target\classes;$gsonJar"
& "$env:JAVA_HOME\bin\java.exe" -cp $serverClasspath edu.ec5205.alert.server.ServerMain
```

This includes server classes, common classes, and Gson. Running the server JAR alone
would omit dependencies. The Gson path uses the normal default Maven cache for this
project; adjust only if your Maven settings explicitly use another local repository.

In a second PowerShell window:

```powershell
Set-Location 'C:\Users\ASUS\Desktop\Network Project\Real-Time-Emergency-Alert-and-Broadcast-System'
& .\server\scripts\Test-Registration.ps1
```

Expect five PASS messages. The script checks exact REGISTERED fields, concurrent open
receivers, duplicate rejection, immutable socket identity, and re-registration after
disconnect. It creates fresh UUIDs and closes its sockets in finally. Close other idle
clients first if all four server workers are occupied. Press Ctrl+C in the server window
to trigger normal shutdown cleanup.

## Verification

ClientRegistrationTest uses real loopback sockets and ephemeral test ports. It covers
registration, persistent connections, duplicates, atomic insertion under racing callers,
owner-checked cleanup, re-registration, connection-state errors, admin authorization
denial, unavailable login, malformed messages, error correlation, unknown versions/types,
early/truncated EOF, and releasing a blocked read by closing its socket.

Full mvn -B clean verify passed using configured Temurin 17.0.21 and normal Maven cache:
60 common tests plus 15 server tests, zero failures/errors/skips.
