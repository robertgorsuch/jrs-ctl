# ADR-0062: in a run with `--tomcat-dir`, the steps that start, stop or wait for the upgraded server watch the new Tomcat

Status: accepted · Date: 2026-11-18 · Spec: §10.1 · Issue #340 · Amends ADR-0026, ADR-0049

## Context

A live 8.0.4 → 10.0.0 upgrade on Windows (WAR install, `--tomcat-dir` pointing at a new Tomcat 10.1, `service.kind: manual`) stopped at `start-service`. The prompt said "Start the JasperReports Server Tomcat under C:\Tomcat9 now", which named the **old** Tomcat. The operator started the new one, and JRS 10.0 served from it, but jrsctl never advanced.

The cause is how the controller is bound:

- **Where the directory comes from:** the platform's service controller is built with `server.installDir` (`Bootstrap` calls `withInstallDir`). For `manual`, `ManualServiceController` and `TomcatState` count as RUNNING only a Tomcat process that belongs to that directory.
- **What was missing:** the upgrade plan used that runtime for every service step, including those that act after the webapp has moved to `--tomcat-dir`.
- **What a timeout would have done:** `start-service` would have timed out after 600 s and compensated the `vendor-upgrade` phase. The irreversible steps are skipped, and the rest are undone in reverse:
  - the point-B file restore runs
  - `copy-webapp-to-tomcat` deletes the webapp from the new Tomcat while it serves
  - `stop-service`'s compensation asks to start the old Tomcat, against a database that `js-upgrade-newdb` has already rebuilt
- **Rollback had the mirror problem:** after a side-by-side upgrade, its `stop-service` watched the old Tomcat. That Tomcat isn't running, so the step passed at once and left the upgraded server running in the new one.

## Decision

- **`HostTomcatServiceRuntime`** (`ops.upgrade`) wraps the upgrade's runtime. Its `controller()` comes from `platform.withInstallDir(<tomcat>)`; everything else delegates unchanged. For `manual` this changes both the prompt ("under `<new Tomcat>`") and the process scan. For a kind driven by a service name, `withInstallDir` changes only the lingering-JVM check, and `--tomcat-dir` refuses those kinds anyway (ADR-0026).
- **Forward run, with `--tomcat-dir`:** `start-service` and `wait-for-server` in `vendor-upgrade`, and the reconcile phase's `stop`/`start`/`wait` around a `WEB-INF` customization, use the new Tomcat. The `stop-service` before the vendor run still stops the configured (old) Tomcat, and its compensation still restarts that one: before the vendor run, the old server is the right one to bring back.
- **Rollback of a side-by-side run** (the new Tomcat found from the stored plan or the point-B manifest, ADR-0049): `stop-service` stops the new Tomcat; `start-service` and `wait-for-server` bring back the old one through the configured installation.
- **The plan's start instruction** for `manual` (`ServiceSwitch`) now names the directory to run `startup.bat`/`startup.sh` from. Tomcat's script derives `CATALINA_HOME` from the current directory and fails with "The CATALINA_HOME environment variable is not defined correctly" when run from elsewhere, which happened in the same run.

## Not decided here

- **Timeouts after the vendor run:** whether a failure of `start-service` or `wait-for-server` *after* `run-vendor-upgrade` should stop compensating (a `Fatal` with "start the server, then `runs recover --resume`") instead of undoing file steps over a rebuilt database. That changes engine-visible failure semantics for every upgrade, so it needs its own decision. With this ADR the common cause of that timeout, watching the wrong Tomcat, is gone.
- **`point-config-at-target`** moved `server.tomcatDir` but not `server.installDir`, so on a plain WAR install the next jrsctl process still bound to the old Tomcat. Decided in ADR-0063 (issue #343).

## Consequences

- With `manual`, the operator is asked to start the right Tomcat, and the run proceeds once it is up.
- `FakePlatform.withInstallDir` now returns a view that records `stop <dir>` / `start <dir>`, so tests can assert which Tomcat a step acted on. `UpgradeRunTest` covers the forward run with and without `--tomcat-dir`, and the side-by-side rollback.
- Not verified live: the fix was tested against the fakes only. The next 8.x → 10 field run with `--tomcat-dir` is the live check, and should be recorded in `BUILD_STATUS.md`.
