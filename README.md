# Real-Time Emergency Alert and Broadcast System
EC5205 Operating Systems & Network Programming — three-member Java project.

## What we are building
An authenticated admin sends emergency alerts to a Java TCP server. The server stores and broadcasts them to connected Java receiver applications. Receivers acknowledge alerts, and the admin sees acknowledgement status.

This repository currently contains the team plan and a Maven starter structure. Application features are not implemented yet.

## Final responsibility split
| Member | Ownership | Start here |
| --- | --- | --- |
| Member 1 — team leader (you) | Server, database, shared protocol, integration | [Server tasks](docs/member-1-server.md) |
| Member 2 | Admin Swing application and its network connection | [Admin tasks](docs/member-2-admin.md) |
| Member 3 | Client Swing application and its network connection | [Client tasks](docs/member-3-client.md) |

Replace member labels with names and GitHub handles in the task documents.
Each member writes their component's documentation and tests; all members participate in integration and presentation.

## First steps for everyone
1. Read [the project plan](docs/PROJECT_PLAN.md).
2. Read your assigned task document.
3. Read [the shared protocol](docs/PROTOCOL.md) before writing socket code.
4. Follow [setup and collaboration](CONTRIBUTING.md).
5. Complete milestone 1 together before building the full interfaces.

## Technology baseline
Java 17, Maven, Swing, TCP sockets, ExecutorService, Gson for JSON, SQLite through JDBC.
Gson and SQLite dependencies will be added by Member 1 when implementing the shared codec and database. They are not needed for the current entry-point placeholders.

## Modules
- common: shared message types, models, encoding/decoding.
- server: connections, authentication, broadcasting, persistence.
- admin: sender interface.
- client: receiver interface.

## Build the starter
Install a JDK 17 and Maven, then verify `java -version` and `mvn -version`.

```sh
mvn clean verify
java -cp server/target/server-0.1.0-SNAPSHOT.jar edu.ec5205.alert.server.ServerMain
java -cp admin/target/admin-0.1.0-SNAPSHOT.jar edu.ec5205.alert.admin.AdminMain
java -cp client/target/client-0.1.0-SNAPSHOT.jar edu.ec5205.alert.client.ClientMain
```

These commands currently print starter messages only. Update launch instructions when dependencies and functionality are implemented.

## Definition of done
One admin sends an alert through the server to two separate computers over a LAN. Both show the alert; acknowledgements return to the admin; history survives server restart; disconnecting one receiver does not stop other receivers.
