# jrsctl — Capability Summary

Source: `docs/spec.md` Draft 1.1 (2026-09-08).

## What it is

`jrsctl` is a single self-contained lifecycle tool for JasperReports Server (JRS). It runs on the server host, needs no pre-installed JDK, PowerShell or Python, and ships as a portable archive for Windows and Linux x86_64 with its own bundled runtime. It has a command-line interface and a local web console that share one execution engine and one event stream. It works in air-gapped environments.

## Capabilities

### Hotfix management
- Author signed hotfix bundles (`hotfix build`): manifest validation, per-file SHA-256, Ed25519 signature.
- Verify a bundle without touching the server: signature, hashes, applicability to the detected version, edition and tenancy.
- Apply a hotfix as a reviewed plan: dependency and conflict checks against installed hotfixes, file-overlap detection, disk and lock preflight, snapshot of every file touched, service stop when WEB-INF changes, staged atomic swap, optional idempotent SQL, restart and wait, post-checks, state record.
- Roll back a hotfix exactly from its snapshot, with SQL rollback scripts where provided. Rollback is last-in-first-out per file, with a cascade option when later hotfixes overlap.
- List installed, rolled-back and superseded hotfixes, in text or JSON.

### Repository export and import
- Export selected URIs, users and roles, access and audit events, monitoring and settings, or a full-server backup.
- Import with update, skip-user-update, theme and event options.
- Two strategies chosen per operation and shown in the plan: REST v2 async endpoints while the server runs, or the vendor `js-export`/`js-import` tools with a managed service stop for full-server work.
- Keystore fingerprint recorded with every export and checked before import, with a guided path for importing a source keystore.
- Pre-import snapshot of the affected subtree and a best-effort rollback that re-imports it.

### Version upgrades
- Orchestrates the vendor buildomatic upgrade scripts on the server's own Java.
- New-database mode by default so rollback is a complete file and connection restore. Same-database mode is allowed only with an explicit, audited confirmation that a database backup exists.
- Backup phase before any change: full export, keystore, webapp and buildomatic archive, configuration files.
- After the upgrade, classifies each installed hotfix as re-applicable or superseded and re-applies only on confirmation.
- Registered customizations are compared three ways (original, customized, new) and re-applied automatically only when the new file is unchanged; conflicts are reported with a diff.
- Smoke test gate at the end, with rollback to the backup point on failure.

### Detection and diagnostics
- `init` finds the installation, Tomcat layout, service type, database settings, run-as user and vendor Java, and writes the configuration for confirmation.
- `doctor` runs some twenty checks with pass/warn/fail and remediation text: reachability, auth, version and edition, compatibility matrix, capability probes, layout, service control, permissions, disk, keystore, vendor scripts, Java version, database, pending runs, lock state, network mode.
- `smoke` exercises login, server info, repository listing, a reference report to PDF, the scheduler API and a small export round trip. A mutating variant uploads and runs a bundled report.
- `selfcheck` verifies the runtime image, configuration, key ring and state schema.

### Local web console
- Dashboard, new-operation form that produces a plan for confirmation, live run view with step tree and log pane, history, doctor view.
- Cancel and rollback from the browser. Support bundle download with secrets redacted.
- Loopback by default with a per-launch token; non-loopback requires TLS and a password. No external resources, so it works offline.

### Secrets and keys
- Credentials resolved from environment variables, permission-checked files, or an AES-GCM encrypted store with an operator passphrase. Non-interactive unlock for scheduled runs.
- Trusted key ring for hotfix signatures with the Jaspersoft publisher key pinned and customer keys addable.

## Safety model shared by every operation
- Plan then apply: every mutating command produces a plan of steps, shows files, resources, restarts, backups and rollback points, and runs only after confirmation.
- Fingerprinted plans: execution refuses to run if the server, inputs, target files or configuration changed since planning.
- Idempotent steps with compensations, grouped into phases that are rollback points.
- Snapshots verified on creation and before restore, with retention that never prunes what an installed hotfix or the last upgrade depends on.
- Transactional journal in SQLite; interrupted runs are detected at startup and can be resumed or rolled back.
- One run at a time per host, enforced by a lock.
- Fail closed: unsupported versions, unsigned bundles, keystore mismatches and missing backups stop before mutation unless an audited override is given.
- Redaction of secrets in logs, events, JSON output, console streams and support bundles.
- Append-only audit of runs, overrides, key and configuration changes.
- Isolated network mode that allows HTTP only to the server itself.

## Command reference
| Command | Purpose |
|---|---|
| `jrsctl init` | Detect the installation and write config |
| `jrsctl doctor` | Environment and compatibility report |
| `jrsctl smoke [--mutating]` | Functional checks against the running server |
| `jrsctl selfcheck` | Verify the tool itself |
| `jrsctl hotfix build\|verify\|apply\|rollback\|list` | Hotfix lifecycle |
| `jrsctl export` / `jrsctl import` | Repository content and full-server backups |
| `jrsctl upgrade` / `jrsctl upgrade rollback` | Version upgrade orchestration |
| `jrsctl customizations register\|unregister\|list\|diff` | Track customized files across upgrades |
| `jrsctl runs recover <id> --resume\|--rollback` | Finish or undo an interrupted run |
| `jrsctl secrets init\|set\|remove\|list` | Encrypted secret store |
| `jrsctl keys list\|add\|remove\|generate` | Trusted signing keys |
| `jrsctl console` | Start the local web console |

Common flags: `--plan` (show the plan only), `--yes` (non-interactive), `--json` (machine output), `--explain` (embedded documentation).

## Exit codes
0 success · 1 usage · 2 precheck or doctor failure, nothing mutated · 3 failed and rolled back · 4 failed, rollback incomplete · 5 cancelled · 6 unsupported server · 7 signature failure · 8 pending recovery · 9 lock held.

## Platform coverage
- JRS 7.1 through 10.x, Community and Commercial, single and multi-tenant.
- Tomcat deployments controlled as a Windows service, systemd unit, ctlscript, catalina script or manually.
- PostgreSQL, MySQL, Oracle, SQL Server and DB2 repositories.
- Windows and Linux on x86_64.

## Not included in v1
Report authoring, business-user UI, database engine backups, remote execution over SSH or WinRM, macOS, ARM64, native installers, OS keyring integration, JBoss/WildFly/WebSphere.
