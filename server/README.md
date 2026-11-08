# JSON registration, authentication, persisted alert creation, and broadcast

The server uses the existing shared JsonMessageCodec and Login / LoginResult /
RegisterClient / Registered models. No protocol fields, message shapes, or error codes
changed. Java 17 and port 5050 are preserved. Connection capacity is now configurable
and defaults to eight active connections with immediate rejection at capacity; see below.

SQLite storage is initialized before the listener starts. Authenticated CREATE_ALERT
requests persist through AlertRepository and are queued for registered receivers before
ALERT_ACCEPTED is queued to the admin. Acceptance confirms persistence and acceptance
for broadcast, not guaranteed delivery or acknowledgement by a receiver.
Registration/authentication are preserved. Shared message shapes and protocol fields
are unchanged; exception metadata was added for correct validation error classification.

## Authentication, authorization, and roles

Authentication verifies the supplied username/password. Authorization checks whether
the connection's role is allowed to send a command. Passing authorization does not
mean a feature has been implemented.

Every connection starts UNREGISTERED. A valid REGISTER_CLIENT makes it a RECEIVER;
a successful LOGIN makes it an ADMIN. These roles belong to individual handlers, not
to the global credential configuration. Another connection must authenticate separately.

A receiver cannot log in as admin; an admin cannot register as a receiver. LOGIN on a
connection that already has either role is rejected without changing it, even if
credentials are correct. Open a new socket to use another role. Closing an admin socket
discards its role. Failed login leaves the connection unregistered and open for retry
or registration.

## Configuration and credential handling

Set both EMERGENCY_ADMIN_USERNAME and EMERGENCY_ADMIN_PASSWORD in the server process
environment. AdminCredentials loads them once before opening the listener. Missing,
empty, or whitespace-only values produce a clear configuration message naming the
variables and exit code 1. There are no default credentials.

Values are compared exactly, without trimming. Restart to change configuration.
Never log environment values, passwords, or complete LOGIN frames. The server logs
connection addresses and receiver UUIDs, not payloads. Login.toString() and
AdminCredentials.toString() redact credentials. Configuration errors contain no values.

TCP remains unencrypted, as documented in PROTOCOL.md. Use placeholder credentials on
an isolated classroom test network.

## Wire exchanges

Send compact UTF-8 JSON followed by LF; the codec flushes replies.

```json
{"version":1,"type":"LOGIN","username":"classroom-admin-placeholder","password":"classroom-password-placeholder"}
{"version":1,"type":"LOGIN_RESULT","success":true}
```

Incorrect credentials return the same LOGIN_RESULT shape with success false.

```json
{"version":1,"type":"REGISTER_CLIENT","clientId":"123e4567-e89b-12d3-a456-426614174000","name":"Library PC"}
{"version":1,"type":"REGISTERED","clientId":"123e4567-e89b-12d3-a456-426614174000"}
```

Successful login and registration keep their connections open for subsequent frames.

## State and error policy

The protocol leaves some closure decisions unspecified. This milestone uses:

| Input/state | Reply | Connection |
| --- | --- | --- |
| Valid LOGIN while unregistered | LOGIN_RESULT / true; becomes ADMIN | Remains open |
| Wrong credentials while unregistered | LOGIN_RESULT / false; remains UNREGISTERED | Remains open |
| LOGIN on receiver/admin | ERROR / INVALID_MESSAGE; role unchanged | Remains open |
| REGISTER_CLIENT on admin | ERROR / INVALID_MESSAGE; remains ADMIN | Remains open |
| First valid registration, ID available | REGISTERED; becomes RECEIVER | Remains open |
| ID active on another socket | ERROR / DUPLICATE_CLIENT | Newcomer closes; original remains |
| Registration repeated on receiver | ERROR / INVALID_MESSAGE; original identity remains | Remains open |
| CREATE_ALERT, GET_CLIENTS, GET_ACKS without admin role | ERROR / UNAUTHORIZED | Unregistered closes; receiver remains |
| ACK without receiver role | ERROR / UNAUTHORIZED | Unregistered closes; admin remains |
| GET_HISTORY without receiver/admin role | ERROR / UNAUTHORIZED | Closes |
| Valid CREATE_ALERT on ADMIN | ALERT_ACCEPTED after successful commit | Remains open |
| Invalid CREATE_ALERT payload on ADMIN | ERROR / INVALID_ALERT with recoverable requestId | Remains open for correction |
| CREATE_ALERT database failure on ADMIN | ERROR / INTERNAL_ERROR, generic explanation and requestId | Remains open; no acceptance |
| Authorized but unimplemented command | ERROR / INVALID_MESSAGE with explicit not-implemented explanation | Remains open |
| Other unexpected messages, including server reply types | ERROR / INVALID_MESSAGE | Unregistered closes; receiver/admin remains |
| Invalid JSON/envelope/requestId/UTF-8/version/type, oversized or truncated frame | ERROR / INVALID_MESSAGE when writable | Closes |
| Clean EOF | No reply | Closes and cleans up |

