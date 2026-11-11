# Archived initial integration review (before receiver completion)

Historical commands and paths below are preserved as recorded. Current runtime paths
and start commands are in the root README and RUNTIME_CLEANUP.md.

This is the preserved initial review. Its receiver gaps are superseded by
[the current integration review](INTEGRATION_REVIEW.md).

Reviewed on **2026-11-11**, Asia/Colombo. **Not ready for the full agreed classroom
demonstration.** The backend and admin application communicate successfully with the
real receiver networking implementation. The shipped receiver is console-only and has
no user ACK control, history workflow, or in-process reconnect control. A limited
admin-to-two-console-receivers broadcast demonstration is feasible; full acceptance
requires the receiver work and a separate-laptop LAN rehearsal.

## Revision, safety, and environment

- Reviewed merge: `fb7c4fafc1a882a099ad8cf4fb111615979b4075` (`fb7c4fa`, merge PR #7).
- Checkout started clean on `main`, tracking `origin/main`. Origin is
  `https://github.com/mohamadazeem/Real-Time-Emergency-Alert-and-Broadcast-System.git`.
- Inspected branches, remote, status, and recent commits. After a successful fetch,
  `git rev-list --left-right --count HEAD...origin/main` returned `0 0`. No update was
  necessary. Changes are uncommitted on `integration-review`; main was not edited.
- No applicable `AGENTS.md` was found in the repository or its ancestor directories.
  Read README, CONTRIBUTING, PROJECT_PLAN, PROTOCOL, all three member task documents,
  common/server READMEs, four module POMs and the parent, production code, and relevant
  tests. There are no admin/client module READMEs in this revision.
- Windows 11 amd64; configured Temurin **17.0.21+9**, Maven **3.9.16**. JAVA_HOME:
  `C:\Users\ASUS\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21.9-hotspot`.
  Maven used the normal `C:\Users\ASUS\.m2\repository` cache, without replacement
  cache flags. Compiler release and source encoding are 17 and UTF-8.
- Initial sandbox attempts could not access GitHub or the configured JDK. Approved
  execution outside those restrictions resolved both; no alternate runtime was used.
  Java is absent from the initial PATH, so commands below prepend JAVA_HOME/bin.
- All new server runs used unique databases and identities under ignored
  `integration-evidence/run-*` or `integration-evidence/gui-*`. Existing application
  databases/identities were not opened. Test secrets were generated, never printed.
  The GUI fixture's temporary password file was removed on fixture cleanup.
- No commits, pushes, merges, deployment, teammate messages, or firewall changes.
- Final process inventory found **0** remaining test Java processes and **0** temporary
  GUI password files. Existing unrelated processes were not terminated.

## Requirement checklist

Paths below are repository-relative. Production filenames identify the implementation;
test filenames identify evidence, rather than substituting documentation for code.
**Implemented** means the named layer exists and has the cited evidence, not that
separate-laptop delivery or every GUI interaction has been verified.

| Requirement | Final status | Source and evidence / limitation |
| --- | --- | --- |
| Shared version-1 JSON shapes | Implemented | `common/src/main/java/edu/ec5205/alert/common/{Messages,MessageType,ProtocolMessage}.java`; codec round trips and real-app harness |
| UTF-8, one JSON object + LF, partial/consecutive frames | Implemented | `common/.../JsonMessageCodec.java`; `common/.../JsonMessageCodecTest.java` tests split UTF-8 and escaped newlines |
| 65,536-byte incoming/outgoing frame bound | Implemented | Fixed byte buffer and encoder checks in `JsonMessageCodec`; exact/over-limit tests; receiver now closes after framing errors |
| Multiple connections, bounded admission | Implemented | `server/.../ServerMain.java`, `ServerLimits.java`: fixed reader pool, SynchronousQueue, immediate rejection; `ServerLimitsTest` |
| Serialized output, isolated slow receivers/admins | Implemented | `server/.../ClientConnection.java`: dedicated writer and bounded nonblocking mailbox, close on overflow; `OutgoingDeliveryTest` uses deliberately blocked output streams (not a physical slow LAN peer) |
| Registry synchronization and owner-checked cleanup | Implemented | `server/.../ConnectionRegistry.java`; registration races and stale-owner cleanup in `ClientRegistrationTest`, `ClientListTest` |
| Receiver registration and stable UUID file | Implemented | `client/.../storage/ClientIdentityStorage.java`, `net/ClientNetworkManager.java`; file reload and real registration checked |
| Reject duplicate active identity | Implemented | UUID-keyed registry reservations; real client-manager duplicate rejected with DUPLICATE_CLIENT; original stays active |
| Receiver reconnect API | Implemented | `ClientNetworkManager.connect`, retained identity loaded again by harness |
| Receiver reconnect control/workflow in application | Missing | `ClientMain` delegates unconditionally to `console/ConsoleClient`; no reconnect command/control; restart manually reuses UUID |
| Admin authentication and per-connection authorization | Implemented | `server/.../{AdminCredentials,ClientHandler,ConnectionRegistry}.java`; real invalid/valid login and fresh unauthorized connection checked; server suites cover role isolation |
| Validate alert title/message/category/priority | Implemented | `common/.../MessageValidation.java`, admin `service/AlertService.java`; Unicode code-point boundaries, invalid enums and blank input tests |
| Server-generated UUID/UTC time | Implemented | `server/.../AlertCreationService.java`; real delivery has accepted ID; server creation tests validate ownership/time |
| Commit SQLite alert before acceptance, broadcast stored fields | Implemented | `AlertCreationService`, `storage/AlertRepository`, `SqliteDatabase`; real receivers compare entire Alert record, including timestamp and content |
| ACK networking and persistence | Implemented | `ClientNetworkManager.sendAck`, `server/.../AcknowledgementService.java`; harness deliberately invokes ACK after delivery and checks ACK_RESULT |
| Explicit human ACK through receiver interface | Missing | No button or console input loop in `ConsoleClient`; network API availability does not satisfy this requirement |
| Duplicate ACK count/timestamp preservation | Implemented | SQLite pair primary key + ON CONFLICT DO NOTHING; harness compares exact original ACK records and absence of duplicate status; concurrent duplicates covered by server tests |
| Admin live ACK events | Implemented | `AdminConnection`, `AlertMonitoringPanel`; actual-server harness observes two distinct ACK_STATUS events. Visual live push after tab return remains not yet verified; lifecycle regression uses real panel listeners |
| Alert history and ACK queries | Implemented | `server/.../{HistoryService,AcknowledgementQueryService}.java`; real admin services query both before/after restart |
| Live connected-client query and push | Implemented | `ConnectionRegistry` live map, not historical database rows; harness queries two/one/zero clients, server tests verify unsolicited membership frames |
| Recent history retrieval and deduplication components | Implemented | `ClientNetworkManager.requestHistory`, `service/AlertHistoryManager`; harness explicitly requests/merges recent history twice, checks missed alert and 12 distinct IDs |
| Receiver application history retrieval and duplicate display suppression | Missing | `ConsoleClient` neither requests nor handles HISTORY nor uses `AlertHistoryManager`; its onAlertReceived prints every incoming alert |
| Admin Swing login/dashboard/create/clients/history/monitoring | Implemented | `admin/.../ui/*.java`, `AdminMain`; real GUI login, send, roster, stored ACK query and history checked |
| Receiver Swing connection/alert/history and priority highlighting | Missing | No receiver Swing code; `ClientMain` always runs ConsoleClient, including graphical desktops |
| Background network work and safe Swing callback updates | Implemented | Admin reader + SwingWorker for requests; EDT done/invokeLater. Fixed ACK selection access on EDT. Network-stall responsiveness remains not yet verified |
| Admin manual reconnect and ACK recovery | Implemented | Logout closes socket and opens LoginFrame; new session can Load Alerts/Query ACKs. Harness uses a fresh AdminConnection/AuthService and recovers offline ACK |
| Normal restart retains alerts and ACKs | Implemented | Two distinct server child JVMs reopen same isolated SQLite file; 13 alerts, first alert's two ACKs and offline ACK checked |
| Shutdown closes sockets/workers and releases listener | Implemented | Server hook closes listener/connections, interrupts pool and awaits threads; harness signals normal JVM exit, observes receiver/admin disconnects, restarts and rebinds same port |
| Configurable host and server port | Implemented | Admin login fields; receiver --host/--port; added server EMERGENCY_PORT (default 5050), validated before storage/listen |
| Error handling | Implemented | Controlled protocol/storage errors and tests; malformed receiver frame now closes. Readiness still depends on UI paths and limits listed below |
| Launch instructions / runtime dependencies | Implemented | Updated root README; `scripts/Prepare-Demo.ps1` prepares all four application jars plus Gson, SQLite and annotations; packaged harness passes |
| Actual multi-laptop LAN, real human ACK, receiver GUI responsiveness | Not yet verified / Missing | LAN never tested here; GUI/ACK controls are missing, so that part cannot be verified yet |

## Findings by severity

### High: incomplete receiver application (remaining scope gap)

`client/src/main/java/edu/ec5205/alert/client/ClientMain.java` calls
`ConsoleClient.main(args)` unconditionally. Its comment explicitly leaves Swing for a
future phase. The console prints alerts, registers a shutdown hook, and waits for the
connection to end. It never reads an ACK command, calls `sendAck`, requests history,
handles HISTORY, reconnects in-process, or uses `AlertHistoryManager`. Building blocks
and mock-server tests exist, but their wiring is supplied by the tests/harness.

Member 3's Swing screen, Acknowledge button, history and reconnect workflow remain
required. No entire GUI was added during this review. This blocks the planned full
classroom demo and any claim that a human acknowledged an alert through the receiver.

### Medium: live admin listeners lost across tab visibility changes (fixed)

`ConnectedClientsPanel` and `AlertMonitoringPanel` removed their network listeners on
ancestorRemoved and never reattached them on ancestorAdded. Returning to a hidden tab
could leave it without live updates. Both now remove/add once when reattached, retaining
existing explicit dispose cleanup. `PanelLiveUpdatesTest` exercises the actual panel
callbacks, checks listener counts across remove/add/repeated-add/dispose, and also
tests that an ACK queued for a previous selection cannot populate another alert's table.

The ACK callback now reads selection and applies the row inside the same EDT task;
previously it read a Swing combo on the network reader and queued a selection-dependent
write later. No wire or service API changed.

### Medium: receiver attempted to continue after frame failure (fixed)

`ClientNetworkManager.listenLoop` continued after ProtocolException, even though an
oversized frame can leave unread bytes. It now breaks and closes via connection-loss
cleanup, matching the codec contract. A new test sends 65,537 bytes with no LF and
checks prompt CONNECTION_LOST; no further data or EOF is required to unblock it.

### Medium: server port hard-coded / launch guidance incomplete (fixed)

`ServerMain` bound only to 5050 despite the plan requiring a configurable port. It now
accepts EMERGENCY_PORT, with 5050 default and startup validation for 1-65535. The real
integration harness uses a dynamically chosen nondefault loopback port, and the unit
test checks default, override and invalid values. This changes startup configuration,
not architecture or shared protocol shapes.

Root README incorrectly called the code a starter and launched individual module jars
without dependencies. The revised instructions prepare/test a seven-jar runtime directory.
The protocol document's stale starter sentence was updated; JSON shapes are unchanged.

### Remaining limitations and focused follow-up

- The protocol's bounded history may exceed 65,536 bytes even below 100 records.
  Server explicitly returns an error requesting a smaller limit. Admin panels request
  50 records; neither application implements adaptive smaller-limit retry. Demo alerts
  should remain short; test the UI with long histories before claiming broader readiness.
- Admin queries other than CREATE_ALERT have no protocol correlation ID. Concurrent
  same-type queries or unsolicited CLIENT_LIST can satisfy type-only matchers. Use
  serial manual queries for the demo; review request serialization/correlation before
  broader concurrent usage. No shared protocol change was introduced here.
- Identity save errors print an error and return the generated identity, so a failed
  write can prevent stable identity on the next launch. Verify a writable identity
  directory. Corrupt files intentionally regenerate identity in the current implementation.
- Reconnect testing uses a **new** AdminConnection/AuthService. Reusing the same admin
  connection object immediately across reader shutdown and a new socket is not established
  by this review; the GUI creates a fresh pair when returning to login.
- No heartbeat or socket inactivity timeout: an idle connection can occupy one of the
  bounded slots. A single stalled writer without subsequent queue overflow may remain
  until disconnect/shutdown. Queue isolation is not a real-time delivery guarantee.
- ACK monitoring can still need a manual query after selection/reconnect. Visual live
  push behavior under rapid selection/query races needs a targeted GUI test.

## Build and test results

Ran the requested **`mvn -B clean verify`** on the clean merged revision, then again
after the fixes. Baseline: **274 tests**, no failures, errors or skips. Final run:
**279 tests**, no failures, errors or skips; BUILD SUCCESS in **36.163 s**, finishing
2026-11-11 16:46:07 +05:30.

| Module | Baseline tests | Final tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 61 | 61 | 0 | 0 | 0 |
| server | 159 | 160 | 0 | 0 | 0 |
| admin | 44 | 47 | 0 | 0 | 0 |
| client | 10 | 11 | 0 | 0 | 0 |
| Total | 274 | 279 | 0 | 0 | 0 |

Suite-level counts and exact final harness output are retained in
[INTEGRATION_EVIDENCE.txt](INTEGRATION_EVIDENCE.txt). Raw reports are in each module's
target/surefire-reports; local logs are `integration-build.log`,
`integration-build-final.log`, `integration-harness-final.log`,
`integration-packaged-final.log`, and `integration-gui.log` (ignored generated files).

What the existing tests prove:

- Common: all documented shapes, JSON/UTF-8 strictness, fragmentation, validation and
  exact frame bounds, without opening an application server.
- Server: actual handler/services with real loopback sockets and temporary SQLite,
  startup child JVMs, concurrency, authorization, storage/reopen, failure injection,
  client lists and query frame bounds. Slow-writer tests deliberately substitute
  blocked/test streams, giving deterministic queue-isolation evidence.
- Admin: mostly scripted mini servers for connection/auth/alert/query behavior.
  AdminUITest's three cases construct/default-check Swing components and tabs; they
  do not send alerts through the real server. AckMonitoringTest's original duplicate
  case mirrors a HashSet and does not prove the actual table behavior.
- Client: ConsoleClientIntegrationTest starts a **mock server**, then wires real client
  managers and history models in test code. It does not exercise ConsoleClient's user
  workflow. Separate storage/history tests prove those helper classes.
- New regressions: one server port case, three real-panel lifecycle/selection cases,
  one receiver oversized-frame case. Assertions cover changed behavior, not just names.

## Real-application end-to-end evidence

Run **`& .\scripts\Test-Integration.ps1`** after Maven verification. It compiles the
standalone harness with javac --release 17, using Maven-resolved production dependency
paths from Surefire reports. The harness rejects non-Java-17 runtime execution.

Layers actually exercised:

1. A child JVM calls the production `ServerMain.main`, so credentials/configuration,
   SQLite startup, real accept loop, handler pool, registry, writers and shutdown hook
   all execute. A tiny launcher waits on stdin solely to request System.exit(0).
   This runs normal JVM shutdown hooks; forced Windows process termination is not
   used as normal-shutdown evidence.
2. Real `AdminConnection`, `AdminAuthService`, `AlertService` send/query over TCP.
3. Two real `ClientNetworkManager` instances load independent disposable identity
   files, receive through background readers, send ACKs, and request history. The
   **harness** connects their callbacks to the real `AlertHistoryManager`.
4. A separate actual `ClientMain` JVM registers and prints an alert sent through
   the real admin service/server, then exits normally when the server shuts down.
5. A second server JVM reopens the same database; a fresh admin session verifies
   exact persisted acknowledgements and 13 stored alerts.

Passing checks include invalid login followed by valid login, per-connection rejection,
two distinct clients in live lists, duplicate identity rejection, identical complete
Alert records at both receivers, two independently sent harness ACKs/ACK_RESULTs and
both live admin updates, duplicate count/timestamp preservation/no duplicate push,
history/ACK queries, isolated disconnect, retained identity reconnect, missed recent
history and repeated-history deduplication, offline ACK recovery after admin reconnect,
normal restart persistence, socket closure, child exits and same-port rebind.

Receiver reconnect/history passing here proves **component compatibility**. It does
not make the absent ConsoleClient history/reconnect/ACK workflow implemented.

The final harness was also run with **`demo-dist/lib/*`**, proving the prepared runtime
jars carry the dependencies needed by the real applications. It does not use raw
socket scripts as its proof of integration. All servers/child receivers were stopped;
their credential-free logs and isolated data remain in the generated run directories.

### Local latency

Measured `System.nanoTime()` just before invoking real `AlertService.broadcastAlert`
and immediately on each real receiver's onAlertReceived callback in the harness JVM.
Thus measurements include admin encoding/send, server validation/SQLite/broadcast,
TCP, receiver decode and callback scheduling. They exclude human action and GUI paint.
Eleven alerts to two receivers plus one alert to the remaining receiver give **23
receiver-delivery samples per run**. The extra console-entry smoke alert is not timed.

| Final run | n | Minimum ms | Median ms | p95 ms | Maximum ms | Mean ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Build-class classpath | 23 | 8.924 | 13.860 | 28.786 | 29.031 | 15.162 |
| Packaged runtime jars | 23 | 8.508 | 15.503 | 34.412 | 34.781 | 16.261 |

Nearest-rank p95; median is the middle of 23 sorted values. These are small, warm/local
loopback observations on one Windows machine, not guaranteed real-time deadlines,
WAN/LAN latency estimates, or delivery guarantees. Do not pool these runs as one trial.

## GUI checks actually performed

Launched the real **AdminMain** through the isolated GUI fixture, and controlled its
native window using the computer-use skill. Observed:

- Login form rendered, configurable host/port and credentials accepted; real login
  opened the four-tab dashboard and showed Connected: localhost:5050.
- Entered `Classroom GUI test` and a short evacuation test message, FIRE/CRITICAL;
  clicked BROADCAST ALERT, observed disabled fields/progress state, followed by
  acceptance with server alert ID and request ID.
- Connected Clients -> Refresh Client List displayed both fixture receivers.
- Alert Monitoring -> Load Alerts -> Query ACKs displayed two stored client IDs with
  UTC acknowledgement times.
- Alert History -> Query Server History displayed the persisted test alert.
- Tab navigation and ordinary successful requests were usable; no visible freeze was
  observed. This is not a measured responsiveness test under network stalls.

The fixture's two networking receivers ACKed **automatically for testing**; no human
receiver clicked an Acknowledge control. GUI monitoring queried stored ACKs; live ACK
push after tab return, invalid-login visual feedback, manual admin reconnect, and the
visual disconnect indicator were not separately exercised. They remain manual checks.
No receiver GUI exists to launch. The actual console entry point was tested separately.
The fixture was stopped with its stop-file signal, all windows disappeared, and its
process and server exited with code 0.

Repeatable GUI fixture (uses port 5050; leave any existing server untouched and stop
it yourself first if appropriate):

```powershell
& .\scripts\Test-Integration.ps1 -GuiFixture
```

In another PowerShell in the repo, inspect the unique directory named in
`integration-evidence\gui-directory.txt`. Username is `integration-admin`. The generated
test password is in that directory's `test-password.tmp`; open it locally only to enter
the test login, never paste it into logs/chat. The file is removed by cleanup. Stop:

```powershell
$guiDirectory = Get-Content .\integration-evidence\gui-directory.txt -Raw
New-Item -ItemType File -Path (Join-Path $guiDirectory 'stop')
```

## Windows PowerShell LAN commands

These commands match the final reviewed **uncommitted integration branch**. Other
laptops' GitHub main will not include these fixes. Copy the reviewed source tree for
a local build on each laptop, or copy the prepared `demo-dist` directory intact. Run
all commands from the repository root (or the parent containing demo-dist).

Build and prepare on the development laptop; JAVA_HOME must point to its installed JDK 17:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
mvn -B clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
& .\scripts\Test-Integration.ps1
& .\scripts\Prepare-Demo.ps1
```

Prepare-Demo copies four application jars, Gson 2.13.2, sqlite-jdbc 3.51.3.0 and
error_prone_annotations 2.41.0 into demo-dist/lib. Use a fresh demo-dist directory if
dependency versions change, to avoid competing older jars. There are no fat jars,
Main-Class manifests or java -jar launcher; use -cp with the wildcard as below.

### Server laptop

Use a **new** demo database filename, and keep that filename for the restart check.
The following refuses an existing filename for the first demo to protect existing data.

```powershell
Get-NetIPConfiguration
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    Format-Table InterfaceAlias,IPAddress

$env:EMERGENCY_PORT = '5050'
$env:EMERGENCY_ADMIN_USERNAME = 'classroom-admin'
$demoPassword = Read-Host 'Disposable classroom password' -AsSecureString
$env:EMERGENCY_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $demoPassword).Password
$demoDatabase = Join-Path (Get-Location) 'data\classroom-demo-new.db'
if (Test-Path -LiteralPath $demoDatabase) { throw 'Choose a new demo database filename first.' }
$env:EMERGENCY_DB_PATH = $demoDatabase
try {
    & "$env:JAVA_HOME\bin\java.exe" -cp '.\demo-dist\lib\*' edu.ec5205.alert.server.ServerMain
} finally {
    Remove-Item Env:EMERGENCY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable demoPassword -ErrorAction SilentlyContinue
}
```

Select the active Wi-Fi/Ethernet adapter's LAN IPv4, not localhost, VPN, virtual adapter,
link-local or default-gateway address. Share that address and port with the other laptops.
Server binds all interfaces; only clients/admin need the remote host. For a **restart**,
retain EMERGENCY_DB_PATH, prompt for the same disposable password again and rerun Java:

```powershell
$demoPassword = Read-Host 'Same disposable classroom password' -AsSecureString
$env:EMERGENCY_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $demoPassword).Password
try {
    & "$env:JAVA_HOME\bin\java.exe" -cp '.\demo-dist\lib\*' edu.ec5205.alert.server.ServerMain
} finally {
    Remove-Item Env:EMERGENCY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable demoPassword -ErrorAction SilentlyContinue
}
```

If the trusted LAN requires an allowance, manually allow the configured JDK's java.exe
or inbound TCP port 5050 **only on the Private profile**, preferably scoped to the local
subnet, using Windows Firewall's normal UI. Confirm the network is the intended private
classroom network. Do not disable the firewall. This review did not change firewall rules.

### Admin laptop

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp '.\demo-dist\lib\*' edu.ec5205.alert.admin.AdminMain
```

