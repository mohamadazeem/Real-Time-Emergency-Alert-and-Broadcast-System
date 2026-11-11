# Runtime artifact cleanup

Completed 2026-11-11, Asia/Colombo, on the existing uncommitted `integration-review`
branch based on `fb7c4fafc1a882a099ad8cf4fb111615979b4075`. Existing receiver/admin/server
fixes, tests and authored documentation were preserved. No commits, pushes, resets,
merges, package moves, protocol changes, UI changes or new dependencies.

## Classification and migration

Read ancestor/repository instructions (no AGENTS.md found), Git status/branches/tracked
and untracked inventory, four Maven modules, current scripts and path references first.
Checked process command lines before inspection and again immediately before moving:
no project Java applications were running, so no close-app handoff was needed.

| Old path / classification | New path |
| --- | --- |
| data/ retained database, sidecars if present, identities | runtime/data/ with original filenames |
| Root database/identity files, if present | runtime/data/ |
| demo-dist/ generated application/dependency jars | runtime/dist/ |
| 15 root build/integration/receiver/GUI logs | runtime/logs/ |
| integration-evidence/ generated databases, identities, classes, CSV and metadata | runtime/evidence/ |
| integration-evidence/ nested .log files | runtime/logs/evidence/ with original subdirectories |
| New harness server/client logs | runtime/logs/<unique-run-id>/ |
| Authored docs/ evidence summaries and reviews | Kept in docs/ |
| Reusable Java harness/verifier source | Kept in scripts/, client/scripts/, server/scripts/ |
| Standard Maven build products | Kept in each module's target/ |

Migrate-Runtime.ps1 classified and preflighted all destinations before moving anything.
It refused collisions and reparse points, used native literal-path moves inside checked
workspace paths, and removed only verified empty legacy directories (no recursive delete).
It rechecked process state immediately before moves. **103 original files** were moved;
every SHA-256 matched immediately after migration and all recorded identity UUIDs matched.

Retained user data consisted of three files: classroom-8a85be9b-96ae-447b-bbde-056d84c18f31.db,
receiver-a.json and receiver-b.json. Before/after logical database checks ran against
**disposable copies**, including SQLite sidecars if present, opened in read-only mode.
The original database was not used for integration tests or opened by the verifier.

| Retained table | Before | After |
| --- | ---: | ---: |
| alerts | 1 | 1 |
| acknowledgements | 2 | 2 |
| clients | 2 | 2 |

Per-table SHA-256 fingerprints of all ordered column values were identical. After all
verification, retained database/identity file hashes and UUIDs still matched the original
manifest. All 103 original file contents remain preserved; refreshed distribution jars
have their original versions in runtime/dist/archive. Module target directories were
rebuilt normally and are not retained-data migration targets.

Detailed file mapping, original hashes/UUIDs, before/after record fingerprints and final
preserved locations are in the ignored directory:
`runtime/evidence/migration-e1d7ebb58cce4fc790b5833835fb64e5/`.

## Future output and path handling

- scripts/Project-Paths.ps1 provides one repository-root resolver. Relative script
  arguments are resolved from that root, not the caller's directory; the caller's
  location is restored. Get-ProjectJava validates the configured JDK 17 without
  replacing JAVA_HOME or the Maven cache.
- scripts/Build.ps1 runs `mvn -B clean verify` and creates a unique runtime/logs build log.
  The first wrapper attempt exposed Windows PowerShell's handling of normal native
  stderr as terminating errors. The corrected wrapper preserves stderr in the log and
  uses the real exit code. The final full run passed.
- scripts/Prepare-Demo.ps1 stages seven jars under runtime/dist, checks that applications
  are stopped, archives the previous lib directory without collisions and publishes
  runtime/dist/lib. The original distribution is preserved under
  runtime/dist/archive/20261111-191424-cbb83bcfd3b24dee9c3befc836a377b3/lib/.
- scripts/Test-Integration.ps1 uses unique runtime/logs outputs and unique harness class
  directories under runtime/evidence, with isolated per-run databases/identities. Both
  harnesses and GUI fixtures use the new paths. Latest active fixture pointers are now
  latest-gui-directory.txt / latest-receiver-gui-directory.txt under the evidence root;
  moved historical pointer text is kept unchanged as historical metadata.
- scripts/Start-App.ps1 launches the existing ServerMain/AdminMain/ClientMain with the
  runtime/dist/lib wildcard and repository working directory. Server/GUI logs are
  unique runtime/logs files. Explicit console mode stays interactive and is not captured.
  -Check validates paths/configuration without starting an application.
- Java defaults are runtime/data/emergency-alerts.db and
  runtime/data/client-identity.json. Existing tests now check those defaults and retain
  explicit configured-path assertions. Application networking/UI behavior is unchanged.