For example, GET_CLIENTS from an unregistered connection returns UNAUTHORIZED.
GET_CLIENTS from an admin passes authorization but returns INVALID_MESSAGE explaining
that the feature is not implemented. GET_HISTORY is permitted for admin or receiver;
ACK is receiver-only. Client listing, history retrieval, and acknowledgement processing
commands remain unimplemented. CREATE_ALERT is the only network command
connected to the storage layer in this milestone; registration is still in memory.

## CREATE_ALERT request-to-database flow

1. The shared codec checks the version/type envelope and UUID requestId, then validates
   the existing CreateAlert record fields. Title must be non-blank and 1–120 Unicode
   code points; message must be non-blank and 1–4000. Category and priority must be
   documented enum names. Text is not trimmed or changed.
2. ClientHandler requires the connection's ADMIN role. A recognized CREATE_ALERT from
   any other role returns UNAUTHORIZED, including a parseable invalid alert payload.
   Broken JSON/unsupported envelopes still return INVALID_MESSAGE because the command
   cannot be safely identified. Receiver role stays intact; an unregistered socket closes.
3. AlertCreationService reuses shared validation, generates UUID.randomUUID() and
   Instant.now() (UTC), and constructs the shared Alert. Extra caller-supplied id,
   alertId, or createdAt fields cannot override these server-generated values.
4. AlertRepository inserts using the existing short SQLite transaction. It returns only
   after commit; SQL failures during the transaction roll it back.
5. The same immutable Alert that was inserted is wrapped in the existing AlertMessage
   and offered to a snapshot of registered receiver mailboxes. There is no socket I/O
   or waiting for queue space on this path. Failed storage never reaches broadcast.
6. AlertAccepted(original requestId, generated alertId) is queued to the admin. Its one
   writer serializes UTF-8 JSON plus LF and flushes. A separate connection/JVM can already
   read the committed row. A receiver's ALERT can arrive before the admin reads acceptance.

SQLExceptions become ERROR / INTERNAL_ERROR with the message "Unable to store the alert."
and the original requestId. No SQL, database path, driver detail, or exception text is
sent to the caller. There is no ALERT_ACCEPTED on an insert failure. The admin may retry
on the same socket after recovery. The tests induce a real SQLite insert failure with
a test-only trigger; no failure switch or trigger is added to production.

The existing shared codec rejected invalid payloads before the handler could choose
INVALID_ALERT. Its ProtocolException now exposes messageType() and isInvalidAlert(),
alongside the existing recoverable requestId(). Existing constructors/method signatures
remain compatible and no wire shapes changed. Valid envelope/requestId plus invalid
alert fields produce INVALID_ALERT; malformed envelopes or correlation IDs produce
INVALID_MESSAGE. This avoids classifying failures by parsing human-readable error text.

ALERT_ACCEPTED means the alert is stored and accepted for broadcasting, as defined
by PROTOCOL.md. It does not mean all receivers got it. If a socket fails after commit
but before receiving the reply, the row can still exist. requestId correlates replies;
it is not an idempotency key and re-sending a successful request can create another alert.

CREATE_ALERT errors echo its valid requestId. The shared codec also recovers valid UUID
request IDs from parseable schema-invalid frames. Invalid IDs and syntactically broken
JSON cannot provide usable correlation. Socket failures can prevent ERROR delivery,
but cleanup still runs.

