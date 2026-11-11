# Integration review after receiver completion

Runtime layout update: generated artifacts now live under runtime/. See
[RUNTIME_CLEANUP.md](RUNTIME_CLEANUP.md) for the latest path verification and commands.
GUI behavior was not rerun during cleanup; the GUI observations below are historical.


Updated **2026-11-11 (Asia/Colombo)**. The missing Member 3 desktop workflow is now
implemented in the existing client module. The complete local classroom workflow is
ready for rehearsal: real admin GUI -> real server -> two receiver GUIs -> explicit
Acknowledge controls -> live admin status, history and reconnect. Separate-laptop LAN
and actual human operation still require the team's rehearsal.

The [initial review](INTEGRATION_REVIEW_INITIAL.md) and
[initial evidence](INTEGRATION_EVIDENCE.txt) are retained as historical records. Their
missing-receiver findings describe the earlier state and are superseded by this report.

## Revision, environment and preservation

- Base commit: `fb7c4fafc1a882a099ad8cf4fb111615979b4075`. Still on `integration-review`.
  Git status/branches were inspected before changes; the earlier uncommitted server,
  admin, protocol-documentation, packaging and integration fixes were preserved.
- No applicable AGENTS.md was found in the repository or ancestor directories. Read
  the initial review, PROTOCOL, common README, Member 3 task instructions, existing
  receiver sources and tests. Verified before implementation that ClientMain always
  launched ConsoleClient, and that ConsoleClient did not wire ACK/history/reconnect.
- Configured **Temurin 17.0.21+9**, **Maven 3.9.16**, Windows 11 amd64, compiler release
  17, UTF-8 source, normal `C:\Users\ASUS\.m2\repository` cache. JAVA_HOME points to
  `C:\Users\ASUS\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.21.9-hotspot`.
  Approved execution outside sandbox restrictions accessed this configured JDK/cache;
  no alternate Java runtime or Maven cache was substituted.
- All verification used unique databases/identity files under ignored runtime/evidence
  directories. No existing user database or identity was opened by the verification.
  Test credentials were disposable, never printed, and temporary GUI secret files were
  removed. No firewall changes, commits, pushes, merges or teammate messages.
- Final process inventory confirmed **0** remaining test Java processes and **0**
  temporary GUI password files; unrelated processes were left alone.
- No server/admin/common production implementation or shared JSON shape changed during
  receiver completion. Ancillary changes outside client were explained beforehand:
  verification scripts and documentation. The previous harness now explicitly selects
  --console for its console-entry smoke test because default startup is now graphical.

## Current requirement checklist

Paths are relative to the repository. `client/...` below expands to
`client/src/main/java/edu/ec5205/alert/client/`.

| Requirement | Status | Implementation and evidence |
| --- | --- | --- |
| Configurable host/port/name | Implemented | `ClientMain.Options`, `ui/ReceiverFrame`; actual GUI connected with editable fields, failure/recovery checked |
| Stable identity | Implemented | Existing `storage/ClientIdentityStorage` reused and hardened; same UUID across reconnect; separate files for A/B |
| Default Swing launch / explicit console | Implemented | `ClientMain` defaults to ReceiverFrame; --console preserves diagnostics; actual GUI entry and console harness both passed |
| Headless behavior | Implemented | Explanation with --console guidance, no silent fallback/identity creation; child-JVM ClientMainTest |
| Connection/error status | Implemented | Controller snapshots and GUI state/notice; unavailable local port, restored connection and disconnect checked visually |
| Full alert content/ID/time | Implemented | Table and detail area; real admin-created alert displayed identically at both GUIs |
| Critical highlighting | Implemented | Red banner, tinted bold rows; visually verified |
| User-operated ACK control | Implemented | Button invokes controller; no automatic ACK in production or receiver GUI fixture |
| Pending/confirmed/failed/retry | Implemented | PENDING before send; success only after matching ACK_RESULT; failed reply/error permits retry; timeout closes uncertain session; deterministic tests gate the reply |
| Duplicate submission guard | Implemented | One operation in flight; confirmed alert button disabled; controller rejects repeated requests; server pair uniqueness unchanged |
| History / live deduplication | Implemented | Existing AlertHistoryManager reused by controller; snapshot sorted by UTC time/ID; visual refresh/reconnect and real-server harness verify no duplicate IDs |
| Automatic history after registration | Implemented | Controller calls GET_HISTORY after each successful registration, including reconnect |
| Oversized history retry | Implemented | Limits 100/50/25/12/6/3/1, at most seven attempts; real server forced reduction through 3; tests also verify successful/failed terminal limit 1 |
| Manual retained-identity reconnect | Implemented | Reconnect creates a fresh guarded network session; actual GUI recovered one missed alert with same UUID |
| Network work off EDT | Implemented | Dedicated command/cleanup executors; reader thread; identity load/save off EDT; timers never perform I/O |
| Swing changes only on EDT | Implemented | Controller state/observers and frame rendering EDT-only; callbacks posted with generation guard; observer assertions and queued-stale-callback test |
| Old callbacks cannot alter replacement | Implemented | Per-socket ownership in manager plus per-session generation in controller; deterministic old callback queued before reconnect and ignored afterward |
| Responsive unavailable connection / cleanup | Implemented with limits | Failed-port GUI recovered; stalled-registration close test and normal shutdown integration passed. Long DNS/blackholed network behavior is still a manual rehearsal |
| Shared protocol/auth/storage/server behavior | Implemented / preserved | Common/server/admin tests and earlier real-app integration passed unchanged; no extra dependencies |
| Human acknowledgements / separate-laptop LAN | Not yet verified | GUI automation clicked real controls; automated harnesses invoked ACK programmatically. No human or multi-laptop test is claimed |

