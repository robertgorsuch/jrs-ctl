# jrsctl — Technical Design Specification

**Product:** JasperReports Server Lifecycle Tool (`jrsctl`)
**Owner:** Jaspersoft Product Management
**Audience:** Claude Code (autonomous build agent) and reviewing engineers
**Status:** Draft 1.1 — build contract
**Date:** 2026-09-08
**Supersedes:** Draft 1.0 (2026-09-08). Changes are listed in `docs/spec-changelog.md`.

---

## 0. How to use this document (instructions for the build agent)

This specification is the single source of truth for building `jrsctl`. Read it fully before writing code. The canonical copy lives at `docs/spec.md` in the repository; `CLAUDE.md` points at it.

Working rules:

1. Build in the phase order defined in §14. Do not start a phase until the previous phase's acceptance script passes **locally on the development OS**. Both-OS CI (§16) must be green before a phase branch is merged, but local acceptance is the gate for starting the next phase.
2. Create `CLAUDE.md` at the repo root on the first run containing: the module map (§4), the coding conventions (§15), and a pointer to `docs/spec.md`. Keep it under 200 lines and update it when the architecture changes.
3. Never reimplement JasperReports Server internals, buildomatic, or `js-export`/`js-import`. Orchestrate them (§7.4, §9).
4. Every **mutating** operation must be expressed as a `Plan` of `Step`s (§6). Read-only operations (`doctor`, `smoke`, `selfcheck`, `list`, `keys list`, `init` detection) are plain functions that return a report. Do not write ad-hoc procedural code that mutates the server or filesystem outside a `Step`.
5. Every `Step` that mutates state must be idempotent (§6.1) and must have a compensating action or be explicitly marked `irreversible` with a justification comment.
6. When the spec is ambiguous, choose the safer option (more preflight, more backup, fail closed), record the decision in `docs/decisions/NNNN-title.md` (ADR format), and continue.
7. Do not add third-party dependencies beyond the approved list in §13.3 without an ADR.
8. Run the full test suite before declaring any task complete. A phase is not done until `mvn verify` passes and the phase's acceptance script in `acceptance/` passes.
9. Secrets never appear in logs, test fixtures, or committed files. Use the redaction filter (§5.8) and verify with the secret-scan test.
10. If a required external artifact (JRS image, keystore, vendor script) is unavailable in the build environment, implement against the interface, stub it with WireMock or a fake, mark the test `@Tag("needs-jrs")`, and note it in `docs/BUILD_STATUS.md`. `needs-jrs` tests are a release gate, never a phase gate.
11. Code signing and publisher-key operations (§11.1, §14 Phase 7) are implemented as build steps but executed only in CI where the credentials exist. Locally the agent produces unsigned artifacts and records that in `docs/BUILD_STATUS.md`.
12. The primary development machine is Windows 11. Testcontainers requires Docker Desktop; crash-injection tests use `taskkill /F` on Windows and `kill -9` on Linux. Tests must be written to run on both.
13. When a spec change is needed, edit `docs/spec.md`, bump the draft number, and append an entry to `docs/spec-changelog.md` so ADRs can cite a spec revision.

---

## 1. Purpose and scope

### 1.1 Problem

Customers running JasperReports Server (JRS) need a supported, repeatable, auditable way to:

- apply and roll back hotfixes,
- export and import repository content and full-server backups,
- perform version upgrades,

across every supported JRS version and edition, on Windows and Linux, in both air-gapped and internet-connected environments, without installing PowerShell, Python, a JDK, or any other prerequisite.

### 1.2 In scope

- Single self-contained application (`jrsctl`) with a CLI and a local web console.
- Hotfix bundle format, authoring (`hotfix build`), application, verification, rollback.
- Repository export/import via REST v2 with fallback to vendor CLI tools.
- Upgrade orchestration over vendor buildomatic scripts.
- Environment detection (`init`), diagnostics (`doctor`), smoke tests, self-check.
- Crash-safe state, journaling, resume, and rollback.
- Packaging for Windows and Linux **x86_64** as portable archives with a bundled jlink runtime.

### 1.3 Out of scope (non-goals)

- Report design, JRXML generation, linting, dashboard composition, domain/OLAP authoring.
- Any self-service or business-user UI.
- Managing the database engine itself (backups of the RDBMS beyond what JRS export provides). See §10.1 for the consequences on upgrade rollback.
- Remote execution over SSH/WinRM. `jrsctl` runs on the JRS host.
- macOS support.
- ARM64 builds (JRS is supported on x86_64 only). Deferred by ADR.
- GraalVM native-image. Deferred by ADR; jlink already removes the JDK prerequisite.
- MSI/EXE/DEB/RPM installers. Deferred to a post-v1 phase by ADR; the portable archive is the v1 deliverable.
- OS keyring integration (Windows Credential Manager, Linux Secret Service). Deferred by ADR.

---

## 2. Definitions

| Term | Meaning |
|---|---|
| JRS | JasperReports Server (Community or Commercial/Pro) |
| Edition | `CE` or `PRO` |
| Tenancy | `SINGLE` or `MULTI` (organizations enabled) |
| Adapter | The `JrsAdapter` implementation that talks to the server over REST; behaviour is capability-driven |
| Strategy | An `ExportImportStrategy` (`REST` or `VENDOR_CLI`) chosen per operation |
| Plan | Ordered list of Steps, grouped into Phases, computed before execution |
| Phase | Named group of consecutive Steps; every Phase boundary is a rollback point |
| Step | Smallest unit of work with precheck, idempotent action, postcheck, compensation |
| Run | One execution of a Plan, with a unique `runId` |
| Snapshot | Hashed backup of files or repository content taken before a mutating Step |
| State store | SQLite database holding installed hotfixes, runs, step transitions, snapshots, plans, audit |
| Journal | The append-only `step_transitions` table in the state store, used for crash recovery |
| Fingerprint | Hash of every input a Plan depends on; execution refuses a Plan whose fingerprint no longer matches |
| Run lock | File lock in `$JRSCTL_HOME` held for the duration of any mutating run |
| Bundle | Signed ZIP containing a hotfix manifest and payload |
| Isolated mode | Outbound HTTP permitted only to the `server.baseUrl` host; everything else refused and audited |
| Public mode | Outbound network permitted; proxies and TLS trust configurable |

---

## 3. Architecture overview