## Code and cleanup

- AdminCredentials validates startup configuration and checks LOGIN credentials.
- ConnectionRole explicitly lists UNREGISTERED, RECEIVER, ADMIN.
- ServerMain validates credentials before binding port 5050 and passes immutable
  configuration plus one shared ConnectionRegistry to handlers.
- ClientHandler owns the retained BufferedInputStream and per-connection role state.
  It reads shared messages in a loop and keeps role state local to that connection.
  Outbound messages are now passed to ClientConnection, which exclusively owns the
  output stream and a dedicated writer thread; handlers never write to the socket.
- ServerMain creates one AlertCreationService / AlertRepository using its initialized
  SqliteDatabase and shares them across handlers; the database lock is still shared.
- ConnectionRegistry maps UUIDs to ClientConnection objects under a short membership
  lock. Duplicate checks, queuing REGISTERED, and publishing the receiver are atomic
  with respect to membership snapshots. UUID spellings are case-insensitive.
  remove(id, connection) prevents stale cleanup from removing a replacement owner.
- Handler finally blocks remove receiver registrations and run the completion callback.
  Admin state disappears with the handler; admins are not receiver-registry entries.
- ServerMain tracks admitted ClientConnection objects, including unregistered/admin
  connections. Shutdown closes the listener, rejects new work, closes connections to
  release blocked reads/writes, stops the reader executor, and waits for readers and
  writers within a shared five-second deadline. No application-level reader queue exists.

Disconnect cleanup happens when EOF/I/O failure is observed. No heartbeat or timeout
is added. A reconnect racing old cleanup may briefly get DUPLICATE_CLIENT and retry.

## Outgoing queues, ordering, and slow receivers

Each admitted connection has one bounded FIFO mailbox and exactly one writer calling
JsonMessageCodec.write. Replies, errors, and ALERT messages use that same mailbox, so
concurrent producers cannot interleave JSON bytes. The default is 32 waiting messages
plus at most one frame being written. EMERGENCY_OUTGOING_QUEUE_CAPACITY permits 1–256.

The queue-full policy is immediate disconnection: a failed nonblocking offer logs the
remote address, closes the socket, discards pending messages, interrupts the writer,
and removes only that connection's registration. No last ERROR is attempted on a full
queue. Writer I/O failure follows the same cleanup. This policy also bounds slow admin
reply queues. Other receiver queues and alert persistence do not wait on that writer.
An idle writer can wait for input; a blocked socket writer is released by socket close.
No delivery timeout, retry buffer, ACK processing, or guaranteed delivery is added.

REGISTERED is offered before registry publication, inside the membership lock. Any
broadcast snapshot that includes the receiver therefore enqueues ALERT after its
REGISTERED in the same FIFO queue. Broadcast takes a snapshot under the lock, releases
the lock, then offers messages. It never holds a database transaction during delivery.
Joining/leaving concurrently may miss a snapshot; no replay is promised. Concurrent
admin requests need not produce a global createdAt-ordered delivery stream, although
each connection's frames follow its enqueue order.

Normal handler termination allows final replies at most one second to drain, then
force-closes and waits at most another second for its writer. Shutdown/overflow/failure
close immediately and can discard pending messages. Acceptance is independent of these
delivery outcomes. Every writer closes its output stream in finally; socket close also
releases the retained input stream, and handler cleanup remains owner-checked.

## Bounded persistent connection capacity

EMERGENCY_MAX_CONNECTIONS defaults to 8 and permits 1–64. Each active connection uses
one reader worker and one dedicated writer, bounded by that admitted connection count.
Eight slots accommodate an admin, two demo receivers, and spare connections. Set at
least 3 for that demo; there are no reserved admin slots. Idle/unregistered connections
also occupy capacity. No heartbeat or idle timeout has been added.

The reader executor uses SynchronousQueue (zero waiting tasks), not the former four
workers plus four queued, unserviced persistent sockets. At capacity, a newly accepted
socket is logged and immediately closed rather than waiting for a worker. TCP's OS
listen backlog still exists; successful connect alone does not imply application
admission. Wait for REGISTERED/LOGIN_RESULT. Slots may briefly remain occupied during
cleanup, so an excess connection can be rejected while an old handler is finishing.
Non-integer, blank, or out-of-range configured limits fail startup before binding.

