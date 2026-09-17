# ADR-0025: a newdb upgrade takes its full export after the stop and never restarts before the vendor run

Date: 2026-09-17. Status: accepted. Amends ADR-0021 item 4 (the upgrade's point-B export).

## Context

`js-upgrade-newdb <export>` drops the repository database and rebuilds it from the export it is
given (ADR-0012). Until this decision the upgrade plan stopped the service, took that export in the
backup phase, **started the service again**, archived the webapp, keystore and configuration, wrote
the target `default_master.properties`, and only then stopped the service for the vendor run.
Between the restart and the second stop the server accepted logins, ran scheduled reports and let
users edit resources. In `newdb` mode every one of those changes was lost when the vendor script
rebuilt the database from the export taken before them. The vendor's own procedure for a newdb
upgrade is "1. Stop your application server" before anything else, then export, then run the script
(upgrade guide 10.1 pp.43-44, 56-57), and the 2023 upgrade deck says to shut the old server down
and take the final export at that point. The review in `docs/reviews/2026-09-17-vendor-doc-review.md`
(§1.6) found the window.

`samedb` has no such window: the script migrates the database in place and the export is only a
rollback aid, so serving between the export and the run costs nothing.

## Decision

1. In `--mode newdb` the plan is: preflight; point B = `backup-keystore`, `backup-webapp`,
   `backup-config` (none of which needs the server down); then, in the vendor phase,
   `write-master-properties`, `stop-service`, `full-export`, `run-vendor-upgrade`,
   `start-service`, `wait-for-server`. The export is taken once the service is down and nothing
   starts the server before the vendor script has run. The plan summary carries a warning that says
   so and why.
2. `--mode samedb` keeps the previous shape: `full-export-stop-service`, `full-export`,
   `full-export-start-service`, `full-export-wait-for-server` in the backup phase, then the rest.
3. The export step keeps its id (`full-export`) and its place under `snapshots/<runId>/`, so
   `upgrade rollback <runId> --to-point B` names the same archive as before, and the vendor run's
   precheck still refuses to start without it.
4. A vendor-phase failure in newdb mode compensates the phase: point-B files are restored and the
   phase's own `stop-service` starts the service again, because it holds the "this run stopped it"
   marker. A service the operator had already stopped is left stopped, as before.

## Consequences

- A newdb upgrade has one outage instead of two, and it starts a little earlier (at the export
  rather than at the vendor run). Point B holds the file backups; the export exists only if the run
  reached the vendor phase, which every run that needs it does.
- The plan phase table in the operator guide differs between the two modes.
- ADR-0021 item 4 said the point-B export "is taken with nothing changing underneath it"; that
  was true of the export itself but not of the interval after it. This ADR closes that interval.
