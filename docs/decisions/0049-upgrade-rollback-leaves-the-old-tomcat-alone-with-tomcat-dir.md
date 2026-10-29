# ADR-0049: `upgrade rollback` leaves the old webapp and buildomatic alone after a run with `--tomcat-dir`

Status: accepted · Date: 2026-10-28 · Spec: §10.1 · Follows ADR-0048

## Context

ADR-0048 stopped the compensation of a failed side-by-side `run-vendor-upgrade` from restoring the old webapp and the installed buildomatic, because the vendor script ran against the new Tomcat and never changed them. It left `upgrade rollback <runId> --to-point B` unchanged and the question open.

The rollback command has the same problem, and a second one:

- **After a failed run**, `config.yaml` still names the old Tomcat. `restore-webapp` and `restore-buildomatic` move the old server's trees aside and re-extract them. That is the rename Windows refused in both field runs on 2026-10-27, and it changes nothing when it works.
- **After a finished run**, `point-config-at-target` has set `server.tomcatDir` to the new Tomcat. The rollback plan resolves its webapp target from that configuration, so `restore-webapp` would extract the old (9.x) webapp into the new Tomcat over the upgraded one, while the old Tomcat's webapp stays as it is.

The rollback plan has to know whether the run was side-by-side. The point-B manifest (`upgrade.json`) is written only by `record-upgrade`, at the end of a successful run, so a failed run has none. The arguments the plan was built from are stored with it (`StoredPlan.argsJson`, the document `PlanRegistry.upgradeArgs` writes, `tomcatDir` included since ADR-0026). A run records its plan id, and a plan a run consumed is never deleted while the run exists. That covers runs made before this change too, including the field runs.

## Decision

- `planRollback` reads `tomcatDir` from the stored arguments of the upgrade run's plan. When it is set, the plan leaves out `restore-webapp` and `restore-buildomatic`, names no tree among its affected paths, and warns that both trees and the new Tomcat are left as they are, that the old Tomcat is the one to start, and that a service re-registered for the new Tomcat must be moved back.
- Everything else is unchanged: `stop-service`, `restore-config`, `restore-keystore`, `restore-jrsctl-config` (which points `server.tomcatDir` back at the old Tomcat), `--restore-database` with the buildomatic the run recorded, `start-service`, `wait-for-server`, `record-rollback`.
- If the stored plan is missing or cannot be read, the rollback compares the webapp the point-B manifest records with the one `config.yaml` now gives. `point-config-at-target` moved `server.tomcatDir` to the new Tomcat right after `record-upgrade` wrote the manifest, so a difference means a side-by-side run, and the rollback leaves both trees alone as above. Amended 2026-10-29 (review finding 2): this consequence said the rollback then restored both trees as before, "not a new risk"; but the trees it restored were under the retargeted `server.tomcatDir`, so point B's old webapp was extracted over the new Tomcat's. With neither a stored plan nor a manifest (a run that never reached `record-upgrade`), the configuration was never retargeted and the in-place behaviour is right.

## Consequences

- A rollback after a side-by-side run no longer touches either Tomcat's webapp. The new Tomcat stays the operator's to discard, as ADR-0048 says for the compensation.
- jrsctl does not re-register services (ADR-0026 amendment), and a side-by-side upgrade needs `service.kind: manual`, so `start-service` asks the operator to start Tomcat. The warning says which one.
- The rollback plan now depends on the stored plan arguments of the run it rolls back. A later change to the `upgradeArgs` document must keep `tomcatDir`.