```
┌──────────────────────────────────────────────────────────────────┐
│  app: Main entry + wiring                                        │
│   ┌──────────────┐        ┌──────────────────────────────┐       │
│   │  cli (Picocli)│        │  console (Javalin + SSE + UI)│       │
│   └──────┬───────┘        └──────────────┬───────────────┘       │
│          └──────────────┬────────────────┘                       │
│                         ▼                                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ops: hotfix, export, import, upgrade, init, doctor,     │   │
│  │       smoke, customizations  → produce Plans or Reports  │   │
│  └───────────┬──────────────────────────────┬───────────────┘   │
│              ▼                              ▼                    │
│  ┌───────────────────────┐    ┌──────────────────────────────┐  │
│  │  core: config, secrets │    │  jrs: REST client, JrsAdapter │  │
│  │  platform, state store │◀───│  capabilities, strategies,    │  │
│  │  snapshots, engine     │    │  vendor-tool wrappers         │  │
│  │  (Plan/Step/Runner),   │    └──────────────────────────────┘  │
│  │  events, redaction     │                                       │
│  └────────────────────────┘                                       │
└──────────────────────────────────────────────────────────────────┘
```

Principles:

- **Plan then apply.** Nothing mutates until a Plan is shown and confirmed.
- **One engine, two front-ends.** CLI and console consume the same event stream.
- **Capabilities isolate version drift.** Ops code never branches on JRS version; it asks the adapter for capabilities.
- **Orchestrate vendor tools.** Buildomatic and js-export/js-import are wrapped, not rewritten.
- **Fail closed.** Any uncertainty halts before mutation with a clear next action.
- **Idempotent steps.** Any Step can be re-executed after a crash and converges to the same end state.

---

## 4. Module map

Maven multi-module project. Package root: `com.jaspersoft.jrsctl`.

| Module | Responsibility | Depends on |
|---|---|---|
| `core` | Config, secrets, platform abstraction, state store (incl. journal), snapshots, compat matrix, redaction, event model, engine (`Plan`, `Step`, `Runner`, retry/cancel, `EventBus`), run lock | — |
| `jrs` | REST v2 client, `JrsAdapter`, capability probes, `ExportImportStrategy` implementations, vendor-tool process wrappers, keystore inspection | `core` |
| `ops` | Operation implementations producing Plans (mutating) or Reports (read-only) | `core`, `jrs` |
| `app` | Picocli commands, `--json` output, progress tree renderer, Javalin console server, SSE endpoint, static UI, support bundle, main entry, shaded JAR | `ops` |
| `dist` | jlink runtime image, portable ZIP/tar.gz, SBOM, checksum and signing steps (signing executes in CI only) | `app` |
| `acceptance` | Phase acceptance scripts and Testcontainers harness | all |

---

## 5. Core module

### 5.1 Configuration

- Single config directory: `$JRSCTL_HOME` (default: `%ProgramData%\jrsctl` on Windows, `/var/lib/jrsctl` on Linux; falls back to `~/.jrsctl` if not writable).
- Files: `config.yaml`, `state.db` (§5.4), `runs.lock` (§5.5), `snapshots/`, `runs/` (per-run temp and staging), `keys/`, `secrets.enc` (§5.2), `console.token` (§11.2).
- Every config key overridable by env var `JRSCTL_<UPPER_SNAKE_KEY>` and by CLI flag. Precedence: flag > env > file > default.
- `jrsctl init` (§12.0) detects the installation and writes `config.yaml`; `doctor` validates it.
- `config.yaml` schema (JSON Schema in `core/src/main/resources/schema/config.schema.json`):

```yaml
server:
  baseUrl: http://localhost:8080/jasperserver-pro
  webappName: jasperserver-pro          # jasperserver | jasperserver-pro; explicit, not derived
  installDir: /opt/jasperreports-server
  tomcatDir: /opt/jasperreports-server/apache-tomcat
  runAsUser: jasperserver               # OS account that runs Tomcat; owns ~/.jrsks and ~/.jrsksp
  auth:
    mode: basic                         # basic (default) | form | token
    username: jasperadmin
    passwordRef: env:JRS_PASSWORD       # env:NAME | file:/path | enc:NAME  (see 5.2)
service:
  kind: systemd                         # windows-service | systemd | ctlscript | catalina | manual
  name: jasperreportsTomcat             # windows-service / systemd only
  scriptPath: /opt/jasperreports-server/ctlscript.sh   # ctlscript / catalina only
  stopTimeoutSeconds: 180
database:                               # required only for hotfixes that carry SQL
  type: postgresql                      # postgresql | mysql | oracle | mssql | db2
  url: jdbc:postgresql://localhost:5432/jasperserver
  username: jasperdb
  passwordRef: env:JRS_DB_PASSWORD
  driverDir: null                       # default: <installDir>/buildomatic/conf_source/db/<type>/jdbc
vendor:
  javaHome: /opt/jasperreports-server/java   # JDK used to run buildomatic; never jrsctl's bundled runtime
network:
  mode: isolated                        # isolated | public
  proxy: { host, port, username, passwordRef, noProxy: [host | .suffix] }
  trustStore: { path, passwordRef }
console:
  bind: 127.0.0.1
  port: 7420
  tls: { enabled, certPath, keyPath }
  auth: { mode: token }                 # token (default, always on) | local (token + password for non-loopback)
backups:
  retentionDays: 30
  maxSnapshots: 20
smoke:
  reportUri: /public/Samples/Reports/AllAccounts   # WARN if absent
```

Rules:
- `network.mode: isolated` is enforced in the HTTP client: an allowlist containing only the `server.baseUrl` host. Any request to another host is refused, logged as `FAIL`, and audited. This makes isolated mode testable with WireMock.
- `vendor.javaHome` is mandatory for upgrade and vendor-strategy export/import. `doctor` verifies it matches the compat matrix requirement for the detected JRS version (JRS 8.x → Java 11, JRS 9+ → Java 17). jrsctl's own runtime is never passed to buildomatic.
- `service.kind: manual` means jrsctl prints the stop/start instruction, waits for the operator (or `--yes` fails with exit code 2), and polls `serverInfo` until the server state changes.

### 5.2 Secrets

- `SecretRef` resolves three sources:
  - `env:NAME` — environment variable.
  - `file:/path` — file contents; `doctor` FAILs if the file is group/world readable (Linux) or has ACLs beyond the owner and Administrators (Windows).
  - `enc:NAME` — entry in `$JRSCTL_HOME/secrets.enc`, AES-GCM (JDK built-in) with a key derived (PBKDF2-HMAC-SHA256, 600k iterations) from a machine-bound salt plus an operator passphrase. Managed by `jrsctl secrets init|set|remove|list`.