## SQLite schema and repositories

SQLite stores the data in one local file. Set EMERGENCY_DB_PATH to choose that file;
the default is data/emergency-alerts.db relative to the server's working directory.
Relative paths are normalized to an absolute path and missing parent directories are
created. An explicitly blank value is rejected. Keep the file on local disk.

Startup validates credentials first, then creates the database/tables/indexes in a short
transaction, and only then binds port 5050. Invalid paths, corrupt databases, and SQL
initialization failures print Storage startup error and exit with code 1. Startup does
not accept connections if initialization fails. CREATE TABLE IF NOT EXISTS makes normal
reopening safe; this milestone is not a migration system for arbitrary existing schemas.

A table is a collection of rows. A primary key identifies a row. A foreign key requires
another referenced row to exist:

| Table | Shared model fields stored | Keys and rules |
| --- | --- | --- |
| clients | clientId, name | clientId is the primary key; saving an existing ID updates its display name |
| alerts | id, createdAt, title, message, category, priority | id is the primary key; duplicate inserts fail; category/priority are documented enum names |
| acknowledgements | alertId, clientId, acknowledgedAt | the pair (alertId, clientId) is the primary key; alert/client must exist |

All these columns are non-null. UUIDs are case-insensitive keys, consistent with the
connection registry, while the original stored spelling is retained. Client-name updates
use an upsert rather than replacing/deleting the client row, preserving acknowledgement
references. Repeated acknowledgements do nothing and retain the first timestamp.
Referenced alert/client rows cannot be deleted while acknowledgements refer to them.

The two timestamp fields remain their original ISO-8601 UTC strings. Additional internal
createdAtSort / acknowledgedAtSort columns hold fixed-width UTC keys with nine fractional
digits. This avoids incorrect text ordering between timestamps with different fractional
precision or Z/+00:00 spelling. These internal columns do not change JSON models.
Indexes support history selection and client foreign-key checks. No password table is added.

A repository is a Java class that keeps SQL out of application handlers:

| Class in server/storage | Methods |
| --- | --- |
| SqliteDatabase | initializes schema, resolves configured path, manages synchronized transactions/connections |
| ClientRepository | save(ClientInfo), findById(clientId) |
| AlertRepository | insert(Alert), findById(alertId), recent(limit) |
| AcknowledgementRepository | save(alertId, Acknowledgement), findByAlertId(alertId) |
| StorageValues | internal UUID, UTC timestamp, and alert text validation |

findById returns Optional.empty when absent. recent accepts only 1–100, selects the most
recent subset, and returns it oldest to newest; equal timestamps use ID as a deterministic
tie-breaker. It sorts by creation time, not insertion order. Acknowledgements are returned
by acknowledgement time then client ID; an unknown alert yields an empty list.
Acknowledgement save returns true for a new row and false for a duplicate pair.

For future integration, construct repositories with the **same SqliteDatabase instance**.
Its synchronized transaction method serializes reads and writes across repository types.
This simple approach suits the classroom server: no connection pool or extra database
worker thread is needed. The monitor is per instance, not a global lock across different
instances or JVMs. A five-second SQLite busy timeout bounds waiting on external locks;
a persistent lock still raises SQLException. WAL mode is not enabled for this strategy.