- Legacy server verification scripts normalize database arguments from the repository
  root and put temporary frame artifacts under runtime/evidence/legacy-checks. They
  intentionally create test records, so run them only against a disposable test server.
- `/runtime/` is ignored. Authored documentation, all test/harness sources and scripts
  remain visible to Git. Obsolete broad directory ignores for data/, demo-dist/ and
  integration-evidence/ were removed so accidental recreation is visible.

## Explicit paths and external configuration

No EMERGENCY_DB_PATH was configured in Process, User or Machine environment during
cleanup. No external database or identity locations were moved. Existing absolute
EMERGENCY_DB_PATH, -DatabasePath and -IdentityFile values continue to be honored. The
launcher normalizes relative paths against the repository; raw Java continues to honor
explicit relative paths from its working directory, as before. JAVA_HOME and the normal
Maven cache are unchanged.

Saved IDE launch settings, shortcuts, other terminals and external environment values
cannot be migrated automatically. If they reference a moved file under data/, update
them to its exact runtime/data filename. Start-App detects a missing old local path
whose migrated counterpart exists and refuses to create a replacement; it explains the
new path. Checks verified this for both retained identities and the retained database.
An explicit external path was also validated without modifying the environment or
creating a file there. Absolute paths outside the repository remain outside it by choice.

## Actual verification

All entry scripts below were invoked from the **parent workspace directory**, rather
than the repository root. They resolved outputs to the repository and restored the
caller directory. No runtime/ directory was created beside the repository.

| Check | Result |
| --- | --- |
| PowerShell parser, all current scripts | Passed |
| Build.ps1: Temurin 17.0.21+9, Maven 3.9.16, normal cache; mvn -B clean verify | Passed: 288 tests, 0 failures/errors/skips; 28.786 s; 2026-11-11 19:13:18 +05:30 |
| Module counts | common 61, server 160, admin 47, client 20 |
| Prepare-Demo.ps1 | Passed; seven jars; old distribution archived |
| Test-Integration.ps1 | Passed, real server/admin/client networking and entry points |
| Test-Integration.ps1 -ReceiverWorkflow | Passed, two real receiver controllers against actual server/admin |
| Test-Integration.ps1 -Packaged | Passed against runtime/dist/lib, including actual ServerMain, admin console login/exit, receiver console delivery, headless receiver GUI-entry policy |
| Test-Integration.ps1 -ReceiverWorkflow -Packaged | Passed, retained-identity reconnect, ACK/status, history/dedup/fallback, normal server restart/persistence and shutdown |
| Start-App -Check for Server/Admin/Receiver from parent directory | Passed |
| Actual Start-App receiver console launch from parent directory against real packaged server/admin | Passed; registered, displayed an alert, and exited normally on server shutdown |
| Stale moved identity/database guard and explicit external-path preservation | Passed; no files created |
| All original files / retained UUIDs and logical records | Preserved |
| Generated files left beside pom.xml | 0 |
| Old data/, demo-dist/, integration-evidence/ recreated by tests | 0 |
| Remaining test application processes / temporary GUI secret files | 0 / 0 |

Current logs are under runtime/logs, including:

- build-20261111-191248-db85cc2da54e4a4080cbf0384b692e68.log
- integration-20261111-191424-08c4e8aa8c8b4a15908d1549576f52c7.log
- receiver-workflow-20261111-191429-a3ff3d2ecf194d688142922c3c95c2b0.log
- integration-packaged-20261111-191434-27e81d30ae0643ffa75fbf9b214e86f3.log
- receiver-workflow-packaged-20261111-191439-54a8f4bf984e461fbca912e1e57d87b1.log
- integration-packaged-20261111-192222-edd8ff37a6ba4d7cad20fdc5689e915e.log (also exercises the receiver launch script)

Harness ACKs were programmatic. **GUI painting/controls were not exercised during this
path-only cleanup**; earlier GUI observations remain historical. No actual human ACK,
separate-laptop LAN, firewall or network-stall GUI test is claimed. No unrelated user
processes were stopped; verification servers/clients exited through harness cleanup.

## Documentation and remaining limits

README.md now provides the final tree and exact Build/Prepare/Start-App commands for
all four windows, retained-file use, logs and LAN configuration. Client/server guides
and the active integration review use runtime paths. The initial archived review and
raw evidence transcripts retain their original path strings explicitly as historical
records; old names also remain intentionally in migration mappings and stale-path guards.
These are not current launch instructions. Searches found no stale old-path references
in active scripts/defaults outside those explicit migration/guard cases.

To repeat: use the root README's commands. The complete workflow remains locally
verified, while GUI behavior after this cleanup and a separate-laptop LAN/human-operation
demonstration still need rehearsal. Changes are uncommitted and unpushed on integration-review.
