# Member 1 — Server and integration
Owner: Team leader (replace with name and GitHub handle).
Folders: server/, common/; coordinate root build changes.

## Tasks in order
- [ ] Define shared Java models, MessageType enum, UTF-8 JSON line codec.
- [ ] Implement ServerMain and configurable port (default 5050).
- [ ] Accept sockets and handle multiple connections with ExecutorService.
- [ ] Register stable client IDs and manage connection state safely.
- [ ] Authenticate admins using configured credentials; reject unauthorized commands.
- [ ] Validate alerts, generate UUIDs and UTC timestamps, broadcast.
- [ ] Serialize concurrent writes per socket; isolate slow/disconnected receivers.
- [ ] Persist alerts and unique acknowledgements with SQLite/JDBC.
- [ ] Implement status, client list, and history responses.
- [ ] Close sockets and worker threads on shutdown.
- [ ] Integrate admin and client at every milestone.

## Completion evidence
Run admin plus two clients; verify broadcast, authentication rejection, duplicate ACK handling, persistence, reconnect history, and isolated disconnects.
Document your server design, threading, locking, schema, and integration findings.

## First task
Start a TCP listener, accept a client, and exchange a framed test message. Coordinate message types with both teammates before continuing.