## Implementation and concrete fixes

`service/ReceiverController` owns the receiver workflow, reusing ClientNetworkManager,
ClientIdentityStorage, AlertHistoryManager, ReceivedAlert and AckStatusState. Its mutable
state belongs to the EDT. A background executor performs identity/connection/write work;
a separate cleanup executor can close a socket even while a command is blocked. Socket
closing unblocks pending I/O. GUI close retires the session, stops timers, cancels queued
commands and shuts down executors. Worker/reader threads are daemon threads.

A generation is advanced before retiring a connection. Queued callbacks, timeout actions
and queued commands check that generation. Each new connection uses its own manager;
the manager itself now binds reads/writes/loss handling to a particular socket session.
Blocking connect/write does not hold its lifecycle monitor, and permanent close prevents
cancelled queued work from reopening the connection. The earlier oversized-frame-close
fix is preserved.

ACK and history operations are serialized because ERROR has no receiver request ID.
Acknowledge immediately sets PENDING; only matching ACK_RESULT success confirms it.
A negative result or server error shows failure and allows retry when connected. An
8-second registration/operation timeout closes the session; an ACK timeout explicitly
states the outcome is unknown and requires reconnect before safe retry. The server's
unique (alertId, clientId) key protects retries. Receiving/displaying/history never ACKs.

Identity saving is now atomic where supported and failures propagate to the workflow.
A malformed identity file is preserved and produces a recoverable error instead of being
silently overwritten with another UUID. Use a valid retained file or explicitly select a
new path. Preferred-name changes retain UUID. Existing corruption test was updated to
assert preservation, and a new failed-write regression checks that existing data stays intact.

## Actual automated test results

Final command: **mvn -B clean verify**. **BUILD SUCCESS**, **288 tests**, zero failures,
errors or skips. Final full run completed **2026-11-11 17:18:10 +05:30**, **37.550 s**.

| Module | Previous review | Current tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| common | 61 | 61 | 0 | 0 | 0 |
| server | 160 | 160 | 0 | 0 | 0 |
| admin | 47 | 47 | 0 | 0 | 0 |
| client | 11 | 20 | 0 | 0 | 0 |
| Total | 279 | 288 | 0 | 0 | 0 |

Client suites: ClientMainTest 1, ConsoleClientIntegrationTest 3, ReceiverWorkflowTest 7,
AlertHistoryManagerTest 4, ClientIdentityStorageTest 5. New workflow tests use scripted
loopback peers to deterministically delay/fail replies and verify the actual production
controller. They prove pending ACK until reply, negative ACK retry, no duplicate request,
history/live dedup and preserved ACK state, bounded fallback success/failure, ambiguous
ACK timeout, stale queued callback rejection, duplicate connect prevention, close while
registration stalls, unavailable server recovery and permanent network close. The new
headless test launches actual ClientMain in a child JVM.

These controlled-peer tests are not the sole integration evidence. See the real-server
and GUI checks below. Raw test reports remain in each module's target/surefire-reports.
An initial compile found an ambiguous ProtocolException wildcard import; it was corrected
before the successful full runs. No final failures or skipped tests are concealed.

## Actual-server application workflow

Commands (from repository root, after Maven verification):

```powershell
& .\scripts\Test-Integration.ps1 -ReceiverWorkflow
& .\scripts\Test-Integration.ps1
& .\scripts\Prepare-Demo.ps1
& .\scripts\Test-Integration.ps1 -ReceiverWorkflow -Packaged
```

