# ADR-0047: newdb in a new Tomcat says it rebuilds the old server's database, and asks first

Status: accepted · Date: 2026-10-28 · Spec: §10.2, §10.4 · Field log `js-upgrade-pro_2026-10-27_04-14_18500.log` (9.0.0 PRO to 10.0.0, Windows); meeting of 2026-10-27

## Context

`write-master-properties` stages the installed `default_master.properties` keys after the target package's own file, so an installed key wins (spec §7.4, `MasterInvariants`). In newdb mode, `js-upgrade-newdb` then drops and recreates the database the staged file names and imports the point-B export into it. That database is always the one the installed server uses. This holds with `--tomcat-dir` too: the old Tomcat and webapp are left in place, but the old server has no repository of its own version afterwards.

On 2026-10-27 a vendor run on the field box (`--tomcat-dir`, JRS 9 still needed) ran `drop database "jasperserver9"` against the live 9.0 repository. It failed only because another session was connected. The vendor's `drop-js-db` target uses `onerror="continue"`, so with nothing connected the drop would have succeeded. The plan had said "drops and recreates the repository database named in default_master.properties", but it never named the database, and nothing told the operator that the old server would lose it. In the same week's meeting, the field team said that customers going from 9 to 10 usually move to a new environment with a separate, newer PostgreSQL. jrsctl cannot do that today.

Two ways forward were considered: (A) keep the design, but name the database in every newdb plan and require an explicit confirmation when `--tomcat-dir` suggests the old server stays usable; (B) a separate-target mode, where the target's database settings win, the old database is never dropped, and rollback leaves it alone. The maintainer chose A for now. B needs its own design: which keys may differ, the export and import between servers, and a rollback that does not rebuild a database the upgrade never touched.

## Decision

- Every newdb plan carries a warning naming `js.dbName`, `dbHost` and `dbPort` from the installed file (or "(not set)"), saying that this is the database the installed server uses. With `--tomcat-dir`, it adds that the old server in its Tomcat cannot run against it afterwards and that a separate target database is not supported.
- `jrsctl upgrade --mode newdb --tomcat-dir <dir>` exits **2** before anything is planned unless `--db-rebuild-confirmed` is given, the same way samedb exits without `--db-backup-confirmed`. `--test` is exempt: the rehearsal changes nothing.
- The gate lives in the command line only. `UpgradeOptions`, the stored arguments and the fingerprint are unchanged, so a run planned by 2.4.0 or by this version recovers with the plan it fingerprinted.
- The guided menu asks before adding the flag, and stops without changing anything when the answer is no.

## Consequences

- A side-by-side newdb command line from 2.4.0 now exits 2 until the flag is added.
- An in-place newdb upgrade (no `--tomcat-dir`) is not gated. The old webapp is replaced anyway, so the old server is gone with or without its database, and the plan still names the database.
- The confirmation is not audited, unlike `--db-backup-confirmed`; adding it to the audit would mean adding it to `UpgradeOptions`.
- B stays open. When it lands, the gate's message is where operators learn about it.
