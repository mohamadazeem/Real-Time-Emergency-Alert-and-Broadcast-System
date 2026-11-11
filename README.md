# Real-Time Emergency Alert and Broadcast System

EC5205 Operating Systems & Network Programming — three-member Java 17/Maven project.
The server, admin Swing application and receiver Swing application support broadcast,
explicit acknowledgements, SQLite history and manual reconnect. This is an educational
LAN prototype; it does not guarantee emergency delivery.

## Repository structure

```text
admin/          Admin application and tests
client/         Receiver application, tests and reusable workflow harness
common/         Shared protocol, codec and tests
server/         TCP server, SQLite storage, tests and legacy verification scripts
docs/           Authored plans, protocol, reviews and evidence summaries
scripts/        Build, distribution, launch, integration and migration tools
.github/        Collaboration templates
pom.xml         Parent Maven reactor (four modules)
README.md
CONTRIBUTING.md
.gitignore
runtime/        Generated files only; ignored by Git
  data/         Demo databases, SQLite sidecars and retained receiver identities
  dist/         Prepared jars in lib/; previous distributions in archive/
  logs/         Build, integration and application logs, including per-run server logs
  evidence/     Isolated test databases/identities, harness classes and migration audits
```

Standard Maven `target/` directories remain inside their modules. Java packages and
Maven modules have not moved. Authored reviews and reusable Java test sources stay in
source/docs/scripts locations, even if their names contain “integration” or “evidence”.

## Build and prepare (Windows PowerShell)

