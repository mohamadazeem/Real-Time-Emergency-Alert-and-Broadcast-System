# Shared JSON protocol foundation

This module implements the version-1 message shapes in [docs/PROTOCOL.md](../docs/PROTOCOL.md).
The server now uses this codec for JSON receiver registration; see
[server/README.md](../server/README.md) for supported connection states and run commands.
This library defines and validates messages without implementing authentication,
broadcasting, storage, or acknowledgement processing.

## Models

`Messages` contains Java 17 records: small data containers with named fields, accessors,
value equality, and immutable references. List-bearing records copy their lists.
Constructors assemble data; the codec validates it before sending and after reading.
`ProtocolMessage` supplies version 1, and every message has a fixed `MessageType`.

| Wire type | Java record in `Messages` | Payload fields (besides version/type) |
| --- | --- | --- |
| REGISTER_CLIENT | RegisterClient | clientId, name |
| REGISTERED | Registered | clientId |
| LOGIN | Login | username, password |
| LOGIN_RESULT | LoginResult | success |
| CREATE_ALERT | CreateAlert | requestId, title, message, category, priority |
| ALERT | AlertMessage | alert |
| ALERT_ACCEPTED | AlertAccepted | requestId, alertId |
| ACK | Ack | alertId |
| ACK_RESULT | AckResult | alertId, success |
| ACK_STATUS | AckStatus | alertId, clientId, acknowledgedAt |
| GET_CLIENTS | GetClients | none |
| CLIENT_LIST | ClientList | clients |
| GET_HISTORY | GetHistory | limit |
| HISTORY | History | alerts |
| GET_ACKS | GetAcks | alertId |
| ACK_LIST | AckList | alertId, acknowledgements |
| ERROR | ErrorMessage | code, message, optional requestId |

Nested records preserve the documented shapes:

- `Alert`: id, createdAt, title, message, category, priority.
- `ClientInfo`: clientId, name.
- `Acknowledgement`: clientId, acknowledgedAt.

`Category` has FIRE, WEATHER, SECURITY, GENERAL. `Priority` has LOW, MEDIUM, CRITICAL.
Names are case-sensitive. Error codes remain strings because the protocol calls its
code list *suggested*, rather than exhaustive.

## Serialization and deserialization

Serialization converts a Java message into JSON. `encode(message)` adds `version` and
`type` alongside its payload fields; it does not introduce a payload wrapper.
Deserialization does the reverse: `decode(json)` checks the envelope, chooses the record
from `type`, validates fields, then asks Gson to construct that record.

