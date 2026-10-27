# Member 2 — Admin application
Owner: Replace with name and GitHub handle.
Folder: admin/.

## Tasks in order
- [ ] Implement AdminConnection with configurable server host/port.
- [ ] Send LOGIN and handle LOGIN_RESULT.
- [ ] First send CREATE_ALERT from a console entry point.
- [ ] Build Swing login and dashboard screens.
- [ ] Add title, message, category, priority, and Send button.
- [ ] Validate required fields before sending; show server errors.
- [ ] Show ALERT_ACCEPTED with server alert ID (not a delivery guarantee).
- [ ] Show connected clients and acknowledgement statuses.
- [ ] Request and display server alert history.
- [ ] Display disconnection state and support manual reconnect.
- [ ] Close sockets and background workers when the window closes.

## Interface rules
Network reads/writes must not block the Swing event thread.
Use SwingUtilities.invokeLater for UI changes from network callbacks.
Use the common codec; do not invent another wire format.

## Completion evidence
Log in, submit an alert, see both receivers acknowledge, inspect history, and demonstrate invalid login/server disconnect behavior.
Document interface screenshots, workflow, and checks.

## First task
Connect to the server and submit the agreed LOGIN message. A temporary console interface is enough for milestone 1.
