# ADR-0012: `newdb` drops the repository database, so both upgrade modes are gated

Status: accepted, 2026-09-13. Supersedes the `newdb` sentence of spec §10.1 (Draft 1.1).

## Context

Spec §10.1 said that with `--mode newdb` "the vendor script creates a new repository database;
the existing database is never modified", made `newdb` the default, gated only `samedb` behind
`--db-backup-confirmed`, and promised that a `newdb` rollback to point B is "a complete restore:
webapp, keystore, config, and the connection back to the old database". The plan summary, the
operator guide, the README and the console said the same.

The vendor's own script says otherwise. `buildomatic/bin/upgrade-newdb.help` in JasperReports
Server 10.0.0 lists the steps of `js-upgrade-newdb` as: validate settings, validate the database
connection, **delete the existing, older jasperserver database**, create and initialize the new
one, import the new resources, **import the export zip file specified on the command line**, then
replace the war. "New" describes the schema, not a second database: the script drops and
recreates the database named by `js.dbName` in `default_master.properties`. jrsctl writes that
file itself (`WriteMasterProperties` copies the installed properties minus passwords, spec §7.4),
so the target always names the database the server is using. There is no configuration by which
a jrsctl `newdb` upgrade leaves the existing database untouched.

Two consequences followed from the wrong sentence. First, `VendorSteps.RunVendorUpgrade` invoked
`js-upgrade-newdb` with no argument, and the wrapper (`do-js-upgrade.bat pro standard %*`)
refuses: "import file expected as input". Every `newdb` upgrade failed at the vendor step, safely
but always. Second, once given its argument, a `newdb` run followed by `upgrade rollback --to-point
B` would restore the old webapp against a database the script had already rebuilt with the new
schema, the state `PointBIntegrity` documents as worse than not rolling back, while the summary
told the operator the rollback was complete. Found as U1 and U2 of
`docs/reviews/2026-09-13-codebase-assessment.md`.

## Decision

1. **The spec follows the vendor.** §10.1 now states that `js-upgrade-newdb` drops and recreates
   the repository database named in `default_master.properties` and imports the point-B full
   export into it, and that jrsctl can undo neither mode's database change.
2. **Both modes require `--db-backup-confirmed`.** The CLI refuses either mode without it (exit 2,
   nothing planned); `confirm-db-backup` is in every upgrade plan and audits the confirmation with
   the mode; the console's upgrade form requires the same checkbox. Database backup stays out of
   scope (§1.3).
3. **Every upgrade and rollback plan carries the files-only sentence** of §10.1, "Rollback
   restores files only. Restore the database from your own backup before running rollback.",
   plus a mode-specific line saying what the vendor script does to the database. The rollback
   plan after a `newdb` run names the point-B `full-export.zip` as the repository-level backup the
   operator can re-import with the restored buildomatic's `js-import`; jrsctl does not do that
   itself.
4. **`RunVendorUpgrade` passes the point-B full export** as the single argument of
   `js-upgrade-newdb`, and refuses in precheck when that file is missing. The `js-ant` fallback
   for a package that ships no wrapper runs what the wrapper itself would run:
   `upgrade-minimal-<ce|pro>` with `-Dstrategy=standard -DimportFile=<export>` for `newdb` and
   `-Dstrategy=inDatabase` for `samedb`; the previous `upgrade-newdb`/`upgrade-samedb` targets
   do not exist in the vendor's build files.
5. **The test packages ship the wrappers with the vendor's contract.** The fake
   `js-upgrade-newdb` in the ops fixture and in `Phase5UpgradeTest` exits 1 without an existing
   export file, so a green run proves the argument is passed.

Rejected: (a) making jrsctl choose a distinct `js.dbName` for the target so the old database
survives. It departs from the vendor's documented procedure, leaves an orphaned database and its
disk behind, needs `CREATE DATABASE` rights the operator may not have granted, and whether
`drop-db` tolerates a database that does not exist differs by database type; a wrong "distinct"
judgement would be data loss. (b) Re-importing the point-B export during rollback. It needs the
database dropped and recreated again with the *old* buildomatic, adds an hour-scale step to a
rollback that must be dependable, and still cannot restore what an export does not carry.
Either could be added later as an explicit, separately gated option.

## Consequences

- `newdb` is no longer the "safe default"; it is the vendor's default. Operators who relied on
  the old sentence must take a database backup before any upgrade, which the vendor's own
  documentation already requires.
- The plan and the gate say what the script will do before it does it; nothing about the vendor
  script changes.
- `UpgradeOptions.newdb(...)` builds options with the backup confirmed, since no run proceeds
  without it.
- The README's "isolated new-database mode" claim and the capabilities note are corrected; the
  console's mode labels say what each mode does to the database.