- Non-interactive unlock of `secrets.enc`: `JRSCTL_PASSPHRASE` env var or `--passphrase-file <path>`. If neither is present and stdin is not a TTY, resolution fails with exit code 2 and a clear message.
- Secrets are held in `char[]`, zeroed after use, never `toString()`-able.
- OS keyrings are out of scope for v1 (§1.3).

### 5.3 Platform abstraction

Interface `Platform` with `WindowsPlatform` and `LinuxPlatform`:

```java
interface Platform {
  OsFamily os();
  Arch arch();
  ServiceController services(ServiceConfig cfg);   // status/start/stop for the configured service.kind
  FileOps files();                                  // atomic replace, lock detection, perms, ACL preserve, streaming hash
  ProcessRunner processes();                        // ProcessBuilder wrapper, no shell, streamed output, timeout
  Path defaultHome();
  TomcatLayout detectTomcat(Path installDir);
  List<Path> candidateInstallDirs();                // used by `init`
}
```

Rules:
- No `Runtime.exec(String)`. No shell invocation. Arguments passed as lists.
- **Any change under `WEB-INF/lib` or `WEB-INF/classes` requires the service to be stopped, on both OSes.** There is no replace-on-restart strategy. A hotfix manifest may declare `restart: none` only when every file it touches is outside those directories and is not held open by Tomcat; `Preflight` verifies this and fails the Plan otherwise.
- Windows: detect locked files before writing even after a service stop (lingering processes). If still locked after `service.stopTimeoutSeconds`, fail the Plan with the holding process id where available.
- Preserve file ownership/permissions/ACLs on replace.
- `ServiceController` polls service state after stop/start; it never assumes the operation completed synchronously. Windows uses `sc.exe query|stop|start`; Linux uses `systemctl` or the configured script.
- All file I/O is streaming. No whole-file `byte[]`. SHA-256 is computed while streaming; downloads and archives are written incrementally.

### 5.4 State store

- SQLite file `state.db` via `sqlite-jdbc`. Opened with `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`.
- **The state store is the single source of truth for run state.** There is no separate on-disk journal.
- Tables:
  - `servers` — detected identity, last seen.
  - `hotfixes_installed` — `id` (manifest id, the primary key), version, installed run, snapshot ref, state (`INSTALLED`, `ROLLED_BACK`, `SUPERSEDED`).
  - `hotfix_files` — every path a hotfix added/replaced/deleted, with before/after hashes; used for overlap and LIFO rollback checks (§8.4).
  - `customizations` — registered paths with original hash at registration and snapshot ref (§10.3).
  - `plans` — serialized Plan JSON, fingerprint, created at, expires at (TTL 30 minutes), consumed by run id.
  - `runs` — id, op, plan id, started, ended, terminal state, exit code.
  - `step_transitions` — the journal: `{ts, runId, stepId, phase, from, to, detail}`; append-only (trigger prevents UPDATE/DELETE).
  - `snapshots` — id, run, step, path, manifest hash, referenced-by.
  - `audit` — append-only (trigger prevents UPDATE/DELETE).
- All writes in transactions. Schema versioned with migrations in `core/src/main/resources/db/migrations/`.
- Support bundles export `step_transitions` for a run as JSONL; that export is derived, never read back.

### 5.5 Run lock and recovery

- `runs.lock` in `$JRSCTL_HOME` is taken (OS file lock) before any mutating Plan executes and held until the run reaches a terminal state. A second mutating run fails immediately with exit code 9 and the holder's run id and pid. The lock is checked before the pending-run check, and by trying it rather than by reading the pid out of the file: a run another process is executing is 9 ("wait for it"), and only a run no process holds any more is 8 ("recover it").
- On startup, `jrsctl` queries `runs` for rows without a terminal state. In interactive mode it offers `resume` or `rollback` (§6.6). In non-interactive mode (`--yes`, `--non-interactive`, `--json`) any mutating command fails with exit code 8 and prints the exact `runs recover` command to run. `--non-interactive` never confirms anything: a plan that needs confirmation exits 2 unless `--yes` is also given; without either flag the confirmation is asked on the terminal, or on stdin when stdout is not a terminal, with end of input meaning no.

### 5.6 Snapshots

- `snapshots/<runId>/<stepId>/` containing payload and `manifest.json` with SHA-256 per file, source paths, permissions/ACLs, owner, and timestamp.
- Verified on creation and again before any restore.
- Retention pruning respects `backups.*` and never prunes a snapshot referenced by an installed hotfix, by a registered customization, or by the most recent successful upgrade.

### 5.7 Compatibility matrix

- `compat/matrix.yaml` bundled (unsigned; it ships inside the same artifact as any key that could verify it). Lists supported JRS versions, editions, Java versions for buildomatic, app servers, databases, supported upgrade paths, and per-version capability expectations.
- `doctor` fails on unsupported combinations unless `--allow-unsupported` is passed (logged to audit; exit code unchanged).

### 5.8 Redaction

- `RedactingFilter` applied to all log appenders, event payloads, console SSE, support bundles, and `--json` output.
- Patterns: configured secret values in raw, Base64, and URL-encoded forms; `password=`; `Authorization:`; JSESSIONID; bearer tokens; keystore passwords; the console token.
- Test: any string registered as a secret must not appear in any output stream in any of the three encodings (property-based test with jqwik).

### 5.9 Event model

Typed, sealed event hierarchy so `--json` output validates against a schema:

```java
sealed interface Event permits PlanCreated, StepPending, StepRunning, StepRetry, StepSucceeded, StepFailed,
                              StepSkipped, StepRolledBack, StepRollbackFailed, Log,
                              RunSucceeded, RunFailed, RunCancelled, RunRolledBack {
  Instant ts(); String runId(); Optional<String> stepId(); String phase();
}
record StepFailed(Instant ts, String runId, Optional<String> stepId, String phase, StepFailure failure) implements Event {}
record StepRollbackFailed(Instant ts, String runId, Optional<String> stepId, String phase, String cause, List<Path> backups) implements Event {}
// ... one record per event type; payload fields are typed, never Map<String,Object>
```

`StepRollbackFailed` is what drives exit code 4.

---

## 6. Engine (in `core`)

### 6.1 Step contract

