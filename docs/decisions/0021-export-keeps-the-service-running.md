# ADR-0021: export keeps the service running unless asked to stop it

Status: accepted, 2026-09-16. Amends spec §9.2 and §9.3 (Draft 1.1). Issue #67.

## Context

Spec §9.2 said the vendor strategy stops the service before a full-server `js-export`, "per vendor guidance", and starts it again afterwards. The first field test, by a JasperReports Server support engineer, reported two problems with that:

- `export --full-server` first checks that the server is running, then proposes a plan whose first mutating step shuts it down;
- "the application falsely assumes the server needs to be shutdown in order to perform full export". `js-export` reads the repository database through buildomatic and does not need the web application stopped.

A stop costs the users of the server an outage for what is a read-only operation, and makes an operator reluctant to take backups.

The vendor guidance the spec cited concerns imports, where a running server's caches go stale and an import can race with users' changes. For an export, the only benefit of stopping is that nothing changes while the export runs.

## Decision

1. `jrsctl export` never stops the service by default, with either strategy. With the vendor strategy the plan says so in a warning: *the service keeps running while js-export reads the repository; pass --stop-service for an export taken with the service stopped*.
2. `jrsctl export --stop-service` (and the console's "Stop the service during a vendor export" option) keeps the previous plan: stop, `js-export`, start, wait for the server. The step ids are unchanged.
3. The choice is part of the stored plan arguments (`stopService`). Arguments stored by an earlier jrsctl have no such key and are read as `true`, because the plan they describe stopped the service. `runs recover` therefore rebuilds the plan that was journaled. The plan fingerprint names the choice only when the service is kept running, so a stopping plan keeps the fingerprint it had before.
4. Unchanged, on purpose:
   - `import` with the vendor strategy still stops the service (caches, concurrent changes).
   - The import's pre-import snapshot still stops the service. The import stops it right after anyway, and changing it would make an interrupted import journaled by an earlier jrsctl rebuild into a different plan.
   - The upgrade's point-B full export (`full-export-stop-service`, `full-export`, `full-export-start-service`) still stops the service. With `--mode newdb` that archive is what the new repository database is built from, so it is taken with nothing changing underneath it. *Amended by ADR-0025 (2026-09-17): in newdb mode the export is taken in the vendor phase after `stop-service` and the service is not restarted before the vendor run; the restart between the two had let repository changes in that the rebuild then discarded.*

## Consequences

- A full-server backup no longer causes an outage.
- A live export can include a change made while it runs and miss one made just after a table was read. Operators who need a quiet, point-in-time export pass `--stop-service` or schedule the export when nobody works.
- Nobody has yet confirmed that `js-export --everything` gives a consistent archive against a running server on every supported version (7.1 to 10.x). The field tester's experience covers the versions support works with. If a version turns out to need the stop, the compatibility matrix is the place to require it for that version.