All three integration runs passed. The new ReceiverWorkflowHarness, located under
client/scripts, uses **the same ReceiverController that backs ReceiverFrame**, not a
replacement history/ACK implementation. It starts actual ServerMain in a child JVM
with a unique SQLite file, dynamically chosen loopback port and disposable credentials.
Two independent receiver controllers load separate identity files. Actual AdminConnection,
AdminAuthService and AlertService authenticate, create alerts, query and receive events.

Checks passed against both build classes and prepared runtime jars:

- Both receiver registrations, automatic history, two distinct UUIDs and live roster.
- Same alert content/ID/time delivered to both; explicit harness actions send ACKs;
  both ACK_RESULTs confirm and admin receives two distinct live ACK_STATUS updates.
- Confirmed duplicate application submission rejected; stored ACK count remains two.
- Repeated history refresh merges by ID and preserves confirmed state.
- Disconnect A, send an alert, B still receives; reconnect A with retained UUID and
  automatically recover exactly one missed alert without duplicate display.
- Seven large valid emoji messages force the **actual server** to reject oversized
  HISTORY frames. Application retries limits 100/50/25/12/6/3; smaller response succeeds
  while all nine already displayed alerts remain available.
- Normal server JVM exit signals connection loss to both application controllers;
  a second ServerMain reopens the same database. Both reconnect, cached display remains
  deduplicated, and a fresh admin query recovers the two persisted ACKs.
- Close controllers/server and rebind the listener port, proving resource release.

The earlier full networking harness also passed, preserving invalid/valid login,
unauthorized fresh connection, duplicate identity rejection, server duplicate-ACK
idempotence/timestamps, console ClientMain entry-point delivery, offline admin ACK
recovery, thirteen-alert restart persistence and shutdown checks.

That earlier harness also measured 23 local receiver deliveries using System.nanoTime:
minimum 6.115 ms, median 9.550 ms, p95/max 34.382 ms, mean 11.206 ms. Those measurements
cover networking callbacks, not Swing painting or human response, and are not real-time
deadlines or LAN estimates.

## Actual GUI checks

Used the computer-use skill to launch and operate actual AdminMain and two ClientMain
GUI instances against a disposable actual server. The fixture contains **no automatic
ACKs** and requires Connect/button actions. The windows share one JVM in the fixture,
with independent controllers, sockets and identity files; this is not separate-laptop
or separate-receiver-process isolation evidence.

Observed and checked through real controls:

1. Connect on A and B: CONNECTED, distinct displayed UUIDs, automatic empty history.
2. Real admin GUI login and BROADCAST ALERT: server accepted a FIRE/CRITICAL test alert.
   Both receiver GUIs displayed its full title/message/category/priority/UTC time/ID.
   Red critical banner and bold/tinted rows were visible.
3. Admin Alert Monitoring -> Load Alerts selected that alert. Clicking A's Acknowledge
   yielded Acknowledged/confirmation and disabled its button; admin showed **1** live
   ACK row. Clicking B's independent button confirmed B and admin showed **2** live rows.
   These were **automated clicks on real GUI controls**, not human clicks or automatic
   fixture ACKs. The pending transition is deterministic in tests; loopback reply was too
   fast to retain a visual pending screenshot, so no visual timing claim is made.
4. Disconnect A through its button. Send a second alert through admin. B displayed it
   while A remained offline. Refresh History on B reported 0 new alerts and retained
   exactly two rows. Reconnect A retained its UUID and reported 1 new history alert,
   yielding exactly two rows with the first alert's confirmed state retained.
5. Disconnect A, set unused local port 65530, Reconnect: CONNECTION_LOST/error displayed,
   controls remained usable. Restore 5050 and reconnect: successful history recovery
   without duplicates. Final code expands the short connection-failure message with
   host/port/server troubleshooting guidance; no other GUI behavior changed afterward.
6. Close B with window X: its window disappeared and admin live roster showed only A.
   Stop the fixture: all remaining windows closed, fixture/server exited normally (0).

Not visually tested: server-side negative ACK/result-error rendering, delayed ACK pending
state, long stalled/blackholed network/DNS behavior, oversized-history retry notice and
server restart while the GUI remains visible. Corresponding controller behaviors were
covered by controlled-peer/real-server checks as described; they should still be rehearsed
on the actual demo laptops. No human ACK or separate-laptop delivery is claimed.

## Exact launch commands and beginner demonstration