In the real login window enter the server's LAN IPv4, port 5050, classroom-admin and
the disposable password set on the server. Passing --host to AdminMain does not set
GUI connection fields; enter them in the form. Console diagnostics, if needed:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp '.\demo-dist\lib\*' edu.ec5205.alert.admin.AdminMain --console
```

The console prompts for connection details and supports queries, not alert creation.

### Receiver laptop A

```powershell
$serverHost = Read-Host 'Server laptop LAN IPv4'
Test-NetConnection -ComputerName $serverHost -Port 5050
& "$env:JAVA_HOME\bin\java.exe" -cp '.\demo-dist\lib\*' edu.ec5205.alert.client.ClientMain --host $serverHost --port 5050 --name 'Receiver A' --id-file '.\receiver-a-identity.json'
```

### Receiver laptop B

```powershell
$serverHost = Read-Host 'Server laptop LAN IPv4'
Test-NetConnection -ComputerName $serverHost -Port 5050
& "$env:JAVA_HOME\bin\java.exe" -cp '.\demo-dist\lib\*' edu.ec5205.alert.client.ClientMain --host $serverHost --port 5050 --name 'Receiver B' --id-file '.\receiver-b-identity.json'
```

Each laptop must create its own identity file. Do not distribute a pre-created identity
file to both laptops. Retain each file for reconnect tests; a shared file on the same
machine produces duplicate-ID rejection. The receiver currently prints name/ID and all
alert fields except alert ID; the harness compares IDs directly through the callbacks.

## Live demonstration and remaining manual checks

**Separate-laptop checks below have not been run here.** Localhost is not evidence of
LAN delivery, Windows firewall configuration, Wi-Fi isolation or human acknowledgement.

1. Put server, admin and both receiver laptops on the same trusted LAN. Prepare the
   runtime jars/JDK 17 on each. Discover server IPv4 and manually check any required
   Private-network allowance. Expected: Test-NetConnection TcpTestSucceeded is True.
2. Start the server with the disposable database. Expected: listening on 5050; no
   configuration/storage errors. Start receiver A and B with separate identity files.
   Expected: distinct UUIDs and Registration successful in both console windows.
3. Start the admin GUI. Try a wrong password first, then the correct test password.
   Expected: rejected login stays in login form; correct login opens dashboard. The
   invalid-login GUI path still needs this manual check (service path passed locally).
4. Refresh Connected Clients. Expected: exactly Receiver A and Receiver B. Create a
   short FIRE/CRITICAL test alert. Expected: acceptance with server ID, and identical
   title/message/category/priority/timestamp printed on both receiver consoles. Query
   Alert History and confirm the row exists. Record screenshots on each laptop.
5. **Current blocker:** neither receiver can acknowledge through its application UI.
   Therefore the planned two-human-ACK step cannot be completed with this revision.
   Do not substitute a harness ACK and call it a user action. Once Member 3 supplies
   the planned interface, click Acknowledge on A, then B; expected admin live count
   goes 1 then 2, duplicate click cannot increase it, stored query remains exactly 2.
6. Stop receiver A with Ctrl+C. Send a second alert. Expected: B still receives it and
   admin roster drops A. Restart A using the same command/file: UUID must remain the
   same. Registration works now; **missed-history display is blocked by missing
   ConsoleClient history wiring**. After receiver workflow completion, verify exactly
   one display for the missed alert and no repeated entries after history refresh.
7. Logout/reconnect admin (fresh login). Load Alerts, select the test alert, Query ACKs.
   Expected: original stored status recovers; after receiver work, ACK while admin is
   offline and verify it appears on query after reconnect. Switch monitoring/clients
   tabs away/back while receiver membership/ACKs change to verify visual live updates.
8. Stop server with Ctrl+C and restart using the same database path. Expected: clients
   detect closure, no shutdown timeout warnings, listener can rebind, Alert History and
   ACK queries recover saved records. Manually restart/reconnect receivers as needed.
9. Check responsiveness during an unreachable-host connection and disconnected-server
   query: windows must repaint/move, progress end with controlled error, and a new login
   must work afterward. This stress behavior was not verified visually in this review.

Troubleshooting:

| Symptom | Check |
| --- | --- |
| Java/Maven unavailable | JAVA_HOME must be an installed JDK 17; prepend its bin; check both version outputs. Do not substitute a different runtime silently |
| NoClassDefFoundError / JDBC driver missing | Use all of demo-dist/lib with the quoted wildcard; rebuild/prepare if jars are missing |
| Connection refused | Start server; verify port matches and no bind error. Check configured address rather than default localhost |
| Remote connection timeout | Correct active LAN IPv4, same subnet, manual Private firewall allowance, AP/client isolation; do not disable firewall |
| Port already used | Stop the owning server you control or set EMERGENCY_PORT and matching admin/receiver port; no automatic process killing |
| Authentication fails | Both server variables required; username/password exact. Admin trims username; use simple classroom username. Restart server after changing credentials |
| Duplicate client | Stop the original instance or select a separate identity file for the additional laptop; keep retained IDs for reconnect |
| Registration fails intermittently | Default 8 total sockets includes admins/unregistered clients. Close spare tools and inspect capacity/queue logs |
| Empty ACK table | Current receiver UI cannot ACK. With completed receiver workflow, select the correct alert and Query ACKs after reconnect |
| History too large | Retry a smaller limit via networking API/admin console diagnostics; current GUI does not adapt its fixed 50-record request |
| Identity changes after restart | Confirm same --id-file path and writable directory; inspect save/corruption warning without overwriting real user data |

## Changed files and disposition

- `server/.../ServerMain.java`, `server/.../ServerLimitsTest.java`: configurable validated
  port with regression test.
- `admin/.../ui/ConnectedClientsPanel.java`, `AlertMonitoringPanel.java`: restore live
  subscriptions after reattachment, perform ACK selection access on EDT.
- `admin/src/test/java/edu/ec5205/alert/admin/ui/PanelLiveUpdatesTest.java`: three focused
  regressions against actual panel listeners/models.
- `client/.../net/ClientNetworkManager.java`, `client/.../ConsoleClientIntegrationTest.java`:
  close on protocol framing error and verify prompt close on oversized incomplete frame.
- `scripts/IntegrationHarness.java`, `scripts/Test-Integration.ps1`: repeatable real
  application integration, monotonic latency, normal-restart and GUI fixture checks.
- `scripts/Prepare-Demo.ps1`: runtime dependency/application jar preparation.
- `README.md`, `server/README.md`, `docs/PROTOCOL.md`: current launch/status guidance.
- `.gitignore`: exclude generated isolated evidence/data and runtime distribution.
- `docs/INTEGRATION_REVIEW.md`, `docs/INTEGRATION_EVIDENCE.txt`: this report and evidence.

No significant architecture or shared JSON change was made. No missing Swing receiver
was built. All files remain reviewable and uncommitted on integration-review. The system
is suitable for a **limited local/backend broadcast demo**, but the agreed complete
classroom demo remains blocked on the receiver application workflow and a recorded
multi-laptop LAN/user-ACK rehearsal.