```java
interface Step {
  String id();
  String title();
  String phase();                               // every Step belongs to a named Phase; boundaries are rollback points
  boolean irreversible();                       // must be false unless justified in a comment
  CheckResult precheck(Context ctx);            // sealed: Pass | Warn(msg) | Fail(msg, remediation)
  StepResult execute(Context ctx, EventSink out);   // MUST be idempotent: re-execution converges to the same end state
  CheckResult postcheck(Context ctx);           // Fail here is a StepFailure.Recoverable
  StepResult compensate(Context ctx, EventSink out);  // no-op only if irreversible()
  RetryPolicy retryPolicy();                    // NONE or bounded exponential backoff
}

sealed interface StepResult permits StepResult.Ok, StepResult.Failed {}
record Failed(StepFailure failure) implements StepResult {}

sealed interface StepFailure permits Retryable, Recoverable, Fatal {
  String cause(); List<Path> affectedPaths(); List<URI> affectedUris(); List<Path> backups(); String nextAction();
}
```

Rules:
- Steps classify their own failures; the Runner never infers class from exception type.
- Idempotency is tested: the Phase 8 crash-injection suite kills the process mid-Step and re-executes the Step; end state must equal a clean run.
- `precheck` and `postcheck` both return `CheckResult`; there is no exception-based contract.

### 6.2 Plan and fingerprint

- `Plan` = ordered `List<Step>` grouped by `phase()` + `PlanSummary` (files touched, resources touched, service restarts, backup locations, rollback point per phase, chosen strategy, explicit warnings such as "database rollback is the operator's responsibility").
- Plans serialize to JSON for `--plan` output and the console; stored in the `plans` table with a 30-minute TTL.
- `PlanFingerprint` = SHA-256 over: server identity (`serverInfo` response), input artifact hash (bundle, archive, or upgrade package), SHA-256 of every target file the Plan will touch, and the resolved effective config. Execution recomputes the fingerprint and refuses to run (exit code 2) if it differs.

### 6.3 Runner

- Takes the run lock (§5.5), executes Steps sequentially, emits events to `EventBus`, writes every transition to `step_transitions` inside a transaction before emitting the event.
- On `StepFailed`:
  - `Retryable` → apply `RetryPolicy`, emit `StepRetry` per attempt; exhausted retries become `Recoverable`.
  - `Recoverable` → compensate the failing Step itself first when it is mutating and its `execute` ran (a precheck failure never ran and is not compensated), then run compensations in reverse order for all succeeded Steps back to the nearest Phase boundary (or the full plan with `--rollback-all`), emit `RunRolledBack` (exit 3). Any compensation failure emits `StepRollbackFailed` and ends the run with exit 4. (ADR-0009)
  - `Fatal` → halt, emit `RunFailed` with backup locations and manual next steps (exit 4 if state was mutated, else 2).
- Every failure message includes: step id, phase, cause, affected paths/URIs, backup location, next available action.

### 6.4 Cancellation

- Single cancellation token shared by Ctrl-C, console cancel, and timeouts.
- Cancellation completes or compensates the in-flight Step; it never abandons a partial write. Exit code 5.

### 6.5 Retry policy

- Default for HTTP steps: 5 attempts, base 2s, factor 2, max 60s, jitter 20%.
- Steps that hit the server during restart use a `waitForServer` sub-step with a 10-minute cap.

### 6.6 Resume and recovery

