# JSON registration, broadcast, history, acknowledgements, and live client lists

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
Receiver registration now saves the client before REGISTERED is queued. Receiver ACKs
persist through the existing AcknowledgementRepository; new rows notify authenticated
admins through ACK_STATUS. All replies and notifications use the existing bounded queues.
GET_HISTORY now retrieves stored alerts for authenticated admins and registered receivers.
The count limit and UTF-8 frame-size policy below bound every response.
Authenticated admins can use GET_ACKS to recover an alert's stored acknowledgement
status after reconnecting. GET_CLIENTS returns currently connected receivers, and
authenticated admins receive fresh CLIENT_LIST snapshots when receiver membership changes.

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
| GET_CLIENTS on ADMIN | CLIENT_LIST of live receivers, sorted by clientId; empty clients array when none are active | Remains open |
| Client-list snapshot exceeds 65,536 serialized UTF-8 bytes | ERROR / INVALID_MESSAGE explaining frame limit and absent pagination | Remains open |
| ACK without receiver role | ERROR / UNAUTHORIZED | Unregistered closes; admin remains |
| GET_HISTORY without receiver/admin role | ERROR / UNAUTHORIZED | Closes |
| GET_ACKS on ADMIN, existing alert | ACK_LIST with stored acknowledgements, or an empty array | Remains open |
| GET_ACKS on ADMIN, unknown alert | ERROR / UNKNOWN_ALERT | Remains open |
| GET_ACKS result exceeds 65,536 UTF-8 bytes | ERROR / INVALID_MESSAGE explaining frame limit and absent pagination | Remains open |
| GET_ACKS database/encoding failure | ERROR / INTERNAL_ERROR with generic explanation; no ACK_LIST | Remains open for retry |
| Invalid GET_ACKS alertId on ADMIN | ERROR / INVALID_MESSAGE | Closes only that socket |
| Valid GET_HISTORY on ADMIN or RECEIVER | HISTORY with the complete newest-N selection, oldest first; empty alerts array when no rows exist | Remains open |
| GET_HISTORY selection exceeds 65,536 UTF-8 bytes | ERROR / INVALID_MESSAGE with frame-size explanation and smaller-limit retry instruction | Remains open |
| GET_HISTORY storage/encoding failure | ERROR / INTERNAL_ERROR with generic explanation; no HISTORY | Remains open for retry |
| Invalid GET_HISTORY limit on ADMIN or RECEIVER | ERROR / INVALID_MESSAGE | Closes only that socket |
| Valid CREATE_ALERT on ADMIN | ALERT_ACCEPTED after successful commit | Remains open |
| Invalid CREATE_ALERT payload on ADMIN | ERROR / INVALID_ALERT with recoverable requestId | Remains open for correction |
| CREATE_ALERT database failure on ADMIN | ERROR / INTERNAL_ERROR, generic explanation and requestId | Remains open; no acceptance |
| ACK on RECEIVER, known alert | ACK_RESULT / true after successful storage, including duplicates | Remains open |
| ACK on RECEIVER, unknown alert | ERROR / UNKNOWN_ALERT | Remains open |
| ACK lookup/insert/commit failure | ERROR / INTERNAL_ERROR; no ACK_RESULT or ACK_STATUS | Remains open for retry |
| Registration database failure | ERROR / INTERNAL_ERROR; no REGISTERED or receiver publication | Remains unregistered; may retry |
| Other unexpected messages, including server reply types | ERROR / INVALID_MESSAGE | Unregistered closes; receiver/admin remains |
| Invalid JSON/envelope/requestId/UTF-8/version/type, oversized or truncated frame | ERROR / INVALID_MESSAGE when writable | Closes |
| Clean EOF | No reply | Closes and cleans up |

For example, GET_CLIENTS from an unregistered connection returns UNAUTHORIZED.
GET_CLIENTS from an authenticated admin returns the live receiver snapshot. GET_HISTORY
is permitted for admin or receiver; ACK is receiver-only. GET_ACKS is admin-only.
A recognized ACK with invalid fields from an unauthorized role returns UNAUTHORIZED;
invalid ACK fields on a receiver return INVALID_MESSAGE and close only that socket.
Broken JSON or invalid envelopes cannot be identified safely and return INVALID_MESSAGE.

