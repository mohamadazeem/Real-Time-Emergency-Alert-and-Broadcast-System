# Receiver application

Generated paths now use runtime/data, runtime/dist, runtime/logs and runtime/evidence.
The root [README](../README.md) includes launch scripts that work from any directory;
the direct Java commands below require the repository root as their working directory.
GUI checks documented here predate the path cleanup; GUI behavior was not rerun during it.

Java 17 Swing receiver for the existing emergency-alert server. `ClientMain` opens the
desktop window by default. Use `--console` for the original **receive-only diagnostics**.
Without a graphical desktop, default startup prints an explanation and returns without
connecting or creating an identity; it does not silently choose console mode.

## What the receiver does

- Configurable host, port and display name, with a retained UUID identity file.
- Connect/Disconnect/Reconnect controls and connection/error status.
- Alert/history table and full title, message, category, priority, UTC timestamp and ID.
- Critical alerts have a red banner, bold rows and a tinted background.
- Acknowledge sends only on an explicit button action. Status becomes pending immediately,
  then acknowledged only after successful ACK_RESULT. Confirmed alerts cannot be submitted
  twice through the button. Failure permits retry when connected.
- Automatic recent history after registration/reconnect, plus Refresh History. Live and
  history entries merge by alert ID; confirmed ACK state survives refresh/reconnect while
  the window remains open.
- Oversized history retries with limits 100, 50, 25, 12, 6, 3, 1, stopping after success or
  an error at 1. Smaller history never removes alerts already displayed.
- Connection attempts, identity I/O, socket writes, ACK/history requests and cleanup run
  off the Swing event-dispatch thread. GUI state and callbacks are applied on the EDT.
  Session generations reject old callbacks; only one ACK/history request is in flight.
- Closing a window cancels its timers/commands and closes its socket, without shutting
  down other receivers. Registration/reply timeout is 8 seconds; TCP connect timeout is
  5 seconds. On ambiguous reply timeout, reconnect before retrying, to avoid misreading
  a late response as confirmation of a new request.

Identity load/write failures stop the connection and show an error. Invalid files are
preserved rather than overwritten with a new UUID. Restore a valid file or choose a new
`--id-file` deliberately. Each receiver instance must have its own file.

## Build from Windows PowerShell

Run all commands from the **repository root**, the directory containing the parent pom.xml.
JAVA_HOME must already point to your installed **Temurin JDK 17**.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -version
mvn -B clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
& .\scripts\Prepare-Demo.ps1
```

Prepare-Demo copies the four module jars and their resolved production dependencies
into `runtime\dist\lib`: Gson 2.13.2, sqlite-jdbc 3.51.3.0, error_prone_annotations 2.41.0.
The receiver itself needs client/common/Gson; using the full prepared directory also
supports server and admin. Module jars do not bundle dependencies and have no executable
jar manifest. Use the **quoted classpath wildcard**, not `java -jar`.

For a new dependency version, prepare a fresh distribution directory to avoid duplicate
versions. Copy `runtime/dist` intact to other laptops with JDK 17, or build the reviewed
source tree there. These changes remain uncommitted on integration-review; GitHub main
alone does not contain them.

## Start the complete system on one computer

Open four PowerShell windows at the repository root: server, admin, receiver A, receiver B.

**1. Server window.** Generate a fresh demo database filename; retain the printed path
for the restart test. Enter a disposable password privately and use it in the admin GUI.

```powershell
$env:EMERGENCY_PORT = '5050'
$env:EMERGENCY_ADMIN_USERNAME = 'classroom-admin'
$env:EMERGENCY_DB_PATH = Join-Path (Get-Location) ('runtime\data\classroom-' + [guid]::NewGuid().ToString() + '.db')
Write-Output "Demo database: $env:EMERGENCY_DB_PATH"
$demoSecret = Read-Host 'Disposable classroom password' -AsSecureString
$env:EMERGENCY_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $demoSecret).Password
try {
    & "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.server.ServerMain
} finally {
    Remove-Item Env:EMERGENCY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable demoSecret -ErrorAction SilentlyContinue
}
```

**2. Admin window.** In the login form use host 127.0.0.1, port 5050, username
classroom-admin and the password you entered above.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.admin.AdminMain
```

**3. Receiver A window.** The GUI opens; click Connect.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.client.ClientMain --host 127.0.0.1 --port 5050 --name 'Receiver A' --id-file '.\runtime\data\receiver-a.json'
```

**4. Receiver B window.** Click Connect, using a different identity file.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.client.ClientMain --host 127.0.0.1 --port 5050 --name 'Receiver B' --id-file '.\runtime\data\receiver-b.json'
```

Supported options: `--host`, `--port`, `--name`, `--id-file`, `--console`. The original
positional host/port/name/identity arguments also work. GUI options populate the form;
connection begins when you click Connect. Identity file location is fixed for that
window; change it by launching another instance. Display name can be edited while
disconnected without changing the retained UUID.