Every operation opens a fresh connection, enables and verifies PRAGMA foreign_keys = ON
before beginning its transaction, and applies the busy timeout. SQLite requires foreign
keys to be enabled for each connection; see the
[SQLite foreign-key guide](https://www.sqlite.org/foreignkeys.html).
Repository SQL uses PreparedStatement parameters, so quotes/Unicode remain data rather
than SQL. Each operation commits on success, rolls back on SQL/runtime failure, and closes
its ResultSet, statement, and connection with try-with-resources. No JDBC resources remain
open between calls or need an extra shutdown hook. Do not perform socket I/O inside a
storage transaction. SQLException signals storage failures; invalid model inputs/limits
raise IllegalArgumentException. Existing credential and socket shutdown rules are unchanged.

The default data directory, database extensions, and journal/WAL/shared-memory sidecars
are ignored by Git. Put custom filenames in the ignored data directory or use an ignored
.db, .sqlite, .sqlite3, or .db3 extension. Backups/data files should not be committed.

## Build, configure, and run

PowerShell window 1, using configured Temurin JAVA_HOME and the normal Maven cache:

```powershell
Set-Location 'C:\Users\ASUS\Desktop\Network Project\Real-Time-Emergency-Alert-and-Broadcast-System'
$env:EMERGENCY_ADMIN_USERNAME = 'classroom-admin-placeholder'
$env:EMERGENCY_ADMIN_PASSWORD = 'classroom-password-placeholder'
# Optional: omit this variable to use the same local default.
$env:EMERGENCY_DB_PATH = 'data\emergency-alerts.db'
$env:EMERGENCY_MAX_CONNECTIONS = '8'
$env:EMERGENCY_OUTGOING_QUEUE_CAPACITY = '32'

& "$env:JAVA_HOME\bin\java.exe" -version
mvn clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

$gsonJar = Join-Path $env:USERPROFILE '.m2\repository\com\google\code\gson\gson\2.13.2\gson-2.13.2.jar'
if (-not (Test-Path -LiteralPath $gsonJar)) { throw 'Gson JAR missing from normal Maven cache' }
$sqliteJar = Join-Path $env:USERPROFILE '.m2\repository\org\xerial\sqlite-jdbc\3.51.3.0\sqlite-jdbc-3.51.3.0.jar'
if (-not (Test-Path -LiteralPath $sqliteJar)) { throw 'SQLite JDBC JAR missing from normal Maven cache' }
$serverClasspath = "server\target\classes;common\target\classes;$gsonJar;$sqliteJar"
& "$env:JAVA_HOME\bin\java.exe" -cp $serverClasspath edu.ec5205.alert.server.ServerMain
```

The classpath includes server, common, Gson, and SQLite JDBC. These paths use the normal default
Maven repository used in this project; adjust only if your Maven settings use another path.
The server JAR alone does not include its runtime dependencies.

PowerShell window 2, using the same placeholder values:

```powershell
Set-Location 'C:\Users\ASUS\Desktop\Network Project\Real-Time-Emergency-Alert-and-Broadcast-System'
$env:EMERGENCY_ADMIN_USERNAME = 'classroom-admin-placeholder'
$env:EMERGENCY_ADMIN_PASSWORD = 'classroom-password-placeholder'
& .\server\scripts\Test-AdminAuthentication.ps1
& .\server\scripts\Test-Registration.ps1
& .\server\scripts\Test-AlertCreation.ps1 -DatabasePath 'data\emergency-alerts.db'
& .\server\scripts\Test-Broadcast.ps1 -DatabasePath 'data\emergency-alerts.db'
```

Separate PowerShell windows do not automatically share environment changes.
The authentication script prints six PASS messages, covering failed/successful login,
feature availability, isolation, and role restrictions. It never prints credentials
or LOGIN frames. The registration script prints five PASS messages for registration,
duplicates, identity protection, and re-registration. Both close their sockets in finally.
Close other idle clients if the configured capacity is occupied. Press Ctrl+C in window 1
for normal server shutdown.

Test-AlertCreation.ps1 checks unregistered/receiver authorization, admin login, INVALID_ALERT
correlation, exact ALERT_ACCEPTED fields, and a saved row reopened through AlertRepository
in a separate JVM. It prints five PASS messages and the created alert ID. VerifyAlert.java
is a source-file test helper, not production server code. This live check intentionally
adds one test alert to the selected database; use a disposable EMERGENCY_DB_PATH such as
data/alert-creation-demo.db in window 1 and pass the same path in window 2. No history
network endpoint is added for verification. Credentials/LOGIN JSON are never printed.

Test-Broadcast.ps1 opens two receivers and an admin, rejects unauthorized submissions,
verifies both receivers get identical ALERT frames, and reopens the database in a
separate JVM to compare the full delivered Alert against its stored row. It then closes
one receiver, creates a second alert, and verifies the healthy receiver still gets it.
The script intentionally inserts two test alerts. Use a disposable database such as
data/broadcast-demo.db in window 1 and pass that same path to the script in window 2.
The earlier creation check still works, but a registered test receiver will now have an
ALERT waiting in its incoming stream after a successful creation.

To repeat a missing-configuration startup check after stopping the server, in window 1:

```powershell
Remove-Item Env:EMERGENCY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
& "$env:JAVA_HOME\bin\java.exe" -cp $serverClasspath edu.ec5205.alert.server.ServerMain
if ($LASTEXITCODE -ne 1) { throw 'Expected configuration failure with exit code 1' }
$env:EMERGENCY_ADMIN_PASSWORD = 'classroom-password-placeholder'
```

This prints only the configuration error, without listening or revealing credentials.

## Verification

Full mvn -B clean verify passed with configured Temurin 17.0.21 and normal Maven cache:
60 shared-codec tests, 22 connection/registration/authentication tests, and 8 configuration
tests (90 total; zero failures, errors, or skipped tests).

Configuration tests start child JVMs running the real ServerMain and verify exit code 1
before listening for missing/empty/blank variables. Socket tests cover both credential
failures, retry, persistent admin connections, role restrictions, per-connection isolation,
authorization versus feature availability, admin disconnect state, and receiver regression.
The receiver tests preserve atomic duplicates, owner-checked cleanup, and re-registration.

Storage milestone verification: mvn -B clean verify passed using configured Temurin 17.0.21
and the normal Maven cache. The total is now 108 tests (60 common, 30 existing server,
14 storage repository tests, 4 storage startup cases), with zero failures/errors/skips.
Storage tests use isolated JUnit temporary files, never the default application database.
They cover reopen persistence, Unicode and quoted text, client upserts with existing ACKs,
chronological history/limits and fractional timestamps, duplicate pairs, foreign keys on
fresh connections, referenced-parent protection, rollback, shared-instance concurrency,
invalid model values, and startup rejection before accepting connections.

Live storage-startup verification also passed using an isolated temporary database:
the file existed before accepting connections, all authentication/registration checks
passed, and all three tables remained empty afterward in that storage-only milestone.
The test server shut down normally and temporary files were removed.

Alert-creation milestone: full mvn -B clean verify passed with Temurin 17.0.21 and the
normal cache: 61 common tests plus 69 server tests (130 total, zero failures/errors/skips).
The 21 new creation cases use isolated temporary databases and real loopback sockets.
They cover authorization/isolation, committed persistence visible before acceptance,
server-owned UUID/time, Unicode and validation boundaries/enums, request correlation,
reopening, real SQL failure with safe errors and recovery, concurrent admins, and no
broadcast/history/ACK network implementation. Existing registration/authentication and
storage tests also pass.

The repeatable live creation check passed with a temporary database: five creation
checks, all login/registration regression checks, and a further reopen after normal
server shutdown confirmed the accepted row persisted. Temporary files were removed.

Live verification also passed on port 5050: six authentication checks and five receiver
regression checks. Normal JVM shutdown released an idle authenticated admin socket;
captured stdout/stderr contained neither credential values nor complete LOGIN frames.

Broadcast milestone tests cover two receivers receiving the exact stored alert, no
delivery to admin/unregistered sockets, unauthorized requests, SQL failure with no
broadcast, receiver disconnect isolation, deterministic blocked-writer queue overflow,
single-writer frame integrity, registration/broadcast races, writer failure/cleanup,
idle/blocked writer shutdown, and bounded zero-wait reader admission. Prior registration,
authentication, alert-creation, storage, and shared-codec tests remain enabled.

Final broadcast verification: mvn -B clean verify passed with configured Temurin 17.0.21
and the normal Maven cache: 61 common tests plus 78 server tests (139 total, zero
failures/errors/skips). The live PowerShell broadcast/database check and authentication/
registration regressions passed. All eight configured connections received login replies;
the ninth connection was explicitly rejected and logged. Normal shutdown closed all
eight reader/writer connections without termination warnings. Test data was temporary
and removed afterward.