## GET_CLIENTS and live membership updates

Stored clients and connected receivers answer different questions. SQLite clients rows
remember registered identities and names across disconnects/restarts and supply foreign-key
parents for acknowledgements. They do not prove that a receiver is online. GET_CLIENTS
reads ConnectionRegistry, which holds only active receiver connections and their registration
names. It never loads all historical clients from SQLite. Admins, unregistered sockets,
and departed receivers are excluded. Disconnect detection happens when the server observes
EOF, a socket/writer failure, queue overflow, or shutdown; no heartbeat is added.

1. After LOGIN succeeds, an admin may send the existing GetClients message. ClientHandler
   authorizes the connection and asks ConnectionRegistry to enqueue the response directly.
   A new admin is not sent a startup list automatically: query GET_CLIENTS for initial
   state, then continue reading unsolicited CLIENT_LIST updates. LOGIN_RESULT always
   precedes membership updates for that session.
2. Registration reserves the UUID and persists ClientInfo before success, as before.
   Inside the membership lock, REGISTERED is offered to the receiver's queue first.
   Only then are the connection and its immutable clientId/name published together.
   The registry queues a fresh snapshot to all authenticated admins. Failed storage,
   rejected duplicate IDs, and failed REGISTERED offers publish no phantom receiver.
3. Each snapshot uses the existing ClientList / ClientInfo records, sorted lexically by
   clientId ignoring UUID letter case, consistent with case-insensitive identity keys.
   Original registered spelling and names are retained. No receivers means clients: [].
   A receiver's disconnect removes only its owned entry and queues a new full snapshot.
   Late cleanup cannot remove a replacement connection or its new display name.
4. Membership mutation, snapshot construction, and nonblocking queue offers all use the
   same registry monitor. Explicit GET_CLIENTS replies use that monitor too. An earlier
   snapshot therefore cannot be enqueued after a newer snapshot on the same admin queue.
   Readers can replace their displayed list with each complete snapshot; no new revision
   field or JSON shape is needed. Unchanged-membership queries can return identical lists.
   The monitor is not held during SQLite storage, socket writes, or waits for a writer.
5. Each snapshot is encoded once for validation and UTF-8 frame sizing before enqueueing.
   Names have no additional character limit in the shared protocol; even valid individual
   REGISTER_CLIENT frames can produce an oversized aggregate CLIENT_LIST. Exactly 65,536
   bytes excluding LF is allowed. Oversized query replies and push snapshots become
   INVALID_MESSAGE with "Client list exceeds the 65536-byte frame limit; this protocol
   has no pagination." No partial list is sent. The admin stays connected, and a later
   smaller membership snapshot can succeed. Current connection limits bound the number
   of names examined; the database's historical client count is irrelevant.
6. All replies/events use the existing bounded FIFO queues and one writer per socket.
   Offers never wait for space. A stalled admin that fills its queue is disconnected
   and removed from subscriptions; other admins and receiver registration continue.
   Notification iteration uses a copy because overflow cleanup can synchronously remove
   an admin. Reader exit, writer failure, overflow, and shutdown retain owner-checked
   receiver removal and admin cleanup. As before, socket failure/full queues can prevent
   final reply delivery; the explicit size-error policy itself does not close a socket.

CLIENT_LIST can arrive between replies to other admin requests, just like ACK_STATUS.
Admin consumers should dispatch frames by type instead of assuming every next frame is
the reply to their latest command. Existing regression scripts consume membership events
while checking their original features; Test-GetClients.ps1 checks the events themselves.

## GET_ACKS flow, frame limits, and reconnect recovery

1. The shared codec validates the unchanged GetAcks model: alertId must be a required,
   non-null UUID string in the documented 8-4-4-4-12 spelling. ClientHandler requires the
   ADMIN role on this socket. Receivers receive UNAUTHORIZED and stay registered;
   unregistered sockets, including failed logins, receive UNAUTHORIZED and close.
   Recognized GET_ACKS requests with invalid fields are also unauthorized on those roles.
   Invalid IDs on an authenticated admin receive INVALID_MESSAGE and close that socket,
   following the existing invalid-message policy.
