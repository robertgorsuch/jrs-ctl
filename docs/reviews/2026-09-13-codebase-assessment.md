# jrsctl codebase assessment — 2026-09-13

Reviewed: `main` at `fd6526e` (v1.1.0 plus the console payload records and the five new console
pages). Method: six read-only reviews, one per risk area (engine and state store; hotfix,
snapshots and service control; upgrade, export/import and the JRS adapter; security; platform,
CLI and distribution; build, CI and documentation), each tracing every reported path end to
end, followed by an independent re-verification of every P0 and P1 against the code and, for
the upgrade findings, against the real JasperReports Server 10.0.0 buildomatic on this machine.
Items already recorded as fixed in `docs/BUILD_STATUS.md` or in the two 2026-09-10 reviews were
confirmed fixed and are not repeated. Full `scripts\mvn.cmd verify` and CI on both operating
systems are green at this commit.

Severity: **P0** blocks a release · **P1** fix before the next release · **P2** improvement.

## 1. Executive summary

The disciplines the September 10 reviews praised still hold and the fixes they asked for are
real: journal-before-event, failing-step-first compensation, WEB-INF stop-before-swap on both
operating systems, hash-verified snapshots, LIFO cascade, journal-driven SQL compensation,
argv-only process execution, a redacted journal, a console with a Host gate, launch codes and
a constant-time bearer check. Nothing in the engine, the security core, the platform layer or
the release path blocks a release.

Two areas do not hold to the standard of the rest:

- **The `newdb` upgrade path cannot run and, once it can, its rollback promise is wrong.**
  `VendorSteps` calls `js-upgrade-newdb` with no argument; the real wrapper refuses ("import
  file expected as input"). More seriously, spec §10.1 says the vendor's `newdb` "never
  modifies" the existing database; the vendor's own `upgrade-newdb.help` lists "Delete the
  existing, older jasperserver database" as a step. `newdb` is the default mode and has no
  confirmation gate. (U1, U2)
- **The hotfix rollback plan trusts less than the apply plan.** It ignores the manifest's
  `restart: required`, and when the run's bundle copy is gone it skips the SQL rollback with a
  warning and still records `ROLLED_BACK`, exit 0. (H1, H2)

Everything else is edge-case hardening, one Linux-only lock hazard worth a CI test (E1), three
build/CI items that make every Dependabot PR red or unsafe to merge (B1–B3), and documentation
that did not follow the console's contract change in `fd6526e` (B4).

## 2. Findings

### 2.1 Upgrade, export/import, JRS adapter

**U1 · P0 · `newdb` rollback promise is false at the spec level.**
`docs/spec.md:535`; `ops/upgrade/DefaultUpgradeOperations.java:60-62` (`NEWDB_WARNING`),
`:319-331`; `ops/upgrade/RestoreSteps.java:347`. The vendor's `buildomatic/bin/upgrade-newdb.help`
(10.0.0) states the script will "Delete the existing, older jasperserver database" and "Create
and initialize the new jasperserver database" — i.e. the database named in
`default_master.properties`, which jrsctl neither changes nor checks. Scenario: operator runs
`upgrade` (default `newdb`, no `--db-backup-confirmed`), smoke fails, `upgrade rollback --to-point
B` restores the old webapp against a recreated new-schema database — the state
`PointBIntegrity` calls "worse than not rolling back". The point-B `full-export.zip` is never
re-imported by any plan. Fix, one of: (a) `newdb` requires the target master properties to name a
database different from the current one, verified by `doctor` and refused otherwise, and the
warning says so; (b) `newdb` gets the same confirmation gate and "files only" rollback wording as
`samedb`; (c) the point-B rollback plan re-imports `full-export.zip` with the restored
buildomatic. Then correct spec §10.1 and record an ADR. Confirmed against the vendor script.

**U2 · P1 · `js-upgrade-newdb` is invoked with no arguments and cannot run.**
`ops/upgrade/VendorSteps.java:276` passes `List.of()`; `js-upgrade-newdb.bat` is
`do-js-upgrade.bat pro standard %*` and `bin/do-js-upgrade.bat:112-113` (`.sh:81`) fails with
"import file expected as input" for the `standard` strategy. The `js-ant upgrade-newdb` fallback
(`:286`) names a target that does not exist in 10.0.0. Every `newdb` upgrade fails at
`run-vendor-upgrade` (fails safe, exit 3). Pass the point-B full export path; add a test that
drives the real wrapper's argument contract with a fake `do-js-upgrade` that enforces it.
Confirmed.

**U3 · P1 · Import rollback re-imports the snapshot while the server-side import may still be
running.** `jrs/strategy/PollImport.java:112-116` returns `Recoverable` on `TimedOut` (2 h); a
non-transient `RestException` from a poll propagates and the runner treats a throw the same way;
`Runner.java:410-421` then compensates the import phase, whose anchor
(`ops/exim/RestoreFromPreImportSnapshot`) starts a second import (REST, or `js-import` after
stopping the service under a running task). Whichever finishes last wins; the run reports
"rolled back", exit 3. A still-in-progress task should end `Fatal` (exit 4 with "wait, then
verify") rather than trigger compensation. Confirmed.

**U4 · P1 · Hotfix reapply classification checks `replaces` targets in the wrong webapp.**
`ops/upgrade/HotfixReconciler.java:95-100` resolves `replaces` siblings next to the *installed*
target; an installed hotfix has already deleted them (`HotfixApplyPhaseSteps.java:241-243`), so
the hotfix is classified `SUPERSEDED` ("absent from the new webapp") and the new webapp ships the
unpatched jar. Resolve against the target webapp (or defer to execution); `HotfixReconcilerTest:84`
creates the sibling and so never models the real post-apply state. Confirmed.

**U5 · P2 · `ReconcileSteps.java:231-233` promises a point-B rollback that does not happen** (the
runner compensates only the reconcile phase). Wording.

**U6 · P2 · Point-B backup steps do not converge after a crash between the rename and the
`.sha256` write** (`BackupSteps.java:188-189`, `:369-370`; `PointB.java:484-486` then throws "no
.sha256 record" on every re-execution).

**U7 · P2 · Pre-import snapshot scope trusts the sidecar's URI list**
(`ops/exim/DefaultExportImportOperations.java:256-290`); referenced resources outside the subtree
(data sources, input controls) overwritten by `--update` are not restorable. Plausible.

### 2.2 Hotfix, snapshots, service control

**H1 · P1 · Rollback silently skips the SQL rollback when the run's bundle copy is gone.**
`ops/hotfix/DefaultHotfixOperations.java:284-308`: a missing or invalid
`runs/<installedRunId>/bundle/manifest.json` yields an empty `sql`, no `RunSqlRollback`, a
warning, and `RecordRolledBack` still marks the hotfix `ROLLED_BACK`, exit 0. Files restored,
schema change stays, state says rolled back. Refuse with exit 2 unless the stored manifest is
`irreversible` or SQL-free (the upgrade side already refuses the analogous half-gone point B).
Confirmed.

**H2 · P1 · Rollback ignores the manifest's `restart: required`.**
`ops/hotfix/RollbackSteps.java:63-65` stops the service only for WEB-INF/lib|classes paths; apply
also stops on `manifest.restart()` (`ApplyInput.java:38-40`). A hotfix that replaced
`WEB-INF/applicationContext-security.xml` or `apache-tomcat/lib/x.jar` with `restart: required`
is restored live; Tomcat keeps serving the hotfix's context until someone restarts it. The stored
manifest is already loaded at `:285`; consult it. Confirmed.

**H3 · P2 · `--allow-unsigned` also waives a present-but-failing signature.**
`HotfixVerifySteps.java:84-94`, `KeyRing.java:93-95`: "unknown key" and "trusted key, tampered
manifest" both surface as "matches no trusted key". At minimum distinguish them in the message
and audit; arguably a bad signature should never be waivable.

**H4 · P2 · A `record-installed` failure reports "rolled back" (exit 3) while the hotfix is fully
applied.** `Runner.java:410-418` compensates only the one-step record phase whose compensation is
a no-op; a later re-apply snapshots hotfixed bytes as "before". Force `rollbackAll` for the
record phase or give `RecordInstalled` a real compensation.

**H5 · P2 · systemd `inactive|failed` is taken as "JVM gone"** (`SystemdServiceController.java:47-53`),
unlike the script/manual kinds; with `KillMode=none` the JVM may still be shutting down.
Cross-check with `TomcatProcesses` after STOPPED. Plausible.

**H6 · P2 · Dependency inversion on rollback.** `RollbackChain.java:44-56` blocks only on shared
files; rolling back H1 leaves H2 (which `requires` H1) INSTALLED with an unmet requirement.

**H7 · P2 · Bundle payload is extracted with no size cap before the disk-space preflight**
(`HotfixBundle.java:96` caps only the manifest; `HotfixVerifySteps.java:76-78`).

### 2.3 Engine and state store

**E1 · P1 · On Linux, reading `runs.lock` through a second handle releases the live run's lock.**
`core/engine/RunLock.java:119-135` (`readHolder` → `Files.readString`), called by
`ops/doctor/LocalChecks.java:355` which the console runs on every dashboard refresh; also
`heldBy` (`:102-116`) closes its probe channel in the same JVM. Java `FileLock` on Linux is a
POSIX `fcntl` record lock, and the JDK documents that closing *any* channel on the file may
release every lock the process holds on it. Scenario: console executes a hotfix on Linux, a
health refresh runs doctor, the kernel drops the lock, a second shell's `jrsctl hotfix apply`
sees the lock free and the run pending, exits 8 telling the operator to `runs recover --resume`,
which re-executes the step the console is still executing. Windows (`LockFileEx`, per handle)
is unaffected. Fix: never open the lock file through a second handle in a process that holds it —
route `readHolder`/`heldBy` through the held `RunLock` when one exists, or keep one process-wide
channel per lock path. Add a Linux CI test (`RunLock.heldBy` currently has no test). Mechanism
documented; not reproduced here.

**E2 · P2 · The CLI never recomputes the fingerprint at execution (spec §6.2).**
`ops/RunService.java:127-129` passes `plan.fingerprint()` as the "recomputed" value, so the gate at
`Runner.java:71` cannot fire from the CLI; the console does rebuild and compare. A target jar
replaced between the plan prompt and the answer runs with stale `before_sha256` values. Confirmed.

**E3 · P2 · `runs recover` executes a rebuilt plan with no reconciliation against the journal**
(`RunsCommand` Recover, `ConsoleApi.java:319-333`, `Recovery.java:239-298`). If the rebuilt plan's
step ids differ (e.g. a customization registered in between adds the O2 stop/start wrapper),
`resumeIndex` lines up against the wrong ids. Refuse when any journaled step id is absent from the
rebuilt plan.

**E4 · P2 · Recovery rollback skips `ROLLBACK_FAILED` steps and still reports "rolled back"**
(`Runner.java:235-262` collects `SUCCEEDED` and mutating `RUNNING|FAILED` only; resume guards
at `:262`, rollback at `Recovery.java:293-298` does not). Narrow crash window; exit 3 while a
partial change stands.

**E5 · P2 · `recordRunStart` sits outside the journal-failure guard** (`Runner.java:74-78`): a
`StateStoreException` there escapes raw, `PlanExecutor` reports exit 4 "rollback incomplete"
with nothing mutated, and the claimed plan is consumed.

**E6 · P2 · A `state.db` newer than the binary opens silently** (`Migrations.java:546-550` never
refuses `current > max known`).

**E7 · P2 · Run rows are not terminal-once** (`Runs.java:836-853` unconditional `UPDATE`; the
`Journal` Javadoc promises otherwise).

**E8 · P2 · `doctor` judges the lock by pid text, not by trying it** (`LocalChecks.java:355-362`),
so after a crash it FAILs with "held by run X" and advises deleting `runs.lock`, inconsistent with
`RunLock.heldBy` and `ConsoleViews.lock`. Folds into the E1 fix.

Test gaps: `RunLock.heldBy`; recovery rollback with a `ROLLBACK_FAILED` transition;
`recordRunStart` failure containment; a second terminal state on a run row; a newer schema
version; recovery of a plan whose step ids differ from the journal.

### 2.4 Security

No release-blocking finding. Verified sound: default bind `127.0.0.1:7420`; Host gate before
auth on every `/api` path including SSE; no cookies, no CORS; `MessageDigest.isEqual` token
compare; token registered with the redactor before it is printed and deleted on shutdown;
`customizations register` confined to `installDir`/`tomcatDir`; fresh 96-bit IV per entry with
the entry name as AAD; runs, rollback and resume all pass through `RunManager` → `Runner`.

**S1 · P2 · Redaction runs after JSON serialisation, so a secret containing `"` or `\` survives.**
`app/JsonOut.java:45` (`redact(write(value))`), `app/console/ConsoleApi.java:582`,
`SupportBundle.java:157`; `core/redact/Redactor.java:148-156` registers raw, Base64 and
URL-encoded forms only, and Jackson escapes `"`→`\"`. A database password `ab"cd` echoed by a
driver error into a doctor detail reaches `--json`, `GET /api/doctor` and the support bundle
unmasked. Add a JSON-escaped form to `formsOf` (cheapest) or redact leaf strings before
`Json.write`; add the test. Confirmed.

**S2 · P2 · `--passphrase-file` skips the owner-only check `file:` secrets get.**
`core/secrets/PassphraseSource.java:55-68` versus `SecretResolver.java:51-70`. The master
secret for `secrets.enc` is the one credential without the permission gate. Confirmed.

**S3 · P2 · Console snapshot prune skips the run lock the CLI takes.**
`app/console/SnapshotViews.java:78-85` calls `RetentionPruner.prune` directly;
`RunsCommand.java:394-397` wraps the same call in a `RunLock`. A prune during another process's
upgrade computes protection before that run's row exists. Take the lock, 409 on
`LockHeldException`. Confirmed.

**S4 · P2 · Operator password (local auth mode) is materialised as `String` per request**
(`ConsoleAuth.java:139`; `ConsoleServer.java:318-319`). Split on the first `:` in the byte array;
zero `decoded`.

**S5 · P2 · `KeyRing.remove` does not validate the name** (`KeyRing.java:81-90`); `keys remove
../../foo` deletes `$JRSCTL_HOME/foo.pub`. Call `requireValidName`.

**S6 · P2 · `secrets.enc` and its temp file are owner-only on POSIX only**
(`EncryptedSecretStore.java:435-439`); `OwnerOnlyFiles` does the ACL dance for the token and
private keys. Hardening.

**S7 · P2 · picocli parse errors are printed unredacted** (`ExitCodes.java:240`) while the sibling
paths promise redaction.

### 2.5 Platform, CLI, distribution

No P0/P1. Verified: argv-only execution everywhere; stdout and stderr drained on their own
threads; every request has a finite timeout; OEM decoding on Windows; exit-code mapping for
2/4/5/6/9; `--json` never prompts; `--non-interactive` without `--yes` fails closed; one home
resolution shared by CLI and console; launchers quote every path; tar.gz sets 0755 on the
runtime binaries; the jlink list carries `java.sql`, `jdk.crypto.ec`, `jdk.zipfs`, `jdk.charsets`,
`java.naming`, `java.net.http`.

**P1 · P2 · Browser launch can SIGKILL the operator's browser on Linux.**
`app/ConsoleCommand.java:224-246` runs `xdg-open` under a 15 s kill-on-timeout runner
(`DefaultProcessRunner.java:117-119` `destroyForcibly`s descendants). In xdg-open's generic
fallback (no recognised desktop, `ssh -X`, WSL without wslu) the browser runs in the foreground.
Start the launcher without waiting and never destroy its descendants.

**P2 · P2 · `init --json --non-interactive` exits 0 where spec §12.0 says 2**
(`InitCommand.java:58-79` versus `:90-97` and `PlanExecutor.java:117-122`). Confirmed.

**P3 · P2 · Script service controllers wait out the whole timeout when the script never ran**
(`ScriptServiceController.java:80,95` discard the `invoke()` result); `PollingServiceController`
fixed this for `sc.exe`/`systemctl` (review 1.12) but not the script kinds. Confirmed.

**P4 · P2 · `init` picks any service containing "tomcat"** (`InitOperation.java:377-396`), with
no cross-check against `layout.tomcatDir()`. On a two-Tomcat host a hotfix stops the wrong one.

**P5 · P2 · `bin/jrsctl.cmd` Ctrl-C: cmd.exe's "Terminate batch job (Y/N)?" sits between the
JVM's exit 5 and the caller.** Plausible.

**P6 · P2 · `chcp.com` probe reads to EOF before its timed wait** (`WindowsCodePage.java:84-92`).

**P7 · P2 · Read-only `doctor` writes probe files into `WEB-INF/lib` and `WEB-INF/classes` of a
live server** (`LocalChecks.java:246` → `DefaultFileOps.java:166-178`). With `reloadable="true"`
Tomcat reloads the context; also contrary to the read-only rule (§0). Probe a sibling outside the
webapp or check ACL/POSIX bits.

### 2.6 Build, CI, tests, documentation

**B1 · P1 · surefire 3.6.0 (Dependabot #6) silently disables `excludedGroups`.**
Run 34708638280: `LiveJrsContainerTest` (`needs-jrs`) was discovered and executed on ubuntu
(410 s trying to pull `registry.example.invalid/...`); under 3.2.5 it is not. Upstream regression
(apache/maven-surefire#3468). Close #6, add a Dependabot `ignore` for `surefire.version`, and add
a canary in `Phase0SkeletonTest` asserting no `needs-*` class appears in `*/target/surefire-reports`.
Confirmed.

**B2 · P1 · The OWASP audit fails on every Dependabot PR** (`ci.yml:99-106`): Dependabot runs read
the Dependabot secret set, not repository secrets. Add `NVD_API_KEY` under Settings → Secrets →
Dependabot; fallback `if: github.actor != 'dependabot[bot]'` on the job. Confirmed.

**B3 · P1 · `dependabot.yml` ignores none of what the repo has decided**: no `ignore` for
`net.jqwik:jqwik` (PR #8; commit 1dd451e explains why 1.10.x is refused), no ignore or group for
`version-update:semver-major` (PRs #4 spotless 3.x, #5 semver4j 6.x, which 1dd451e says are "their
own change with their own gate"). Confirmed.

**B4 · P1 · The console contract grew in `fd6526e` without the spec, changelog or status
following.** Spec §13.1 stops at `GET /api/doctor`; `ConsoleApi.java:102-113` now serves
`/api/smoke` (GET/POST), `/api/customizations{,/diff,/register,/unregister}`,
`/api/snapshots{,/prune}`, `/api/config`, `/api/selfcheck`, `/api/keys`, `/api/repository/tree`.
`docs/spec-changelog.md` has no entry after 2026-09-10; `docs/BUILD_STATUS.md:22-23` still says
"26 JSON Schemas" (45) and "eight api-* schemas" (15); `docs/capabilities.md:39-42` lists five
pages; `web/README.md` still says "the six views" and "endpoints are exactly spec §13.1 plus
`/api/hotfixes` and `resume`". CLAUDE.md names the spec the single source of truth. Confirmed.

**B5 · P2 · Schema coverage is checked against the map, not the router**: `POST /api/runs/{id}/cancel`
and `POST /api/auth/launch` have no schema and `ConsoleSchemaTest.java:114` iterates
`JsonSchemas.endpoints()`, so an unmapped route is never noticed.

**B6 · P2 · `main` still says `1.0.0-SNAPSHOT` after v1.1.0** (`pom.xml:9`). Release builds are
correct (`versions:set` from the tag feeds `jrsctl-version.properties`), but every dev build,
archive name and support bundle claims an older version. Bump to `1.2.0-SNAPSHOT` after each tag.

**B7 · P2 · `jrs` opts out of the coverage floor with no justification** (root `pom.xml:258`
requires one; `jrs/pom.xml` sets nothing and inherits 0.00).

**B8 · P2 · The signed SBOM comes from whichever package leg uploads last** (`ci.yml:194`,
`:221-225` `merge-multiple: true`). Upload from one leg or suffix the platform.

**B9 · P2 · The NVD cache is saved on every run, including runs that never opened it**
(`ci.yml:116-120`, `if: always()`); gate on the audit step's outcome.

**B10 · P2 · ci.yml builds with the runner's `mvn`, publish with `./mvnw`**; the wrapper's
SHA-verified Maven never protects the release build.

**B11 · P2 · Test hygiene (counts).** 0 `@Disabled`; tags consistent; no acceptance import of
`app`; every test asserts. 14 `Thread.sleep` in 6 files (fixed 3 s teardown at
`Phase8CrashRecoveryTest.java:150`; 200–500 ms waits in Phases 5/6/8) are CI-flake candidates.
130 unit tests are `should_…` without `_when_`.

**B12 · P2 · Doc nits.** `BUILD_STATUS.md:24` omits `recovery-runbook.md` from the embedded list;
`CONTRIBUTING.md` "Adapter changes are tested…" has two colliding parentheticals.

## 3. Already known and still open

Recorded in `docs/BUILD_STATUS.md` or the evening assessment's Appendix B and confirmed still
open: `--storepass` on argv and form-login password as `String`; pre-auth token in the URL query
(`RestClient.resolve`); `StartImport` retry after a 502 can start a second server-side import;
vendor scripts inherit the full environment; `PointB.restoreDir` stages under `webapps/`;
`planUpgrade` writes the re-pack zip at plan time; `restLoginExists()` sends a credential-less
login; Linux lock detection is blind for a Tomcat owned by another account (WARN, by design);
ctlscript/catalina controllers wait out a failed script; `runs/<runId>/` directories are never
pruned (which is what keeps H1 rare today); the `integration` job is vacuous (ADR-0007 deferred);
a home created on Windows inherits the `%ProgramData%` ACL; `CODE_OF_CONDUCT.md` contact.

## 4. Recommended order

1. **U1 + U2 together, with an ADR** — decide the `newdb` contract (gate it like `samedb`, or
   verify a distinct target database, or re-import at rollback), fix the wrapper invocation, and
   add a test that drives a fake `do-js-upgrade` enforcing the real argument contract. Until
   this lands, `upgrade --mode newdb` should be documented as unavailable.
2. **H1 + H2** — the rollback plan reads the stored manifest it already loads: refuse when SQL
   rollback is impossible; stop the service when `restart: required`.
3. **E1 (+ E8)** — single-handle lock reads; Linux CI test for `heldBy` under a held lock.
4. **B1–B3** — close #6 and #8, add the Dependabot ignores, add the Dependabot `NVD_API_KEY`
   secret, add the surefire canary. Then #1 and #4 can be rebased and judged.
5. **B4** — spec §13.1, spec-changelog, BUILD_STATUS, capabilities.md and web/README for the
   seven new console endpoints, before the next tag.
6. **U3, U4** before advertising import rollback and `--reapply-hotfixes`.
7. **S1–S3, E2, P2, B6** — each a small contained change.
8. The remaining P2s as hardening, in any order.