The codec uses Gson 2.13.2 with `Strictness.STRICT`, as recommended in the
[Gson troubleshooting guide](https://google.github.io/gson/Troubleshooting.html#malformed-json-not-rejected).
Comments, single-quoted JSON, trailing commas, trailing JSON objects, arrays at the root,
and missing/null required fields are rejected. Strings, booleans, numbers, arrays, nested
objects, and enum values must have their expected JSON types; for example, `"true"` is
not accepted in place of `true`. GET_HISTORY requires an integer from 1 through 100.
Both encode and decode validate; constructing a record alone is not validation.

## Framing and resource ownership

TCP delivers bytes in arbitrary chunks. A frame is one UTF-8 JSON object followed by
byte LF (0x0A). `read(input)` collects bytes through one LF into a fixed 65,536-byte
buffer. The first extra non-LF byte raises `ProtocolException` immediately, without
waiting for EOF or allocating a growing line buffer. LF is excluded from the limit.
UTF-8 decoding reports invalid sequences rather than replacing them silently.

Partial arrivals, including splits inside UTF-8 characters, are collected until LF.
Consecutive frames are returned by successive calls to `read` without consuming bytes
from the next frame. An escaped JSON newline is backslash plus `n` on the wire, so it
does not end the frame. `write(output, message)` writes compact UTF-8 JSON plus one LF
and flushes. Both incoming and outgoing frame limits count bytes, not characters.

The caller owns the socket and streams; the codec does not close them. Keep one reader
and a retained `BufferedInputStream` per connection for efficient byte reads. Do not
mix that stream with another reader on the same socket. Writes synchronize on the
provided output stream through the flush: all senders for one connection must share
that same output-stream object. Multiple wrappers around one socket do not share this lock.

Clean EOF between frames returns `null`. EOF after any frame bytes but before LF raises
`EOFException`, even if those bytes form a complete JSON object. Frame and validation
failures raise `ProtocolException` (an `IOException`); socket failures propagate as
`IOException`. After an oversized frame, close the connection: unread bytes remain and
the codec deliberately does not attempt to drain an unbounded frame. A fully consumed
but invalid frame does not consume the next frame. Callers decide whether to
send ERROR or close; the codec does neither automatically.

## Interpretations of unspecified details

These choices preserve the documented fields and shapes without changing PROTOCOL.md:

- Query/reply messages described in prose use the same version/type envelope as the examples.
- All listed fields are required except ERROR.requestId, which is omitted when null.
  An explicitly supplied JSON null is rejected. CREATE_ALERT.requestId remains required.
- Unknown extra fields are ignored, including any clientId supplied in ACK. This codec
  does not establish a caller identity; the future server must bind identity to the socket.
- IDs use UUID strings with the standard 8-4-4-4-12 hexadecimal spelling. UTC timestamps
  use ISO-8601 strings with a zero offset (Z or +00:00); the spelling is retained.
- Alert title/message limits count Unicode code points (characters), without trimming
  or changing the text. Empty and whitespace-only values are rejected. No extra length
  rules are invented for names, usernames, passwords, or error text.
- Wire records retain supplied IDs, timestamps, and list ordering. The codec neither
  generates IDs/timestamps nor sorts history or enforces session authorization.
- Gson's default nesting limit is retained. The application does not define a new
  nesting format or allow arbitrary deeply nested extension data.

`Login.toString()` redacts credentials, and codec exceptions omit raw payloads.
Serialized LOGIN JSON still contains the password: never log raw messages or encoded JSON.

## How teammates use common

The server, admin, and client Maven modules already depend on `common`; they inherit
Gson transitively. JUnit is test-scoped and is not a production dependency. Teammates
should import these shared models and codec rather than maintain separate JSON field
names or parse messages using ad hoc string operations.

Example for a receiver socket integration:

```java
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.common.Messages.RegisterClient;
import java.io.BufferedInputStream;

var codec = new JsonMessageCodec();
var input = new BufferedInputStream(socket.getInputStream());
var output = socket.getOutputStream(); // retain this object for all writers

codec.write(output, new RegisterClient(retainedClientId, "Library PC"));
ProtocolMessage reply = codec.read(input); // null means clean EOF
```

Receiver/admin teammates can branch on `message.type()` and then cast to the matching
record to access its fields. Server teammates can use the same records for replies.
The server currently implements registration state; other application actions remain
future work. Build from the repository
root so Maven builds `common` before its dependents; use `mvn install` if another checkout
or separately built module needs the local SNAPSHOT artifact.

## Verification

Use the configured Temurin JDK 17 and normal Maven cache, from the repository root:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
mvn clean verify
```

Focused JUnit tests cover every documented message shape, all category/priority values,
optional error correlation, UTF-8, escaped newlines, byte-fragmented and consecutive frames,
strict JSON, missing/wrong fields, unsupported versions/types, UUIDs/UTC timestamps,
text/history limits, clean/truncated EOF, exact and excessive byte lengths, an endless
oversized frame, flush behavior, and caller stream ownership.

Milestone verification (2026-11-08): full reactor `mvn -U -B clean verify` passed with
Temurin 17.0.21 and the normal Maven cache. All 59 tests passed (zero failures, errors,
or skipped tests). A transient Maven Central DNS failure required a download retry.
No server, admin, or client source changes were made for that shared-foundation milestone.

The subsequent registration milestone adds `ProtocolException.requestId()`: a valid UUID
requestId recovered from parseable but invalid JSON, otherwise null. This lets the server
correlate ERROR replies without exposing raw payloads. The wire message shapes remain
unchanged. Verification now includes 60 common tests and 15 server registration tests;
see [the server guide](../server/README.md).
