# ADR-0048: point B leaves the old webapp and buildomatic alone when the upgrade went to a new Tomcat

Status: accepted · Date: 2026-10-28 · Spec: §10.1 · Field runs `r-20261027-211006-91a5` and `r-20261027-230841-272f` (9.0.0 PRO to 10.0.0, Windows, `--tomcat-dir`)

## Context

When `run-vendor-upgrade` fails, its compensation restores rollback point B. Until now that meant the same four things in every layout: the webapp and the installed buildomatic, each re-extracted from its archive with the current tree moved aside first, then the configuration and keystore snapshots.

With `--tomcat-dir` (ADR-0026), `write-master-properties` points `appServerDir` at the new Tomcat. `copy-webapp-to-tomcat` copies the webapp there, and the vendor script runs from the target package's buildomatic against that Tomcat. The old webapp and the installed buildomatic are therefore exactly as point B archived them. Restoring them changes nothing, but it still renames two directory trees that the old server or its tools may hold open.

On the field box both side-by-side runs failed this way. Run 1 could not move the old webapp aside, and run 2 could move neither the webapp nor the buildomatic. Both ended with exit 4 ("rollback incomplete") even though there was nothing to roll back in those trees. The renames failed as a whole, so the trees stayed in place and JRS 9 started again from them. The message gave only `<source> -> <target>`: on Windows, a refused rename (`AccessDeniedException`) carries no reason, and the compensation passed on `getMessage()` without the exception type.

## Decision

- With `--tomcat-dir`, the compensation of `run-vendor-upgrade` does not restore the webapp or the installed buildomatic. It logs that it leaves both as they are. It still restores the configuration and keystore snapshots, because the keystore is shared and the config snapshot covers files the run rewrote. It still removes its markers. `copy-webapp-to-tomcat`'s own compensation removes the copy in the new Tomcat, as before.
- In-place upgrades (no `--tomcat-dir`) are unchanged.
- Every point-B tree restore failure (the compensation and the `restore-*` steps of the rollback plan) names the exception type (`Failures.describe`), so an access-denied rename says so.

## Consequences

- A failed side-by-side vendor run whose snapshots restore cleanly now rolls back with exit 3 instead of exit 4.
- `upgrade rollback <runId> --to-point B` still restores all four. The operator chooses to run it, and it can follow later steps of a finished run. Whether it should also skip the old trees after a side-by-side run is left open.
- The vendor run can leave files in the new Tomcat outside `webapps/<name>`. They were not part of point B before this ADR and are not now. The new Tomcat is the operator's to discard.
- If a future vendor package writes into the installed buildomatic or into the old webapp in side-by-side mode, this ADR's premise no longer holds and the skip has to be revisited.