2. AcknowledgementQueryService checks AlertRepository.findById first. An unknown alert
   returns UNKNOWN_ALERT. A known alert with no acknowledgements returns the existing
   AckList(alertId, acknowledgements) model with an empty array. ACK_LIST echoes the
   requested alertId spelling; lookups remain case-insensitive.
3. AcknowledgementRepository reads rows ordered by acknowledgedAtSort, then clientId
   using the existing case-insensitive ID collation. The normalized nanosecond sort key
   orders timestamps by actual UTC instant. The response returns the original stored
   client IDs and timestamp strings, including fractional precision and Z/+00:00 spelling.
   Duplicate acknowledgements still retain the first timestamp.
4. The service measures the serialized UTF-8 frame before enqueueing, using shared-codec
   empty/singleton ACK_LIST sizes plus array commas. Normal model fields are ASCII UUIDs
   and ISO UTC timestamps, but the check always counts UTF-8 bytes and JSON escaping.
   Exactly 65,536 bytes excluding LF is allowed. Larger results receive INVALID_MESSAGE:
   "Acknowledgement list exceeds the 65536-byte frame limit; this protocol has no pagination."
   The socket stays open. No truncated list, split response, or success-shaped empty list
   replaces the result. GET_ACKS has no limit/cursor field, so a full oversized list cannot
   be retrieved through this protocol version; other alerts can still be queried.
5. To bound row allocation, the query requests at most floor(65536 / 36) + 1 = 1,821
   rows. Each valid row contains a 36-byte UUID, so that prefix alone cannot fit, even
   before timestamp/JSON overhead. It necessarily produces the explicit size error.
   Therefore every successful ACK_LIST contains all stored rows, never a truncated prefix.
   The repository's original findByAlertId(alertId) API remains available to existing callers;
   the new maximumRows overload is an internal allocation guard, not a wire pagination API.
6. JDBC resources and transactions close before ClientHandler offers the response to the
   existing bounded outgoing queue. One writer serializes it with the unchanged JSON shape.
   SQL/encoding failures return INTERNAL_ERROR with "Unable to retrieve acknowledgements."
   No SQL, database path, driver details, or misleading empty results reach the caller.
   The same admin socket can retry after recovery. Existing overflow, disconnect isolation,
   FIFO ordering, connection capacity, and shutdown cleanup remain in effect.

GET_ACKS only reads data. It does not insert/update acknowledgements or emit ACK_STATUS.
ACK_STATUS is a live event and is not replayed on login. After reconnecting, an admin
logs in, uses GET_HISTORY to identify alerts, and sends GET_ACKS for selected alert IDs.
The returned snapshot includes acknowledgements committed while that admin was offline,
with their original timestamps. Continue listening for live ACK_STATUS events afterward.
Live events can overlap a query snapshot: combine records by (alertId, clientId) so that
the same acknowledgement is counted once, regardless of reply/event arrival order.

## GET_HISTORY bounded-response policy

GET_HISTORY uses the existing limit of 1 through 100. The selected newest N alerts
are returned oldest to newest, with alert ID breaking equal-timestamp ties. The entire
HISTORY JSON must fit in 65,536 UTF-8 bytes, excluding its final LF. Valid large alerts
can exceed this aggregate limit: four 4,000-emoji messages alone consume 64,000 bytes,
before titles, IDs, timestamps, and JSON overhead. Escaped control characters also grow
when serialized, so neither character counts nor row counts are sufficient size checks.

Before enqueueing HISTORY, the server measures the exact encoded UTF-8 size using the
shared codec, including the envelope, array commas, and JSON escaping. If the complete
selection is too large, it queues ERROR / INVALID_MESSAGE with an explanation of the
65,536-byte limit and an instruction to retry with a smaller limit. The socket stays open.
It sends no partial HISTORY, does not silently shorten the selected list, and does not
split one reply across multiple HISTORY frames. This policy preserves the shared JSON
shapes and newest-N meaning. A limit of 1 accommodates any normally stored valid alert.
If the result fits exactly at 65,536 bytes, it is sent. Existing queue-overflow and socket
failure policies still apply; response-size rejection itself does not disconnect anyone.

## GET_HISTORY request-to-database-to-response flow