Set JAVA_HOME to the installed **Temurin JDK 17** and check the versions. Use the normal
Maven cache; no replacement Java runtime/cache is selected by these scripts.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -version
mvn -version
& .\scripts\Build.ps1
& .\scripts\Prepare-Demo.ps1
```

Run the examples from the directory containing the parent pom.xml. Scripts also work
when invoked by absolute path from another directory: every script resolves its paths
from its own repository location and restores the caller's working directory. For example:

```powershell
& 'C:\Users\ASUS\Desktop\Network-project\Real-Time-Emergency-Alert-and-Broadcast-System\scripts\Build.ps1'
```

Build.ps1 runs exactly `mvn -B clean verify` and writes a uniquely named build log under
runtime/logs. Plain Maven also works from the root; if redirecting it manually, place
that log under runtime/logs, not beside pom.xml. Prepare-Demo creates runtime/dist/lib
with four application jars plus Gson 2.13.2, sqlite-jdbc 3.51.3.0 and
error_prone_annotations 2.41.0. It preserves an existing lib directory in a unique
runtime/dist/archive directory before publishing refreshed jars. Close project apps
before preparing/replacing the distribution. No dependencies were added for cleanup.

## Start the complete system

Use four PowerShell windows, one per command/application. The launch script prints its
unique log path under runtime/logs, then waits until the application closes. Console mode
remains interactive and is not captured, to keep password input out of logs.

**Server window:** enter a disposable classroom password privately. The example chooses
a demo database explicitly. To continue an existing retained demonstration, replace that
filename with its exact moved filename listed by `Get-ChildItem .\runtime\data -Filter '*.db'`.
Use the same database path on every restart; choosing a new filename creates a new database.

```powershell
$env:EMERGENCY_ADMIN_USERNAME = 'classroom-admin'
$demoSecret = Read-Host 'Disposable classroom password' -AsSecureString
$env:EMERGENCY_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $demoSecret).Password
try {
    & .\scripts\Start-App.ps1 -Application Server -Port 5050 -DatabasePath 'runtime/data/classroom-demo.db'
} finally {
    Remove-Item Env:EMERGENCY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable demoSecret -ErrorAction SilentlyContinue
}
```

**Admin window:** in the login form enter host 127.0.0.1, port 5050, username
classroom-admin and the password entered on the server.

```powershell
& .\scripts\Start-App.ps1 -Application Admin
```

**Receiver A window:** click Connect in the GUI.

```powershell
& .\scripts\Start-App.ps1 -Application Receiver -ServerHost 127.0.0.1 -Port 5050 -Name 'Receiver A' -IdentityFile 'runtime/data/receiver-a.json'
```

**Receiver B window:** use its independent identity file, then click Connect.

```powershell
& .\scripts\Start-App.ps1 -Application Receiver -ServerHost 127.0.0.1 -Port 5050 -Name 'Receiver B' -IdentityFile 'runtime/data/receiver-b.json'
```

In admin, refresh Connected Clients, create a short FIRE/CRITICAL alert, and Load Alerts
in Alert Monitoring. Select the alert and acknowledge it independently in A and B; expect
admin status to advance 1 -> 2. Refresh receiver history without duplicate rows. Disconnect
A, send another alert, confirm B still receives it, then reconnect A with its retained UUID
and recover the missed recent history. Close receiver/admin windows and stop server with
Ctrl+C. Repeat the server command with the same database to inspect persisted history/ACKs.

For receive-only console diagnostics add `-Console` to the receiver command. Admin console
mode also uses `-Console`; it prompts for host/port and supports diagnostics. GUI startup
on a headless machine explains that a graphical desktop is required. See the full
[receiver guide](client/README.md) for workflow details, bounded history and ACK limitations.

Direct packaged Java entry points remain valid from the repository root, with classpath
`'.\runtime\dist\lib\*'`: `edu.ec5205.alert.server.ServerMain`,
`edu.ec5205.alert.admin.AdminMain`, `edu.ec5205.alert.client.ClientMain`.
Individual jars are not executable fat jars; use -cp, not java -jar. On a distribution-only
laptop, keep the runtime/dist layout and start those classes from the directory containing runtime/.

## Configuration and LAN

Default database: runtime/data/emergency-alerts.db. Default receiver identity:
runtime/data/client-identity.json. Launching two receivers requires separate -IdentityFile
paths. Never regenerate or copy a retained identity just to reconnect.

Explicit absolute EMERGENCY_DB_PATH, -DatabasePath and -IdentityFile locations are honored;
external files are not moved automatically. Relative script paths are repository-root
relative. Direct Java still interprets explicit relative paths from its working directory,
so use the repository root or absolute paths. Start-App honors an existing environment
port unless -Port is supplied. Old commands that point to a moved file under data/ are
rejected by the launcher with the replacement path, rather than silently creating a new
identity/database; update your saved command/environment explicitly.

For separate laptops, discover the server's active Wi-Fi/Ethernet IPv4 with
`Get-NetIPConfiguration`. Use that LAN IP in the admin form and receiver -ServerHost,
with the matching configured port. Check `Test-NetConnection -ComputerName <server-ip> -Port 5050`.
Copy the runtime/dist jars, but let each laptop retain its own identity file. If required,
manually allow the Java server/demo port on the trusted **Private** firewall profile,
preferably scoped to the local subnet. Never disable the firewall. **Separate-laptop LAN
was not tested during this cleanup.**

## Verification and retained files

```powershell
& .\scripts\Test-Integration.ps1
& .\scripts\Test-Integration.ps1 -ReceiverWorkflow
& .\scripts\Test-Integration.ps1 -Packaged
& .\scripts\Test-Integration.ps1 -ReceiverWorkflow -Packaged
```

Each run creates unique logs under runtime/logs and isolated evidence/temporary data under
runtime/evidence. Compiled standalone harness classes get their own directory. Tests never
use the retained database/receiver files under runtime/data. An explicitly supplied
-EvidenceDirectory is honored and resolved from the root; defaults produce no root clutter.

Cleanup validation: **288 tests passed**, zero failures/errors/skips; distribution and
actual-server checks passed using both build classes and packaged jars. GUI painting and
controls were **not rerun during this path-only cleanup**. Historical GUI observations
are in the integration review. No new human-ACK or multi-laptop delivery claim is made.

| Old location | New location |
| --- | --- |
| data/ and any root identity/database files | runtime/data/ (same filenames and UUIDs) |
| demo-dist/ | runtime/dist/ |
| Root integration-*.log, receiver-*.log and other generated logs | runtime/logs/ |
| integration-evidence/ non-log artifacts | runtime/evidence/ |
| integration-evidence/**/server/client logs | runtime/logs/evidence/**/ |
| Newly generated per-run server/client logs | runtime/logs/<unique-run-id>/ |

**103 files** were migrated without overwriting collisions; all moved hashes and UUIDs
matched. Read-only logical checks on disposable copies preserved the retained database's
**1 alert, 2 acknowledgements and 2 clients**. Authored historical reports keep original
path text as recorded, with migration notes; use current commands above for new runs.
Details and logs: [runtime cleanup report](docs/RUNTIME_CLEANUP.md),
[current integration review](docs/INTEGRATION_REVIEW.md).

## Team plan

Member 1 owns server/database/common protocol; Member 2 owns admin; Member 3 owns receiver.
Read [PROJECT_PLAN](docs/PROJECT_PLAN.md), [PROTOCOL](docs/PROTOCOL.md),
[Member 1 tasks](docs/member-1-server.md), [Member 2 tasks](docs/member-2-admin.md),
[Member 3 tasks](docs/member-3-client.md), and [CONTRIBUTING](CONTRIBUTING.md).
The agreed demonstration is admin -> server -> two separate LAN computers, independent
user acknowledgements, isolated disconnects, and history/ACK persistence after restart.