Receive-only console diagnostics:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.client.ClientMain --console --host 127.0.0.1 --port 5050 --name 'Console diagnostic' --id-file '.\runtime\data\console-receiver.json'
```

## Beginner-friendly demonstration

1. Start the server, admin, and two receiver GUIs with the commands above. Connect each
   receiver; both should show CONNECTED, different Client IDs and History loaded.
2. In admin Connected Clients, click Refresh Client List. Expect two receiver names.
3. In Create Alert, enter a short title/message, category FIRE, priority CRITICAL, then
   BROADCAST ALERT. Expect server acceptance with an ID. Both receivers should display
   identical content and the same ID/timestamp, with critical highlighting.
4. In admin Alert Monitoring, click Load Alerts and select the new alert. Its table
   should initially have no ACK rows. On receiver A select the alert and click
   Acknowledge: it must show pending until server confirmation, then Acknowledged.
   Admin should show one ACK. Do the same on B: admin should show two. Confirmed buttons
   are disabled; acceptance of an alert is distinct from an acknowledgement.
5. On each receiver click Refresh History. Expect the same single alert, with its
   confirmed ACK state retained. Admin Alert History -> Query Server History shows the
   stored alert; Alert Monitoring -> Query ACKs recovers the stored ACK rows.
6. Click Disconnect on A. Send another alert from admin. B must receive it normally.
   Click Reconnect on A: its Client ID stays the same and recent history adds the
   missed alert once. Refresh again: no duplicate rows. Select the new alert to read
   its details and acknowledge it independently.
7. Optional error check: disconnect A, change its port to an unused local port and click
   Reconnect. Expect an error and editable connection controls. Restore the correct port
   and reconnect. Invalid input must not freeze or close the window.
8. Close B with the window X. Admin should show only A connected. Close A/admin when done.
   Stop the server with Ctrl+C. Keep the demo database for persistence verification.

To restart the server in the **same server PowerShell window**, keep its existing
EMERGENCY_DB_PATH/port/username. Prompt for the same disposable password and start Java
again (do not run the fresh-database assignment):

```powershell
$demoSecret = Read-Host 'Same disposable classroom password' -AsSecureString
$env:EMERGENCY_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $demoSecret).Password
try {
    & "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.server.ServerMain
} finally {
    Remove-Item Env:EMERGENCY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable demoSecret -ErrorAction SilentlyContinue
}
```

Reconnect/relaunch admin and receivers; query history and stored ACKs in the admin.

## Separate-laptop LAN demonstration (not tested here)

On the server laptop, run `Get-NetIPConfiguration` or:

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' } |
    Format-Table InterfaceAlias,IPAddress
```

Choose the active Wi-Fi/Ethernet adapter's LAN IPv4. Use the same trusted LAN for all
laptops. The server binds all interfaces at EMERGENCY_PORT (default 5050). The admin
login host and both receivers' host fields must use **that LAN address**, with the same
port. On receiver laptop A or B, replace the localhost command with:

```powershell
$serverHost = Read-Host 'Server laptop LAN IPv4'
Test-NetConnection -ComputerName $serverHost -Port 5050
& "$env:JAVA_HOME\bin\java.exe" -cp '.\runtime\dist\lib\*' edu.ec5205.alert.client.ClientMain --host $serverHost --port 5050 --name 'Receiver A' --id-file '.\runtime\data\receiver-a.json'
```

On B use Receiver B and receiver-b.json, created on B. Do not copy A's identity file.
If changing the server port, change EMERGENCY_PORT, Test-NetConnection, both receiver
ports and the admin form. Java arguments do not override admin GUI connection fields.

If required, manually allow the configured JDK java.exe or inbound TCP demo port in
Windows Firewall for the **Private** profile on the trusted demo network, ideally only
from the local subnet. Do not disable the firewall. Check Wi-Fi client/AP isolation,
VPN/virtual adapter addresses and matching ports if remote connections fail. No firewall
settings were changed by this work.

## Verification and limits

Final `mvn -B clean verify`: **288 tests passed**, zero failures/errors/skips (common 61,
server 160, admin 47, client 20). Actual-server controller integration and the prepared
runtime-jar workflow both passed. Real GUI automation verified Connect on A/B, critical
alert display, each Acknowledge button and admin live counts 1 -> 2, history refresh,
disconnect isolation, same-UUID reconnect/missed-history recovery, failed-port recovery
and window-close cleanup. These GUI button actions were automated, not human actions.

The final Maven results, real-server integration and actual GUI observations are in
[the integration review](../docs/INTEGRATION_REVIEW.md). Repeat checks:

```powershell
& .\scripts\Test-Integration.ps1 -ReceiverWorkflow
& .\scripts\Test-Integration.ps1
```

The first uses the **production receiver controller**, actual ServerMain and actual admin
network/services; it does not build its own history/ACK workflow. The second preserves
the earlier full networking/restart harness and explicit console-entry smoke test.
Harness ACKs are programmatic; the GUI controls were also clicked through automation
against the real admin/server. No claim of human or separate-laptop LAN testing is made.

Recent history is bounded to 100 and may shrink further to fit the frame limit. This
protocol has no pagination, so it cannot promise every missed alert. Displayed history
and local ACK state live in memory until window exit. Only the identity is stored on
the receiver; server alerts/ACKs persist in SQLite. On a fresh receiver process, history
has no per-receiver ACK state, so an old alert can appear unacknowledged; clicking again
is safe because the server stores one ACK per client/alert. Admin GET_ACKS is authoritative.

No automatic ACK, automatic reconnect, sound notification, guaranteed emergency delivery,
or internet deployment was added. Plain TCP credentials are suitable only for the isolated
classroom prototype. Long network stalls and different desktop scaling should still be
rehearsed manually on the actual demonstration laptops.