1. The shared codec reads one JSON frame and validates the existing GetHistory model.
   limit is required and must be a JSON integer from 1 to 100. Missing/null limits,
   strings, booleans, fractions, and out-of-range numbers produce INVALID_MESSAGE on
   authorized connections. This follows the existing invalid-frame closure policy.
2. ClientHandler checks the role attached to this socket. Only ADMIN and RECEIVER may
   retrieve history. Unregistered sockets, including failed logins, receive UNAUTHORIZED
   and close. Recognized GET_HISTORY requests with invalid fields also require a role;
   broken JSON/unsupported envelopes still produce INVALID_MESSAGE.
3. HistoryService asks AlertRepository.recent(limit) for the bounded selection. SQLite
   first orders alerts by createdAtSort descending and ID descending, then selects N.
   It returns that selection in ascending timestamp/ID order. createdAtSort normalizes
   timestamps to UTC nanoseconds, so equivalent fractional spellings sort together.
   Alert UUID keys use the existing case-insensitive collation to break timestamp ties.
   No rows means History with an empty alerts array, not an error.
4. The read transaction and its JDBC resources finish before delivery. HistoryService
   measures serialized singleton HISTORY frames and the empty envelope using the shared
   codec. Subtracting each singleton's envelope and adding array commas gives the exact
   complete response size, including UTF-8 and JSON escaping. This avoids constructing
   an oversized aggregate JSON frame and does not duplicate the shared serializer.
5. ClientHandler offers HISTORY, or the explicit size error, to the existing bounded FIFO
   queue. The connection's single writer sends the unchanged JSON shape plus LF. A
   SQLException or invalid stored model encoding yields generic INTERNAL_ERROR with
   "Unable to retrieve alert history." No SQL/path/driver details or misleading empty
   HISTORY are sent. The same socket can retry after storage recovers.

History retrieval only reads alert rows. It does not mark an alert delivered, create an
acknowledgement, or send ACK_STATUS. A user must explicitly send ACK separately.
Clients must deduplicate ALERT and HISTORY entries by alert ID: a live broadcast can
appear again in history, including when a broadcast races the query. The snapshot is
not a cursor or a guarantee that every missed alert is recoverable. Clients receive one
reply per query; live ALERT/ACK_STATUS frames may appear between replies in enqueue order.

## ACK request-to-database flow

Application delivery and a user acknowledgement are separate events. Sending ALERT to
a receiver means the application attempted delivery. It does not establish that a person
read it. ACK represents an explicit user action, such as pressing an acknowledgement
button. Neither delivery nor opening the receiver connection automatically creates an ACK.

1. Before registration succeeds, ConnectionRegistry reserves the UUID against competing
   registrations. ClientRepository saves the client ID and display name in SQLite, then
   commits. This creates the parent row required by the acknowledgement foreign key.
   Storage runs outside the membership lock, so it does not block membership snapshots.
   Only afterward does the registry queue REGISTERED and publish the active receiver.
   A duplicate active/reserved ID cannot overwrite the owner's saved name. Failure releases
   the reservation; disconnect removes active membership but retains the saved client.
2. JsonMessageCodec validates the existing ACK shape and alert UUID. ClientHandler accepts
   it only on a RECEIVER connection. It passes the client ID retained from that socket's
   successful registration to AcknowledgementService. Extra clientId or acknowledgedAt
   fields in the request cannot choose another identity or timestamp.
3. AcknowledgementService asks AlertRepository whether the alert exists. Absence returns
   UNKNOWN_ALERT; a failed lookup returns INTERNAL_ERROR. A known alert gets a server UTC
   timestamp from Instant.now(). No history query command is needed for this check.
4. AcknowledgementRepository inserts the alert/client pair in a short SQLite transaction.
   The existing composite primary key allows one row per pair, including case variants
   of UUIDs. ON CONFLICT DO NOTHING leaves the first committed timestamp untouched.
   save returns true for a new row and false for an existing pair, only after commit.
   The same database instance is shared by all repositories and handlers.
5. Only a newly committed row causes ACK_STATUS(alertId, registered clientId, stored time)
   to be offered to a snapshot of authenticated admin queues. Repeated and concurrent
   duplicates succeed without another row or another new-acknowledgement notification.
