# Final scope and milestones
## Required features
- Server-enforced admin authentication.
- Alert title, message, category, priority, server ID, and UTC timestamp.
- Multiple simultaneous receivers.
- Broadcast to all currently connected receivers.
- User acknowledgements and admin acknowledgement status.
- Persistent server alert and acknowledgement history.
- Explicit connection state, clean disconnection, manual reconnect.
- History retrieval after reconnect, with duplicate suppression by alert ID.

## Out of scope for the first release
SMS, mobile apps, geographic targeting, internet deployment, automatic hazard detection, and guaranteed emergency delivery. This is an educational LAN prototype.

## Milestones
1. Connectivity: admin sends one structured alert through server to two console receivers. Each owner supplies their own component.
2. Core workflow: server authentication, IDs, validation, acknowledgements; admin displays status; clients acknowledge.
3. Persistence and UI: SQLite history, Swing windows, history retrieval and duplicate suppression.
4. Reliability and demonstration: malformed messages, reconnects, concurrent sends, multiple laptops, report and presentation.

Use these as sequence gates; assign actual dates together.

## Acceptance checks
- Unauthenticated send attempts are rejected.
- One alert reaches two connected receivers.
- Server acceptance is distinct from user acknowledgement.
- Duplicate acknowledgements count once per client and alert.
- One disconnected or slow receiver does not stop others.
- History remains after server restart.
- Reconnecting receivers can retrieve recent history without duplicate display.
- Empty/oversized/malformed input produces a controlled error.
- Network I/O does not freeze Swing windows.

## OS and networking evidence
Document separate processes, thread-pool handling, shared-state synchronization, blocking socket I/O, GUI/background-thread communication, resource cleanup, and TCP message framing.

## Shared delivery work
Each member documents architecture, screenshots, and checks for their component.
Together produce system architecture, integration results, limitations, demo script, slides, and final report.