See **[client/README.md](../client/README.md)** for complete copy/paste PowerShell commands
covering JDK/Maven build, all runtime dependencies, safe disposable server database and
password prompt, admin GUI, two distinct receiver identity paths, restart, and LAN setup.
From repository root after Prepare-Demo, application entry points are:

```powershell
# Server: set EMERGENCY_ADMIN_USERNAME, EMERGENCY_ADMIN_PASSWORD, EMERGENCY_DB_PATH,
# and optionally EMERGENCY_PORT first, as shown in client/README.md.
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.server.ServerMain
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.admin.AdminMain
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.client.ClientMain --host 127.0.0.1 --port 5050 --name 'Receiver A' --id-file '.\runtime\data\receiver-a.json'
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.client.ClientMain --host 127.0.0.1 --port 5050 --name 'Receiver B' --id-file '.\runtime\data\receiver-b.json'
```

Run each Java command in a separate PowerShell. In the admin login enter the server
host/port and configured credentials. In each receiver click Connect. In admin refresh
the roster, create a short alert, Load Alerts in monitoring, select the alert, acknowledge
independently from A and B, observe 1 -> 2 live status, and query history/ACKs. Disconnect
A, send another alert, confirm B delivery, reconnect A and confirm missed-history recovery.

For LAN, discover the server's active Wi-Fi/Ethernet IPv4 with Get-NetIPConfiguration;
enter it instead of 127.0.0.1 in admin and both receivers. Match EMERGENCY_PORT (default
5050) everywhere. Copy the prepared runtime/dist intact, but let each laptop create its own
identity file. Use Test-NetConnection to check reachability. If required, manually allow
the JDK's java.exe or demo TCP port only for the trusted Private network, preferably
local subnet scoped. Do not disable the firewall. These LAN steps remain unexecuted.

## Remaining limits

- Recent history is bounded by count and frame size; fallback can omit older missed
  alerts. No pagination or guaranteed delivery is promised by the shared protocol.
- Receiver history and local ACK status are in memory until window exit. Identity alone
  persists locally. A fresh receiver process cannot query its own saved ACK state with
  this protocol, so historical alerts initially appear unacknowledged. Re-ACK is safe;
  admin GET_ACKS/SQLite remains authoritative. Same-window reconnect preserves state.
- Explicit console mode is receive-only diagnostics, not the complete GUI workflow.
- No automatic reconnect, heartbeat, read-idle disconnect or emergency-delivery guarantee.
  Socket queues/server admission remain bounded as before. Plaintext TCP credentials
  remain intended only for the isolated classroom prototype.
- Admin GUI still uses a fixed 50-record history request without adaptive retry; receiver
  completion does not alter admin behavior. Its existing query correlation/concurrency
  limitations remain as recorded in the initial review.
- No multi-laptop Wi-Fi/firewall rehearsal, prolonged network soak or actual human ACK
  session has occurred. Complete those before claiming the LAN acceptance demonstration.

## Changed files and evidence

Receiver completion changed/added:

- client/ClientMain.java (under src/main/java package): GUI entry/options/headless policy.
- client/.../ui/ReceiverFrame.java: Swing controls, display/highlighting and close cleanup.
- client/.../service/ReceiverController.java: production ACK/history/reconnect workflow.
- client/.../net/ClientNetworkManager.java: socket ownership, cancellable lifecycle,
  stale session guards and retained framing-error cleanup.
- client/.../storage/ClientIdentityStorage.java: safe retained identity persistence/error handling.
- client/.../console/ConsoleClient.java: shared options, safe identity errors, alert ID display.
- client/src/test/.../ReceiverWorkflowTest.java, ClientMainTest.java, and updated
  storage/ClientIdentityStorageTest.java. Prior integration regression tests remain enabled.
- client/scripts/ReceiverWorkflowHarness.java: actual-server workflow and real-GUI fixture.
- client/README.md; root README; this report, archived initial report and receiver evidence.
- scripts/Test-Integration.ps1: compile/select new harness/fixture; scripts/IntegrationHarness.java:
  explicit --console entry smoke test. Existing Prepare-Demo packaging is reused.

Retained logs under runtime/logs/: receiver-build-final.log, receiver-workflow-final.log,
receiver-previous-harness.log, receiver-packaged-final.log and receiver-gui.log. Raw isolated
server logs/databases/identity files remain under runtime/evidence. Credential-free
summary and suite counts: [RECEIVER_COMPLETION_EVIDENCE.txt](RECEIVER_COMPLETION_EVIDENCE.txt).

All changes remain uncommitted on integration-review. The complete application workflow
is implemented and locally verified; the remaining readiness gate is the team's actual
multi-laptop LAN/human-operation rehearsal and the limits listed above.
