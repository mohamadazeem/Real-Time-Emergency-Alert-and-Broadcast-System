# Shared protocol — version 1 baseline
All three members must agree to changes here before changing implementations.

## Transport and framing
TCP, UTF-8. Exactly one JSON object followed by LF per message; flush after writing.
JSON encoders escape newlines inside text. Buffer partial reads and process multiple complete frames.
Maximum frame size: 65536 bytes excluding LF; enforce while reading before unbounded allocation.
Default port: 5050, configurable. One serialized writer per connection.
Include version: 1 and type in every object. Validate types, required fields and allowed enum values.
The current starter does not yet implement this protocol.

## Connection state
An admin sends LOGIN; only successfully authenticated sessions may CREATE_ALERT or GET_CLIENTS.
A receiver sends REGISTER_CLIENT first. Server binds that socket to clientId and ignores any caller-supplied identity in ACK.
A clientId is a locally retained UUID; duplicate active IDs must be rejected to avoid ambiguous status.
Client identities are for the classroom demo, not proof of real-world identity.
Never log passwords; plaintext TCP credentials are only suitable for an isolated classroom prototype.

## Message examples
Client → server:
```json
{"version":1,"type":"REGISTER_CLIENT","clientId":"<uuid>","name":"Library PC"}
```
Server → client:
```json
{"version":1,"type":"REGISTERED","clientId":"<uuid>"}
```
Admin → server:
```json
{"version":1,"type":"LOGIN","username":"admin","password":"<configured-secret>"}
```
Server → admin:
```json
{"version":1,"type":"LOGIN_RESULT","success":true}
```
Admin → server:
```json
{"version":1,"type":"CREATE_ALERT","requestId":"<uuid>","title":"Fire Emergency","message":"Evacuate the building.","category":"FIRE","priority":"CRITICAL"}
```
Server → receivers:
```json
{"version":1,"type":"ALERT","alert":{"id":"<server-uuid>","createdAt":"2026-10-27T09:30:00Z","title":"Fire Emergency","message":"Evacuate the building.","category":"FIRE","priority":"CRITICAL"}}
```
Server → admin after storage:
```json
{"version":1,"type":"ALERT_ACCEPTED","requestId":"<uuid>","alertId":"<server-uuid>"}
```
Receiver → server:
```json
{"version":1,"type":"ACK","alertId":"<server-uuid>"}
```
Server → receiver:
```json
{"version":1,"type":"ACK_RESULT","alertId":"<server-uuid>","success":true}
```
Server → admin:
```json
{"version":1,"type":"ACK_STATUS","alertId":"<server-uuid>","clientId":"<uuid>","acknowledgedAt":"2026-10-27T09:31:00Z"}
```

## Queries and errors
- GET_CLIENTS (authenticated admin) → CLIENT_LIST with clients: [{clientId,name}]. Server sends a fresh list when membership changes.
- GET_HISTORY with limit (1–100) → HISTORY with alerts array, ordered oldest to newest within the most recent limit. Available to authenticated admins and registered receivers.
- GET_ACKS with alertId (admin) → ACK_LIST with alertId and acknowledgements: [{clientId,acknowledgedAt}].
- Invalid request → ERROR with code, message, and requestId if supplied.
- Suggested error codes: UNAUTHORIZED, INVALID_MESSAGE, INVALID_ALERT, DUPLICATE_CLIENT, UNKNOWN_ALERT, INTERNAL_ERROR.

## Validation and status
Title: 1–120 characters; message: 1–4000 characters after rejecting whitespace-only values.
Categories: FIRE, WEATHER, SECURITY, GENERAL. Priorities: LOW, MEDIUM, CRITICAL.
Server creates alert IDs and UTC timestamps. Persist ACK uniquely by (alertId, clientId); repeated ACK returns success without double counting.
On admin login, obtain history and GET_ACKS for selected alerts.
On receiver reconnect, register then GET_HISTORY (limit 100); deduplicate ALERT/HISTORY entries by ID.
History retrieval is bounded and does not promise every missed alert.
ALERT_ACCEPTED means stored and accepted for broadcasting. ACK means a user explicitly acknowledged.