- `jrsctl runs recover <runId> --resume|--rollback`.
- Resume re-runs the precheck of the interrupted Step and then re-executes it (idempotency guarantees convergence). If the precheck fails, only rollback is offered.
- Rollback compensates every succeeded Step of the run in reverse, after first compensating a mutating Step the journal left `RUNNING` or `FAILED` (the process died before that Step's own compensation ran). A compensation must therefore converge from any partial state, including one where `execute` never started.

---

## 7. `jrs` module

### 7.1 Server detection and capabilities

- `GET {baseUrl}/rest_v2/serverInfo` → version, edition, features (tenancy is derived from the `MT` feature flag).
- Capability probing after version detection: each capability (`EXPORT_ASYNC`, `IMPORT_ASYNC`, `KEYSTORE_ENCRYPTION`, `ORGS`, `TOKEN_AUTH`, `PREAUTH`, `REST_LOGIN`) is verified by a non-mutating probe request. The compat matrix records the *expected* capability set per version; `doctor` WARNs on a mismatch between expected and probed.

### 7.2 JrsAdapter

One implementation, `RestJrsAdapter`, whose behaviour is driven entirely by probed capabilities. Version-specific quirk classes are added only when the nightly contract suite (§16) demonstrates drift, each with an ADR and a contract test.

```java
interface JrsAdapter {
  ServerIdentity identity();
  Session login(Credentials c);                   // basic by default; REST_LOGIN or form when required
  ExportHandle startExport(ExportRequest r);
  ExportStatus pollExport(ExportHandle h);
  Path downloadExport(ExportHandle h, Path target); // streamed to disk
  ImportHandle startImport(ImportRequest r, Path archive);
  ImportStatus pollImport(ImportHandle h);
  KeystoreInfo keystore();                         // resolved from server.runAsUser's home
  Set<Capability> capabilities();
  HealthReport health();                           // used by doctor and smoke
}
```

### 7.3 Export/import strategies

```java
sealed interface ExportImportStrategy permits RestStrategy, VendorCliStrategy {
  List<Step> exportSteps(ExportRequest r);
  List<Step> importSteps(ImportRequest r);
  boolean requiresServiceStop();                   // VendorCli: true; Rest: false
}
```

`VendorCliStrategy` wraps `js-export`/`js-import`. It is not a `JrsAdapter`; it cannot log in or report health. Selection rules are in §9.2.

### 7.4 Vendor tool wrappers

- Locate `buildomatic/` under `installDir` (or under the upgrade target package). Verify expected scripts exist for the detected version.
- Invoke with `ProcessRunner` using `vendor.javaHome` as `JAVA_HOME`, stream stdout/stderr into events (redacted), capture exit code, enforce timeout.
- Never modify vendor scripts. Property overrides are written to `default_master.properties` **in the buildomatic directory being invoked** (the upgrade target package's copy for upgrades; a run-scoped copy of the installed buildomatic for export/import). The pre-existing file, if any, is snapshotted first and restored by compensation. Open question Q5 (§19) tracks whether `js-ant` accepts an out-of-directory property file; if it does, prefer that.

### 7.5 REST client

- `java.net.http.HttpClient` with configurable proxy, trust store, timeouts, cookie management, and the isolated-mode host allowlist (§5.1).
- All responses parsed with Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`.
- Correlation id header per run for server-side log matching.

---

## 8. Hotfix subsystem

### 8.1 Bundle format

ZIP containing:

```
manifest.json
payload/            # files mirrored to their destination relative to tomcatDir or installDir
sql/                # optional, ordered *.sql plus rollback scripts, per-DB subdirs (postgresql/, mysql/, oracle/, mssql/, db2/)
checks/             # optional JSON check definitions
SIGNATURE           # detached Ed25519 signature over manifest.json only
```

The signature covers `manifest.json` only. The manifest carries the SHA-256 of every file under `payload/`, `sql/`, and `checks/`; verification checks the signature and then every listed hash. Any file in the ZIP not listed in the manifest fails verification.

`manifest.json` schema (`core/.../schema/hotfix-manifest.schema.json`):

```json
{
  "id": "JRS-10.0.0-HF-0007",
  "version": "1",
  "title": "Fix scheduler NPE",
  "applies": { "versions": [">=10.0.0 <10.1.0"], "editions": ["PRO"], "tenancy": ["SINGLE","MULTI"] },
  "requires": ["JRS-10.0.0-HF-0003"],
  "conflicts": [],
  "files": [
    { "action": "replace", "path": "webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar", "sha256": "...", "replaces": ["foo-1.2.2.jar"] },
    { "action": "add",     "path": "webapps/jasperserver-pro/WEB-INF/classes/fix.properties", "sha256": "..." },
    { "action": "delete",  "path": "webapps/jasperserver-pro/WEB-INF/lib/bar-0.9.jar" }
  ],
  "sql": [ { "db": "postgresql", "file": "sql/postgresql/001.sql", "sha256": "...", "idempotent": true, "rollbackFile": "sql/postgresql/001-rollback.sql" } ],
  "checks": [ { "file": "checks/scheduler.json", "sha256": "..." } ],
  "restart": "required",
  "prechecks": [ { "type": "fileExists", "path": "..." }, { "type": "http", "url": "/rest_v2/serverInfo", "expect": 200 } ],
  "postchecks": [ { "type": "http", "url": "/login.html", "expect": 200 } ],
  "rollback": "snapshot"
}
```

Manifest rules (enforced by `ValidateManifest`):
- `id` is the state-store key. `applies.versions` governs applicability.
- `files[].action` is mandatory: `add | replace | delete`.
- `restart: none` is permitted only if no file is under `WEB-INF/lib` or `WEB-INF/classes` (§5.3).
- Every `sql[]` entry must set `idempotent: true` and either provide `rollbackFile` or the manifest must set `"rollback": "irreversible"` with a `rollbackNote` string that is shown in the Plan summary. There is no transactional SQL promise; DDL auto-commits on several supported databases.
- SQL requires the `database` config section; `ValidateManifest` fails with remediation if it is absent.

### 8.2 Apply plan (generated Steps)

Phase `verify`:
1. `VerifySignature` — reject unsigned unless `--allow-unsigned` (audited).
2. `ValidateManifest` — schema + applicability vs. detected server + dependency/conflict check vs. state store + **file overlap check** against `hotfix_files` (a file already owned by an installed hotfix that is not in `requires` is a conflict).
3. `Preflight` — disk space, write access, lock detection, service status, `restart` consistency, database connectivity if SQL present.
4. `RunPrechecks`.

Phase `backup`:
5. `Snapshot` — every file to be replaced or deleted.

Phase `apply`:
6. `StopService` — always when `restart: required`.
7. `StageFiles` — write to `runs/<runId>/staging/`, verify hashes.
8. `AtomicSwap` — per file: rename staging into place (replace/add) or move to snapshot (delete). Idempotent: a file already at the target hash is skipped.
9. `ApplySql` — run each script via JDBC using the driver jar from `database.driverDir`; each script is idempotent by contract; compensation runs `rollbackFile` in reverse order.
10. `StartService` + `WaitForServer`.
11. `RunPostchecks`.

Phase `record`:
12. `RecordInstalled` — `hotfixes_installed`, `hotfix_files`, audit.

Compensations restore from Snapshot in reverse. After `RecordInstalled`, rollback is `jrsctl hotfix rollback <id>`.

### 8.3 Rollback plan

- Rollback is LIFO per file: if any file owned by hotfix N is also owned by a later installed hotfix, rollback of N is refused with the list of blocking hotfix ids. `--cascade` rolls back the blocking hotfixes first, newest to oldest, in one Plan.
- Steps: `StopService` (if any WEB-INF file) → `RestoreSnapshot` (verify hashes before and after) → `RunSqlRollback` (if present) → `StartService` + `WaitForServer` → `RecordRolledBack`.

### 8.4 Commands

- `jrsctl hotfix build <dir> --key <ref> --out <bundle>` — validates the manifest, computes hashes, signs, writes the ZIP. Used by hotfix authors and by the test fixture generator.
- `jrsctl hotfix apply <bundle> [--plan] [--yes] [--allow-unsigned]`
- `jrsctl hotfix rollback <id> [--cascade] [--plan] [--yes]`
- `jrsctl hotfix list [--json]`
- `jrsctl hotfix verify <bundle>` — signature, hashes, and applicability only

---

## 9. Export / import subsystem

### 9.1 Unified request model

```java
record ExportRequest(Scope scope, Set<String> uris, boolean includeUsersRoles, boolean includeAccessEvents,
                     boolean includeAuditEvents, boolean includeMonitoring, boolean includeSettings,
                     boolean fullServer, Path output)
record ImportRequest(Path archive, boolean update, boolean skipUserUpdate, boolean includeAccessEvents,
                     boolean includeAuditEvents, boolean includeMonitoring, boolean includeSettings,
                     boolean skipThemes, Path sourceKeystore, SecretRef sourceKeystorePassword)
```

### 9.2 Strategy selection

- `RestStrategy` when `EXPORT_ASYNC`/`IMPORT_ASYNC` probes pass and `fullServer=false`.
- `VendorCliStrategy` when `fullServer=true`, when probes fail, or when `--strategy vendor` is passed. The vendor strategy always includes `StopService` before `js-import` and before a full-server `js-export`, per vendor guidance, and `StartService` after.
- Strategy and its service-stop consequence are shown in the Plan summary.

### 9.3 Keystore handling

- `KeystoreInfo` resolves `.jrsks`/`.jrsksp` from the home directory of `server.runAsUser`; `doctor` FAILs if `runAsUser` is unset on a server with `KEYSTORE_ENCRYPTION`.
- Export records the keystore fingerprint in a sidecar `<archive>.jrsctl.json`.
- Import compares fingerprints; on mismatch, fail before mutation with instructions, or accept `--source-keystore` and `--source-keystore-password-ref` to perform the vendor-documented keystore import step first.

### 9.4 Import safety

- `PreImportSnapshot` exports the affected subtree using the same strategy as the import (full server via vendor when `update=true` at root).
- Rollback re-imports that snapshot. **This is best-effort**: re-import restores overwritten resources but does not delete resources the failed import created. The Plan summary and the operator guide state this explicitly.

### 9.5 Commands

- `jrsctl export [--uri ...] [--users-roles] [--access-events] [--full-server] [--strategy rest|vendor] --out <file>`
- `jrsctl import <archive> [--update] [--skip-user-update] [--source-keystore ...] [--strategy rest|vendor] [--plan] [--yes]`

---

## 10. Upgrade subsystem

### 10.1 Modes and rollback semantics

- `--mode newdb` is the **default**. The vendor script creates a new repository database; the existing database is never modified. Rollback to point B is a complete restore: webapp, keystore, config, and the connection back to the old database.
- `--mode samedb` migrates the existing database schema in place. **jrsctl cannot undo that migration**; database backup is out of scope (§1.3). `samedb` therefore requires `--db-backup-confirmed` (audited), `doctor` records the operator's confirmation, and the Plan summary states in plain text: "Rollback restores files only. Restore the database from your own backup before running rollback."

### 10.2 Orchestration plan

**Phase A — preflight**
1. `Doctor` (must pass).
2. `VerifyTargetPackage` — target JRS distribution present, checksum verified, version in compat matrix as a supported upgrade path from current, `vendor.javaHome` matches the target's requirement.
3. `ConfirmDbBackup` — `samedb` only; fails without `--db-backup-confirmed`.

**Phase B — backup** (rollback point B)
4. `FullExport` (vendor strategy; includes service stop/start).
5. `BackupKeystore`.
6. `BackupWebapp` — archive of `tomcatDir/webapps/<webappName>` and installed `buildomatic/`.
7. `BackupConfig` — `default_master.properties`, JNDI, context files.

**Phase C — vendor upgrade** (rollback point C = restore B)
8. `WriteMasterProperties` — into the target package's buildomatic dir (§7.4), snapshotting any existing file.
9. `StopService`.
10. `RunVendorUpgrade` — `js-upgrade-newdb` or `js-upgrade-samedb`; streamed output; `JAVA_HOME=vendor.javaHome`.
11. `StartService` + `WaitForServer`.

**Phase D — reconcile** (report-first; nothing mutates without confirmation)
12. `PlanHotfixReapply` — for each installed hotfix: if `applies` matches the new version *and* every `replaces` target exists in the new webapp, it is listed as `REAPPLICABLE`; otherwise `SUPERSEDED`. The list is shown; in interactive mode the operator confirms, with `--yes` only `--reapply-hotfixes` triggers re-application. Re-application runs the normal apply Plan per hotfix.
13. `PlanCustomizationReapply` — for each registered customization, a 3-way comparison of the original (hash at registration), the customized copy (snapshot), and the new file. If new equals original, the customization is reapplied automatically. Otherwise it is reported as `CONFLICT` with a unified diff and left for the operator.

**Phase E — verify**
14. `Smoke` (§12.2). Failure offers rollback to point B.
15. `RecordUpgrade` — marks hotfixes `SUPERSEDED`/re-installed, records the upgrade snapshot set as retention-protected.

### 10.3 Customizations

- `jrsctl customizations register <path>` snapshots the file and records its current hash as "original" in the `customizations` table. `unregister`, `list`, and `diff` are provided.

### 10.4 Commands

- `jrsctl upgrade --to <version> --package <path> [--mode newdb|samedb] [--db-backup-confirmed] [--reapply-hotfixes] [--plan] [--yes]`
- `jrsctl upgrade rollback <runId> --to-point B|C`
- `jrsctl customizations register|unregister|list|diff <path>`

---

## 11. Security

### 11.1 Bundle signing

- Ed25519 detached signatures using the JDK's built-in provider (no BouncyCastle). Customer key ring in `keys/trusted/`. Jaspersoft publisher key bundled and pinned; customers may add their own for internal hotfixes.
- `jrsctl keys list|add|remove|generate`.
- Publisher-key signing of release bundles and of the jrsctl distribution executes only in CI (rule 11).

### 11.2 Console security

- A per-launch bearer token is generated on every console start, printed once to the terminal, and written to `$JRSCTL_HOME/console.token` with owner-only permissions. Every `/api/*` request must carry it. This applies on loopback as well; a shared host must not allow other local users to start runs. The token file is restricted before the token is written into it, never afterwards, and on Windows its inherited access control entries are dropped; a file that cannot be made owner-only is deleted rather than left holding the token.
- The browser is opened with a single-use launch code, never with the token, because the URL becomes a command line any local account can read. The code is worth 10 seconds, only one is outstanding at a time, it is consumed by the first exchange, and both the exchange and every refusal are audited with the peer address. A home other accounts can reach gets no browser at all unless `--open` is given; a home jrsctl creates is owner-only from the start.
- The `Host` header must match the bound address or `localhost`; otherwise 421. This blocks DNS rebinding.
- Default bind `127.0.0.1`. Non-loopback bind additionally requires TLS and `console.auth.mode: local` (token plus operator password); refused otherwise.
- No cookies are set. Because auth is a header token, CSRF is not applicable.

### 11.3 Redaction and audit

- As §5.8. Audit rows for: every run start/end, every override flag (`--allow-unsigned`, `--allow-unsupported`, `--db-backup-confirmed`), key ring changes, config changes, console token issuance, launch-code exchanges and refusals, isolated-mode refusals.

### 11.4 Least privilege

- `doctor` warns if running elevated without a Step that requires it, and FAILs if `server.runAsUser`'s home is unreadable when keystore access is required.

---

## 12. Detection and diagnostics

### 12.0 `init`

- `jrsctl init [--yes] [--non-interactive]` detects the installation and, after confirmation or with `--yes`, writes `config.yaml` (`--non-interactive` alone reports and exits 2 rather than writing):
  - candidate install dirs from `Platform.candidateInstallDirs()` (common paths, the working directory of a running Tomcat process, the Windows registry/uninstall entries);
  - `webappName`, `tomcatDir`, `baseUrl` from the Tomcat layout and `server.xml` port;
  - `service.kind` and name by probing Windows services / systemd units / `ctlscript.sh`;
  - `database` and `vendor.javaHome` prefilled from `buildomatic/default_master.properties` (passwords are never copied; a `passwordRef` placeholder is written);
  - `runAsUser` from the Tomcat process owner.
- Every value is shown for confirmation; nothing is written without it unless `--non-interactive`.

### 12.1 `doctor`

Checks (each returns PASS/WARN/FAIL with remediation text): bundled runtime integrity; config schema; secret file permissions; server reachable; auth works; version/edition/tenancy detected; compat matrix match; capability probes vs. expected; install dir layout; service controller can query state; write access to target dirs; disk space; keystore present and readable for `runAsUser`; vendor scripts present; `vendor.javaHome` version matches matrix; database connectivity (if configured); pending runs; run lock free; snapshot store health; network mode consistency (isolated mode with proxy configured = WARN); running elevated without need = WARN.

### 12.2 `smoke`

Non-mutating by default: login; serverInfo; list `/` repository; run `smoke.reportUri` to PDF and verify the response starts with `%PDF` and exceeds 1 KB (WARN, not FAIL, if the report URI is absent); scheduler API reachable; export of a tiny subtree round-trips. `--mutating` uploads a bundled minimal JRXML under `/temp/jrsctl`, runs it, and deletes it.

### 12.3 `selfcheck`

Verifies every jar in the runtime image against a build-time manifest of hashes, runtime version, config schema, key ring, SQLite schema version, the host operating system and architecture against ADR-0002, and the directory the SQLite native library is unpacked into (a `noexec` mount fails the item). A failing platform item exits 6, not 2.

---

## 13. Console (in `app`)

### 13.1 Endpoints

All endpoints require the bearer token (§11.2).

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | tool version, matrix version, last run, lock state |
| GET | `/api/server` | detected server identity |
| POST | `/api/plan` | `{op, args}` → Plan JSON + `planId` (stored, 30-minute TTL) |
| POST | `/api/run` | `{planId, confirm: true}` → `runId`; refuses if fingerprint changed or TTL expired |
| GET | `/api/runs` | history |
| GET | `/api/runs/{id}` | plan, steps, outcome, backups |
| GET | `/api/runs/{id}/events` | SSE stream (replays `step_transitions`, then live) |
| POST | `/api/runs/{id}/cancel` | cancel |
| POST | `/api/runs/{id}/rollback` | rollback where available |
| GET | `/api/runs/{id}/support-bundle` | zip of plan, transitions (JSONL), logs, server info, state excerpt (redacted). Everything that can fail, the live doctor run included, is computed before the response is committed, so a failure is an error document rather than a truncated zip delivered as 200; the log is the file named by `jrsctl.log.file` and both the log and the event stream are tailed |
| GET | `/api/doctor` | run doctor, return report |

### 13.2 UI

- Static single-page app, vanilla JS + minimal CSS, no build step, served from the JAR. No external CDN references (isolated mode).
- Views: Dashboard (server identity, health, pending runs), New Operation (form → plan → confirm), Run (step tree with live status, elapsed, log pane, cancel, rollback), History, Doctor.
- Step status colors and icons must be distinguishable without color (icon + text).

### 13.3 Approved dependencies

Runtime: `picocli`, `javalin`, `jackson-databind`, `jackson-dataformat-yaml`, `slf4j-api`, `logback-classic`, `logstash-logback-encoder`, `sqlite-jdbc`, `networknt json-schema-validator`, `commons-compress`, `semver4j`.
Test: `junit5`, `assertj`, `wiremock`, `testcontainers`, `jqwik`, `mockito`.
Not approved without an ADR: `bouncycastle` (JDK provides Ed25519 and AES-GCM), `jna`.

---

## 14. Build phases and acceptance criteria

Each phase has an executable acceptance script in `acceptance/phaseN/` runnable via `mvn -pl acceptance verify -Dphase=N`. `needs-jrs` tests are excluded from phase gates and run as a release gate (§16).

**Phase 0 — Skeleton**
- Multi-module Maven build (§4); `CLAUDE.md`; `docs/spec.md`, `docs/spec-changelog.md`, `docs/BUILD_STATUS.md`; `jrsctl --version`; `selfcheck` passes; CI workflow runs on Windows and Linux runners.

**Phase 1 — Core + Engine**
- Config load with precedence tests; secrets resolve for `env:`, `file:`, `enc:` including non-interactive passphrase; platform layer detects OS and a fake Tomcat layout; service controller for every `service.kind` against fakes; state store migrations; step transitions written transactionally and recovered; run lock blocks a second process (exit 9); pending run blocks non-interactive commands (exit 8); snapshot create/verify/restore with permissions; redaction property test over raw/Base64/URL-encoded; Runner executes a fake 5-step plan with a forced `Recoverable` failure at step 4 and rolls back steps 4, 3, 2, 1 in that order; compensation failure yields `StepRollbackFailed` and exit 4; cancellation mid-step compensates; fingerprint mismatch refuses execution; streaming hash of a >2 GB sparse file stays under 64 MB heap.

**Phase 2 — Adapter + Detection + Doctor**
- WireMock fixtures for `serverInfo` and capability probes across 7.x/8.x/9.x/10.x; single adapter behaves correctly under each capability set; isolated-mode allowlist refuses a second host; `init` detects a fake layout on both OSes and writes a valid config; `doctor` produces a full report against WireMock; `@Tag("needs-jrs")` Testcontainers test exists and is stubbed if no image is available.

**Phase 3 — Hotfix**
- `hotfix build` produces a signed bundle from a fixture directory; signature and hash verification incl. an unlisted-file rejection; manifest schema validation incl. `action`, `restart`/WEB-INF rule, SQL rollback rule; apply plan generated for a sample bundle; service stop enforced for WEB-INF changes; atomic swap with Windows lock simulation (a test process holding the jar open); idempotent re-execution of `AtomicSwap` after a simulated crash; SQL apply and rollback against an H2-free approach (Testcontainers PostgreSQL, tagged `needs-docker`); rollback restores hashes exactly; LIFO rollback refusal and `--cascade`; file overlap conflict; `list` reflects state.

**Phase 4 — Export/Import**
- REST async export/import round-trip on WireMock; vendor strategy with a fake `js-export`/`js-import` script including the service stop; `vendor.javaHome` passed as `JAVA_HOME`; keystore resolved from `runAsUser`'s home; keystore mismatch detected and blocked; pre-import snapshot and best-effort rollback verified; the Plan summary carries the best-effort wording.

**Phase 5 — Upgrade**
- Full orchestration with fake vendor scripts for both modes; `samedb` refused without `--db-backup-confirmed`; `newdb` rollback to point B restores webapp, keystore, config and DB connection settings; hotfix reapply classification (`REAPPLICABLE`/`SUPERSEDED`) and confirmation gating; customization 3-way comparison auto-applies only the no-conflict case; smoke gate.

**Phase 6 — Console**
- All endpoints implemented behind the token; `Host` header check; plan TTL and fingerprint refusal; SSE replays transitions then streams live; UI renders a run end-to-end in a headless browser test; support bundle contains no secrets; cancel and rollback from UI.

**Phase 7 — Distribution**
- jlink runtime image; portable ZIP (Windows) and tar.gz (Linux) for x86_64; SBOM generated; SHA-256 checksums; signing step implemented and executed in CI only; installer smoke test of the portable archive on clean Windows and Linux VMs with no pre-installed JDK.

**Phase 8 — Hardening**
- Idempotency tests for every mutating command; crash injection (`taskkill /F` / `kill -9` mid-step) followed by `runs recover --resume` and `--rollback`; retention pruning incl. upgrade protection; `--json` for every command validated against schema; offline docs embedded; `--explain` on every command.

---

## 15. Coding conventions

- Java 21 (the tool bundles its own runtime; the server's Java is irrelevant to jrsctl code), `-Werror`, Error Prone enabled, Spotless (google-java-format).
- Records for immutable data; sealed interfaces for result/error/event types; pattern-matching `switch` over sealed types with no `default` branch so new variants fail compilation.
- No `null` returns from public APIs; use `Optional` or sealed results.
- All I/O through `Platform`. No `java.io.File`. No whole-file byte arrays.
- Every public class has a one-paragraph Javadoc stating its invariants.
- Test naming: `should_<behaviour>_when_<condition>`.
- Commit messages: Conventional Commits. One logical change per commit.
- Branch per phase; PR description must list which acceptance criteria are satisfied.

---

## 16. CI/CD

- GitHub Actions matrix: `windows-latest`, `ubuntu-latest`; JDK 21 Temurin.
- Jobs: build+unit, integration (Testcontainers on Linux only), acceptance per phase, secret scan (gitleaks), dependency audit (OWASP), SBOM, package, sign (CI secrets only), release on tag.
- The adapter contract (identity, capabilities, health) runs on every build against a recorded server per compat-matrix row (`jrs/src/test/resources/recordings/`, ADR-0011); a matrix row without a recording fails the build. Release gate (not phase gate), where an image exists: the same contract as `needs-jrs` tests against every JRS image available in the private registry, nightly; failures open an issue automatically. Open question Q6 (§19) names the CE image. The `integration` job is not a release gate while ADR-0007 is deferred.

---

## 17. Documentation deliverables

- `README.md` — install, quick start, five most common commands.
- `docs/spec.md` — this document; `docs/spec-changelog.md` — revision log.
- `docs/operator-guide.md` — every command, flag, exit code, and error class with remediation; states explicitly which rollbacks are best-effort (import) and which require an operator database restore (`samedb` upgrade).
- `docs/hotfix-authoring.md` — bundle format, `hotfix build`, signing, testing a bundle.
- `docs/security.md` — threat model, key management, console token, hardening.
- `docs/decisions/` — ADRs. ADR-0001..0006 are pre-seeded for the scope decisions in §1.3.
- `docs/BUILD_STATUS.md` — maintained by the agent: phase status, stubbed components, unsigned artifacts, known gaps.
- Embedded help: `jrsctl help <command>` and `jrsctl <command> --explain`.

---

## 18. Exit codes

| Code | Meaning |
|---|---|
| 0 | success (override flags such as `--allow-unsupported` do not change the code; they are audited) |
| 1 | usage error |
| 2 | precheck/doctor/fingerprint failure (nothing mutated) |
| 3 | run failed, rolled back cleanly |
| 4 | run failed, rollback incomplete — manual action required (details printed) |
| 5 | cancelled |
| 6 | unsupported server/config (compat matrix), or a host outside ADR-0002 (not Windows or Linux on x86-64) |
| 7 | signature/verification failure |
| 8 | pending recovery required — run `jrsctl runs recover <runId>` |
| 9 | run lock held by another jrsctl process |

---

## 19. Assumptions and open questions

Assumptions the agent should proceed with unless overridden:

1. "Isolated mode" means outbound HTTP only to the `server.baseUrl` host.
2. The customer host has the JRS installation, its `buildomatic/`, database connectivity, and a JDK suitable for buildomatic (`vendor.javaHome`) already configured.
3. Vendor upgrade scripts for the target version are supplied by the customer as a package path; `jrsctl` does not download them.
4. Ed25519 via the JDK provider is acceptable for bundle signing; no HSM integration in v1.
5. Database backups before a `samedb` upgrade are the operator's responsibility and are confirmed by flag.
6. The JDBC driver jars shipped under the JRS `buildomatic/conf_source/db/` tree are acceptable for executing hotfix SQL.

Open questions for PM/engineering (record answers as ADRs):

- Q1: Minimum JRS version to support in v1 (spec assumes 7.1).
- Q2: Whether JBoss/WildFly or WebSphere deployments must be supported in v1 (spec assumes Tomcat only; abstraction allows extension).
- Q3: Whether the console should support multiple registered servers in v1 (spec assumes one server per `JRSCTL_HOME`).
- Q4: Publisher key custody and rotation process.
- Q5: Whether `js-ant` accepts a `default_master.properties` path outside the buildomatic directory. Until answered, §7.4 writes into the invoked buildomatic directory with snapshot/restore.
- Q6: Which JRS CE container image the `needs-jrs` suite uses (a `js-docker` build pushed to the private registry is the working assumption).

---

## 20. Definition of done (v1.0)

- All Phase 0–8 acceptance scripts pass on Windows and Linux CI.
- Portable-archive smoke tests pass on clean VMs with no pre-installed JDK.
- Recorded adapter contract suite (ADR-0011) green across every row of `compat/matrix.yaml`, and the `needs-jrs` live suite green against every image that exists.
- Zero findings from secret scan and dependency audit at High or above.
- Operator guide reviewed by support engineering.
- `docs/BUILD_STATUS.md` shows no stubbed components remaining except those explicitly deferred by ADR.