6. ClientHandler queues the existing ACK_RESULT(alertId, true) after storage succeeds.
   Both inserts and duplicates receive success. SQL failures return a generic
   INTERNAL_ERROR, never a success reply or a status notification. Retry after recovery
   is safe. No SQL details, credentials, or database paths appear in these errors.

ACK_RESULT confirms persistence. ACK_STATUS is a live notification to currently connected
authenticated admins; it is not a durable notification queue. A disconnect or queue
overflow can lose a reply/status after commit, while the acknowledgement remains saved.
Re-sending ACK then returns success without resending ACK_STATUS. GET_HISTORY retrieves
stored alerts; GET_ACKS retrieves stored acknowledgement status. GET_CLIENTS separately
reports the live registry and does not change acknowledgements.

Admin login queues LOGIN_RESULT before adding the session to notification snapshots.
Receiver registration queues REGISTERED before allowing broadcast snapshots. Each socket's
single writer preserves its FIFO enqueue order; there is no cross-socket arrival order
guarantee between ACK_STATUS and ACK_RESULT, or global ordering between concurrent ACKs.
Notification offers occur after database transactions end. A full or failed admin queue
disconnects only that admin; storage and other admin deliveries continue. Admin subscriptions
are removed on reader exit, writer failure, overflow, and server shutdown.

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
- ServerMain creates AlertCreationService, AcknowledgementService, HistoryService,
  AcknowledgementQueryService, and alert/client/ACK
  repositories using its initialized SqliteDatabase and shares them across handlers.
- ConnectionRegistry maps UUIDs to ClientConnection objects under a short membership
  lock and retains each active receiver's immutable ClientInfo. Duplicate checks,
  queuing REGISTERED, publishing the receiver, and queuing membership snapshots are atomic
  with respect to membership snapshots. UUID spellings are case-insensitive.
  remove(id, connection) prevents stale cleanup from removing a replacement owner.
