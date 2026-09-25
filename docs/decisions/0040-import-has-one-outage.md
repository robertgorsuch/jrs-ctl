# ADR-0040: a vendor import has one outage, and the snapshot can be left out

Status: accepted · Date: 2026-09-25 · Spec: §9.2, §9.4, §9.5 · Amends ADR-0021 §4 · Field test 3

## Context

Field test 3 restored an archive through the vendor (buildomatic) strategy and counted the service going down twice for one import. The plan was:

- `precheck`: check the keystore fingerprint, locate `js-import`;
- `backup`: announce the snapshot, record the repository listing, locate `js-export`, **stop the service**, `js-export` of the affected folders, **start the service**, wait for the server, write the sidecar;
- `import`: the snapshot-rollback anchor, the new-content anchor, **stop the service**, (keystore import), `js-import`, **start the service**, wait for the server.

The snapshot stopped the service because `snapshotRequest` used the `ExportRequest` constructor that defaults `stopService` to true. ADR-0021 §4 kept that on purpose: "the import stops right after anyway, and changing it would make an interrupted import journaled by an earlier jrsctl rebuild into a different plan". But ADR-0021's own first decision is that `js-export` does not need the web application stopped: it reads the repository database through buildomatic. The stop and start around the snapshot is a second outage (a full Tomcat restart and warm-up) that protects nothing the import's own stop does not.

The field test also asked for a way to skip the snapshot: on a large repository it costs as much time and disk as the import, and some operators already hold a backup.

## Decision

1. **The pre-import snapshot runs with the server up.** `snapshotRequest` passes `stopService = false`, so the vendor snapshot is `locate`, `js-export`, `sidecar`, and a vendor import stops the service once: stop, (keystore import), `js-import`, start, wait. The REST strategy never stopped the service and is unchanged. The plan's service warning says the snapshot is taken with the server running.
2. **Recovery compatibility.** `ImportOptions` gains `snapshotStopsService`; the stored plan arguments gain the key of the same name. A new plan stores `false`. Arguments an earlier jrsctl stored have no such key and are read as `true`, which rebuilds the old step list (stop, `js-export`, start, wait around the snapshot), so `runs recover` still matches a journaled run step for step. The fingerprint names the choice (`snapshot=live`) only when it differs from what an earlier jrsctl built, so an old plan keeps its old fingerprint.
3. **`jrsctl import --no-snapshot`** leaves out the snapshot export and its re-import on rollback. Decided, as the safer option:
   - The repository listing (`backup.pre-import-listing`) stays, and the import phase's rollback anchor becomes the new step `import.additions-rollback` (`RemoveImportAdditions`), whose compensation deletes what the failed import created under the archive's folders, exactly as the snapshot anchor does before re-importing. Deleting additions does not need a snapshot, and leaving them would make a failed import worse than it has to be.
   - `import.new-content-rollback` (ADR-0036) stays: folders that did not exist before are still removed.
   - What the import overwrote is not put back. The plan says so plainly: `NO_SNAPSHOT_WARNING` replaces the rollback sentence (spec §9.4) and the import phase's rollback line ends "what it overwrote is not put back (--no-snapshot)".
   - The override is written to the audit table (`--no-snapshot`, naming the archive and the folders), like `--force-version`.
   - The stored plan arguments gain `noSnapshot`; missing means `false`. The fingerprint gains `snapshot=none`.
   - The guided menu's restore flow asks "Take the rollback copy first?" with Enter meaning yes; only an explicit `n` passes `--no-snapshot`, and the end of input stops the flow instead of choosing.
4. **Say when REST would have needed no outage.** When the operator forced `--strategy vendor` on a server whose `IMPORT_ASYNC` probe passes, and neither `--source-keystore` (which needs `js-import --keystore`) nor an archive above the 2 GB REST limit (ADR-0033) needs the vendor tools, the plan warns that the REST route needs no outage at all. When the automatic choice fell to the vendor tools because the probe failed, REST was not possible and nothing is said.

## Why the rollback is still safe

The rollback order does not change. In the import phase the anchors (`import.snapshot-rollback` or `import.additions-rollback`, then `import.new-content-rollback`) come before `import.stop-service`. The Runner compensates in reverse order, so when `js-import` fails, `import.stop-service`'s compensation starts the service before the anchors run; their REST listing and deletions, and the snapshot's re-import (itself stop, `js-import`, start, wait inside a nested context), meet a running server. `VendorImportRunTest` proves it with a fake adapter that refuses REST calls while the fake service is stopped.

A snapshot taken with the server up can include a change a user makes while `js-export` runs, and miss one made right after a table was read. That was already true of the time between the snapshot's restart and the import's stop, which the old plan left open for as long as Tomcat took to warm up; the live snapshot shortens the window rather than opening a new one.

## Consequences

- A vendor restore costs one outage instead of two, and a Tomcat warm-up less.
- A run journaled by 2.0.1 or earlier is recovered with its own steps; `runs recover` never sees a step list it did not journal.
- `--no-snapshot` trades the ability to put overwritten resources back for time and disk. It is audited, and both the plan and the rollback line say what is given up.
- ADR-0021 §4's second bullet is superseded by decision 1; its first bullet (the import itself stops the service) and third (the upgrade's point-B export) are unchanged.
