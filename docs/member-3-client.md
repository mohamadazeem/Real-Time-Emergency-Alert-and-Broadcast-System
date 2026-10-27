# Member 3 — Receiver application
Owner: Replace with name and GitHub handle.
Folder: client/.

## Tasks in order
- [ ] Implement ClientConnection with configurable host/port.
- [ ] Generate and retain a stable UUID clientId locally; accept a display name.
- [ ] Send REGISTER_CLIENT; receive REGISTERED.
- [ ] First receive and print ALERT messages in a console.
- [ ] Build Swing connection and alert/history screens.
- [ ] Display title, message, category, priority, and timestamp.
- [ ] Highlight critical alerts and add an Acknowledge button.
- [ ] Send ACK and handle ACK_RESULT.
- [ ] Track displayed alert IDs to suppress duplicates.
- [ ] Show connection status and manual reconnect.
- [ ] Retrieve recent history on reconnect.
- [ ] Close sockets and background workers on exit.

## Interface rules
Receive in a background thread; update Swing through SwingUtilities.invokeLater.
Use the common codec. Treat client application receipt separately from a user clicking Acknowledge.

## Completion evidence
Run two separate clients, display the same alert, acknowledge independently, reconnect and retrieve history without duplicate display.
Document interface screenshots, workflow, and checks.

## First task
Connect, register, and display an ALERT from the server in the console.