- Handler finally blocks remove receiver registrations and run the completion callback.
  Admin subscriptions disappear with the handler or writer cleanup; admins are tracked
  separately from receiver identities and never receive receiver ALERT broadcasts.
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
No delivery timeout, retry buffer, or guaranteed delivery is added. ACK processing
persists explicit user acknowledgements; it does not make socket delivery reliable.

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
| AcknowledgementRepository | save(alertId, Acknowledgement), findByAlertId(alertId), findByAlertId(alertId, maximumRows) |
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
& .\server\scripts\Test-Acknowledgement.ps1 -DatabasePath 'data\emergency-alerts.db'
& .\server\scripts\Test-History.ps1 -DatabasePath 'data\emergency-alerts.db'
& .\server\scripts\Test-GetAcks.ps1 -DatabasePath 'data\emergency-alerts.db'
& .\server\scripts\Test-GetClients.ps1 -DatabasePath 'data\emergency-alerts.db'
```

Separate PowerShell windows do not automatically share environment changes.
The authentication script prints six PASS messages, covering failed/successful login,
live-list access, isolation, and role restrictions. It never prints credentials
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

Test-Acknowledgement.ps1 uses unique UUIDs each run and closes every socket in finally.
Use a disposable EMERGENCY_DB_PATH such as data/acknowledgement-demo.db in window 1,
and pass the same path in window 2. Each run intentionally saves one alert, one client,
and one acknowledgement. The script checks receiver-only authorization, unknown alerts,
delivery without automatic ACK, exact ACK_RESULT/ACK_STATUS fields, connection-bound
identity, duplicate success without extra notifications, disconnect cleanup, and renamed
client re-registration. VerifyAcknowledgement.java reopens the file in a separate JVM
three times to check the saved parent rows, single ACK, and unchanged first timestamp.
Raw status JSON is retained temporarily to avoid PowerShell timestamp precision loss;
the temporary frame file is deleted in finally. No credentials or LOGIN JSON are printed.
Storage failures and truly concurrent duplicates are exercised by the automated tests.

Test-History.ps1 runs against a local server using the same placeholder credentials and
database path. Start window 1 with a disposable EMERGENCY_DB_PATH such as
data/history-demo.db, then use this in window 2:

```powershell
& .\server\scripts\Test-History.ps1 -DatabasePath 'data\history-demo.db' -RequireEmpty
# Repeat against the same database after the first run (omit -RequireEmpty):
& .\server\scripts\Test-History.ps1 -DatabasePath 'data\history-demo.db'
```

The optional -RequireEmpty verifies the initial empty-array response and requires a fresh
database. Each run saves four valid test alerts containing maximum-length emoji titles
and messages, plus a registered test client. It checks both authorized roles, unauthorized
access, invalid limits, live/history duplicate IDs, explicit oversize errors with open
sockets, and complete smaller-limit retries. VerifyHistory.java reopens the database in
a separate JVM to compare the full response against recent(limit), including stored fields
and ordering, and confirms the new alerts have no acknowledgements. Raw JSON is retained
temporarily to preserve timestamp precision. All sockets and the temporary frame file
are cleaned up in finally. The script never prints credentials or LOGIN JSON. Run on an
isolated test server without other alert creators for repeatable selection comparisons.
Automated tests additionally cover SQL failures, timestamp ties, 100-of-101 selection,
JSON escaping, and exact/one-byte-over frame boundaries.

Test-GetAcks.ps1 runs against a local server with the same credentials and database path.
Use a disposable EMERGENCY_DB_PATH such as data/get-acks-demo.db in window 1, then run
this from the repository root in window 2:

```powershell
& .\server\scripts\Test-GetAcks.ps1 -DatabasePath 'data\get-acks-demo.db'
# Run the same command again to repeat with fresh IDs.
```

Each run adds one test alert, two registered clients, and two explicit acknowledgements.
It checks unauthorized roles, UUID validation, unknown versus empty results, exact ACK_LIST
fields, original timestamps, and status recovery after disconnecting the admin before the
second acknowledgement. Four separate VerifyAckList.java JVMs reopen the database and
compare complete ordered results with storage. Repeated queries followed by another reply
check that queries do not generate ACK_STATUS. Raw JSON files preserve timestamp precision;
all sockets and temporary JSON files are closed/removed in finally. Credentials and LOGIN
JSON are never printed. Automated tests cover database failures and frame-size boundaries.

Test-GetClients.ps1 checks the exact query and push frames with two authenticated admins.
Use an isolated local test server without other connected receivers and a disposable
EMERGENCY_DB_PATH such as data/get-clients-demo.db in window 1. In window 2, run:

```powershell
& .\server\scripts\Test-GetClients.ps1 -DatabasePath 'data\get-clients-demo.db'
# Repeat with the same command; all receiver IDs are fresh each run.
```

The script verifies empty results, unauthorized roles, joins in reverse ID order,
deterministic sorted names/IDs, duplicate rejection without false updates, disconnects,
and reconnect with a new name. Each run leaves two historical client rows and no active
receivers. VerifyLiveClients.java reopens SQLite in a separate JVM to confirm both rows
remain stored while the final live list is empty. All sockets and the temporary JSON
file are cleaned up in finally; credentials/LOGIN JSON are never printed. Automated tests
add concurrent membership/query ordering, login races, storage failure without publication,
blocked-admin queue isolation, failed REGISTERED offers, writer cleanup, and exact/oversized
UTF-8 list frames.

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

Persistent acknowledgement milestone (2026-11-09): mvn -B clean verify passed with
Temurin 17.0.21+9 and the normal Maven cache: 61 common tests and 94 server tests
(155 total; zero failures, errors, or skipped tests). The new acknowledgement suite
uses real loopback sockets, isolated temporary SQLite files, and test-only SQL triggers.
It covers committed ACK replies/status, multiple authenticated admins, ignored supplied
identity/time, unauthorized roles (including invalid ACK fields), unknown alerts, invalid
messages, insert/lookup failures and recovery, sequential/case-variant/concurrent duplicates,
registration commit/failure/retry, duplicate registration names, disconnect/reconnect, and
reopened persistence. Queue tests also verify that an overflowing blocked admin writer
cannot prevent ACK commits or notifications to a healthy admin, and that shutdown releases
the admin writer. Existing repository tests continue to verify foreign keys and rollback.

The live Test-Acknowledgement.ps1 passed, including three separate-JVM database reopen
checks. Test-AdminAuthentication.ps1, Test-Registration.ps1, Test-AlertCreation.ps1, and
Test-Broadcast.ps1 also passed against the same isolated local server. Normal JVM shutdown
returned exit code 0 with no reader/writer termination warnings. A connection-reset log
from a deliberately disposed test socket was observed; healthy connections continued.
The disposable database and temporary live launcher were removed afterward. The existing
schema, repositories, shared JSON models, admin module, and client module are unchanged.

GET_HISTORY milestone (2026-11-09): mvn -B clean verify passed with Temurin 17.0.21+9
and the normal Maven cache: 61 common tests and 120 server tests (181 total, zero
failures/errors/skips). HistoryTest adds 26 cases covering admin/receiver access,
unregistered and failed-login rejection, empty arrays, limit validation, newest-N selection,
nanosecond chronology and equal-instant ID ties, 100-of-101 selection, reopening storage,
generic SQL errors/recovery, FIFO replies, and no automatic acknowledgements. Large-frame
cases cover emoji, JSON escaping, an exact 65,536-byte response, and an explicit error
for one byte over followed by a successful smaller retry on the same socket.

The live Test-History.ps1 passed twice against the same disposable database: first with
-RequireEmpty, then without. Separate JVMs reopened storage to verify complete ordered
responses and absent acknowledgements. Authentication, registration, alert creation,
broadcast, and acknowledgement live regression scripts also passed. Normal JVM shutdown
returned exit code 0 with no reader/writer termination warnings. A connection-reset log
from deliberate test-socket disposal was observed. The disposable database and temporary
launcher were removed. The prior uncommitted acknowledgement work was preserved; common,
admin, client, existing repository SQL/schema, queue capacity, and shutdown code are unchanged.

GET_ACKS milestone (2026-11-09): mvn -B clean verify passed with Temurin 17.0.21+9
and the normal Maven cache: 61 common tests and 143 server tests (204 total, zero
failures/errors/skips). AcknowledgementQueryTest adds 23 cases for admin-only access,
unauthorized/failed-login roles, UUID validation, unknown versus empty results, timestamp
ordering/ties/original spellings, case-insensitive lookup, repeated read-only queries,
reconnect and reopened persistence, SQL lookup/list failures and recovery, invalid stored
encoding, FIFO replies, and serialized sizes of 65,535/65,536/65,537 bytes. A 1,900-row
fixture verifies that the allocation guard cannot return a truncated success.

Test-GetAcks.ps1 passed twice against the same disposable database, including eight
separate-JVM database reopen checks and recovery of acknowledgements committed while the
admin was offline. Authentication, registration, alert creation, broadcast, acknowledgement,
and history live regression scripts also passed. Normal JVM shutdown returned exit code 0
with no reader/writer termination warnings. A connection-reset log from deliberate socket
disposal was observed. Temporary database/launcher files were removed. Existing uncommitted
work was preserved. Shared models/JSON shapes, schema, admin/client modules, queue capacity,
and shutdown code were unchanged in that milestone; GET_CLIENTS was not yet implemented.

GET_CLIENTS milestone (2026-11-09): mvn -B clean verify passed with Temurin 17.0.21+9
and the normal Maven cache: 61 common tests and 159 server tests (220 total, zero
failures/errors/skips). ClientListTest adds 16 cases that read every membership frame:
authorization/isolation, empty versus historical clients, names and deterministic ordering,
join/leave updates to multiple admins, duplicate rejection without events, REGISTERED before
publication, persistence failure/retry, login races, concurrent registration/query/removal
ordering, stale-owner cleanup, blocked-admin overflow, failed receiver offers, writer failure,
and serialized UTF-8 frame boundaries. Earlier feature tests consume unsolicited membership
frames while retaining their original assertions.

Test-GetClients.ps1 passed twice on the same disposable database. Separate JVMs confirmed
that disconnected client identities persist while the live list is empty. Authentication,
registration, alert creation, broadcast, acknowledgement, history, and GET_ACKS live
regression scripts all passed. Normal shutdown closed an active registered receiver and
an authenticated admin subscribed to updates; JVM exit code was 0 with no reader/writer
termination warnings. Reset/writer-failure logs from deliberately disposed regression-test
sockets were observed. The temporary database and launcher were removed. Earlier uncommitted
work was preserved. Shared models/JSON shapes, common/admin/client modules, database schema,
capacity settings, and writer/shutdown implementation are unchanged.
