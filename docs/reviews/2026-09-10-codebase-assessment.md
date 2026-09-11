# jrsctl codebase assessment — 2026-09-10 (evening)

Scope: all six modules at HEAD `d1c29ec` (tag `v1.0.0` sits two commits earlier on `1ba39d9`). This assessment builds on the morning's `2026-09-10-codebase-review.md`: it re-verifies every one of that review's 50 findings at HEAD, audits the quality of the nine fixes recorded in `BUILD_STATUS.md`, scrutinises the two large commits that landed afterwards (`b4f28b2`, `1ba39d9`), and adds 60 new findings. Five parallel module reviews (core, jrs, ops, app, build/CI/docs) plus a repository-wide invariant sweep were run; every finding quoted below was confirmed in source, and the P0s were re-checked by hand. Nothing was modified.

Priority key: **P0** = can lose or corrupt server state, or invalidates a release claim; **P1** = realistic failure handled badly; **P2** = hardening, quality, ergonomics.

---

## 1. Executive summary

**The engineering is unusually disciplined for a project at this stage.** Sealed types with exhaustive switches, an idempotency test that classpath-scans every `Step`, crash-injection acceptance tests that `kill -9` the real jar, WireMock fixtures recorded from five real server versions, zero `TODO`s, zero `@Disabled` tests, one `@SuppressWarnings` in 37k lines of Java. The module graph is acyclic and matches the spec exactly. The morning review's nine server-safety and rollback fixes are real fixes, not patches: `Durability`, `PriorState`, `PointBIntegrity`, `SqlProgress` and the `Archives` symlink work are all well-shaped and mostly well-tested.

**But the product cannot yet keep its central promise.** The engine never compensates the step that fails (prior finding 1.9, still open), so a hotfix that dies half-way through `atomic-swap` or an upgrade whose vendor script exits non-zero leaves partial work in `WEB-INF/lib`, restarts Tomcat on top of it, and records the run as *rolled back* with exit 3. Every "the run is rolled back from the snapshot" message in `ops` is currently false for the failing step. This is the single most important defect in the codebase and it is fixable in one place.

**And the release is not real.** `.github/workflows/ci.yml` has been invalid YAML since the phase 7 commit, so **no CI job has ever executed**; all ten runs on GitHub show `failure` in 0 seconds. The `v1.0.0` GitHub release was cut before the tag was pushed, from a locally-built `1.0.0-SNAPSHOT` archive renamed by hand, with no Linux artefact, no signatures, and a runtime SBOM that carries two High-severity jackson-databind advisories. None of the six spec §20 release gates is met.

| Area | Grade | One-line verdict |
|---|---|---|
| Architecture and module boundaries | **A−** | Clean, acyclic, spec-faithful; one package cycle (`engine`↔`state`) and the Step abstraction is being stretched in `ops`. |
| Server-safety of mutating plans | **C+** | Nine real fixes landed, but the failing-step gap (1.9) undoes them in the failure case; plus new P1s in SQL delimiter handling and customisation reapply. |
| Adapter and compatibility | **B−** | ADR-0004 is holding; HTTP resilience, re-login, stall detection and probe semantics are all still open; auth failure silently picks the service-stopping path. |
| CLI, console, security | **B** | Sound token model, redaction, no-innerHTML UI; but the launch-code exchange is a race not a fix, `mock.js` ships in production, Ctrl-C and `--json` purity still wrong. |
| Test strategy | **B+** | 727 tests, excellent idempotency and crash coverage; no fault injection, no Runner-path compensation test, one masked assertion. |
| Build, CI, release | **D** | CI dead since phase 7; v1.0.0 assets hand-made from a SNAPSHOT; licence contradiction; dependency audit fails spec §20. |
| Documentation | **B+** | Spec, guide, README and exit codes agree; stale Javadoc after the fixes; ADR-0007 "Accepted" but unimplemented. |

---

## 2. Where the morning review stands at HEAD

| Status | Count | Findings |
|---|---|---|
| Fixed | 11 | 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 2.1, 1.17 (with a regression, see P1-C1), 4.6 |
| Partially fixed | 7 | 2.3 (CSRF header yes, re-login no), 4.1 (launch code narrows the window, does not close it), 4.2 (POSIX correct, Windows not), 4.7 (per-session writer exists; queue unbounded, heartbeat shared), 5.1 (logback/Jetty bumped; jackson, sqlite, lang3 not), 5.5 (concurrency, wrapper, JaCoCo report; no thresholds, no SHA pins, no timeouts), 5.6 (CONTRIBUTING added; runbook/matrix/air-gap absent) |
| Open | 32 | 1.9–1.16, 1.18, 1.19, 2.2, 2.4–2.9, 3.1–3.7, 4.3–4.5, 4.8, 4.9, 5.2–5.4 |

The prior review was wrong on one point: reproducible-build deferral *is* recorded, in ADR-0003.

### Fix-quality audit (the nine recorded fixes)

- **1.1 Windows lock probe** — correct in the interrupted-rename case, but the guard hard link is created *inside* `WEB-INF/lib` and its deletion is refused while Tomcat holds the jar (NTFS share modes apply to the file, not the name). A read-only preflight can therefore leave `*.jrsctl-lockguard` beside a live jar (P1, core B-3). Also reports permission problems as "locked" (P2).
- **1.2 Durability** — well built. One publish rename bypasses `Durability.move` (`SnapshotStore.java:116`), and `syncDirectory` swallows POSIX failures, which is the case it exists for (P2 ×2).
- **1.3 Attempt marker** — correct; the *done* marker is not forced (P2). Messaging says "rolled back to point B", which is false while 1.9 is open.
- **1.4 PriorState** — correct and tested. `planState()` fallback untested.
- **1.5 PointBIntegrity** — correct; distinguishes missing/pruned/drifted/legitimately-empty. Manifest-hash drift untested.
- **1.6 Archives** — symlinks and ownership handled; but names containing `..` or `:` anywhere are archived fine and refused on extract, so some Linux backups are unrestorable (P1, ops C-4); link targets are not validated (P2).
- **1.7 DefaultHome** — correct and tested; two Javadocs still describe the old rule.
- **1.8 SqlProgress + SqlScript** — journaling correct; the reader is good; but `DefaultJdbcConnector.execute` re-splits every statement with the default delimiter, so the new `-- jrsctl:delimiter` directive is defeated end to end (P1, ops C-2).
- **2.1 js-import banner parsing** — works; substring match on every line means a resource literally named "build failed" fails an otherwise successful import and triggers a mutating compensation (P2).

---

## 3. Critical findings (P0)

**P0-1 · The failing step is never compensated; the service is restarted on partial work.**
`core/engine/Runner.java:343` builds compensation targets from `succeededMutating` only, and `rollbackRecorded` (`:171-179`) includes RUNNING but not FAILED steps. Consequences in `ops`: `AtomicSwap.execute` (`ApplySteps.java:673-716`) fails after k of n renames, the hotfix `StopService` is compensated (service **started** on a half-swapped `WEB-INF/lib`), and the run is journaled ROLLED_BACK with exit 3. Same shape for `RunVendorUpgrade` (`VendorSteps.java:288-297`): `WriteMasterProperties` is undone, Tomcat is started on a half-migrated webapp, and `runs recover --rollback` cannot help because the run is terminal. `ApplySql` and `RestoreSnapshot` have the same gap. No test drives a mid-step failure through `Runner.run` and asserts file, database and service state afterwards.
*Fix:* in `Runner.failed`, compensate the failing mutating step first (it has journaled FAILED, so `mutated` is true), then the succeeded ones; add `RunnerTest.should_compensate_failing_step_when_mutating_step_fails_midway` and an ops test through the Runner for `atomic-swap`, `apply-sql` and `run-vendor-upgrade`. Until then, `AtomicSwap`/`RunVendorUpgrade`/`ApplySql` should call their own `compensate` on every failure path before returning `Recoverable` (ops-local mitigation).

**P0-2 · CI has never run; the workflow file is invalid YAML.**
`.github/workflows/ci.yml:162`: `- name: Sign archives, checksums and SBOM (spec §0 rule 11: the key exists only here)` is an unquoted scalar containing `: `. Confirmed with a YAML parser ("mapping values are not allowed here", line 162 col 65) and on GitHub: all ten runs are `failure` with `jobs: []`, including the tag push. The file has been broken since `eb092f0` (phase 7); commits `b4f28b2` ("security hardening", touched ci.yml) and `1ba39d9` ("optimize CI concurrency", touched ci.yml) both edited it without noticing. `Phase0SkeletonTest` only asserts the file exists.
*Fix:* quote the name; make `Phase0SkeletonTest` parse every workflow with SnakeYAML (already on the classpath); add `actionlint` to the pre-commit hook.

**P0-3 · The v1.0.0 release does not meet the definition of done.**
The release (created 22:32Z) predates the tag push (22:46Z). Its Windows zip is byte-identical to the local `dist/target/jrsctl-1.0.0-SNAPSHOT-windows-x64.zip`; the SBOM says `1.0.0-SNAPSHOT`; `--version` inside the archive prints `1.0.0-SNAPSHOT`; there is no Linux tar.gz, no `.sig`, and the pom at the tag is still `1.0.0-SNAPSHOT`. Against spec §20: acceptance on both OSes in CI (not met, CI dead); portable smoke on clean VMs (not met); `needs-jrs` suite across the matrix (not met, Q6 unresolved); zero High from secret scan and dependency audit (not met: 30 open Dependabot alerts, two High on jackson-databind 2.17.2 in the shipped SBOM); support review of the guide (no evidence); no stubs except ADR-deferred (publisher key still empty).
*Fix:* mark v1.0.0 a pre-release or delete it; add a release profile with `enforcer:requireReleaseVersion`; let the `release` CI job (once CI works) build, sign and upload; re-cut only when the gates pass.

---

## 4. High findings (P1), by area

### Core

- **C1 · Secret-store salt change breaks every pre-existing Linux store.** The 1.17 fix switched the KDF salt from DNS hostname to `/etc/machine-id` (`EncryptedSecretStore.java:387-415`) with no version bump (`VERSION = 1`) and no legacy fallback, so `unlock` fails deterministically with a misleading "wrong passphrase" for any store created before `b4f28b2`. *Fix:* bump to v2, record the id source, retry with the legacy hostname on v1 and re-encrypt in place.
- **C2 · Redaction of run events is per-sink convention, not an engine guarantee.** `RedactingEventSink` has zero production callers; `Runner` accepts any `EventSink`, and `PlanExecutor`, `RunManager` and `ConsoleApi` each redact independently. `affectedUris` are never redacted. *Fix:* `Runner` wraps its sink with the redactor; redact URI userinfo/query.
- **C3 · Guard link left in `WEB-INF/lib` on a running server** (see fix-quality 1.1). *Fix:* place the guard outside the webapp on the same volume, or skip the guard when the sibling listing fails while the handle is held.
- Still open from the morning: 1.10 (journal failure escapes with no terminal state), 1.11 (cancellation invisible in sleeps and service polls: zero `checkpoint()` call sites in core), 1.14 (sub-second timestamp ordering), 1.15 (raw-string Windows paths), 1.18 (no `quick_check`; `RunLock.close`/`StateStore.close` throw after SUCCEEDED), 3.1 (macOS/ARM never refused), 3.3, 3.4.

### Ops

- **O1 · `-- jrsctl:delimiter` is defeated end to end.** `SqlRunner.java:53-54` splits, then `DefaultJdbcConnector.execute:164-178` splits each statement again with `;`. `CREATE PROCEDURE … BEGIN a; b; END;` is sent as three statements. Only `SqlScriptTest` exercises the directive, never through the runner. *Fix:* add a no-split `executeStatement`, mirror in `FakeJdbcConnector`, add a runner-path test.
- **O2 · Customisation reapply writes under `WEB-INF/classes` with Tomcat running.** Upgrade plan order (`DefaultUpgradeOperations.java:153-165`) is stop → vendor → start → wait → hotfix reapply → customisation reapply; `ReconcileSteps.copyOver` then `atomicReplace`s files that typically live in `WEB-INF/classes`. Violates CLAUDE.md's stop-first invariant; will hit a lock on Windows and silently edit a live webapp on Linux. *Fix:* decide at plan time whether any registered target requires a stop and wrap the step, or move it before `start-service`.
- **O3 · `Archives` create/extract asymmetry** (fix-quality 1.6): validate names at create time; narrow the extract check to `..` segments and absolute roots; reject absolute and out-of-tree link targets.
- **O4 · Manifest `replaces` can name the target itself or collide case-insensitively.** `ManifestValidator.java:106-110` checks only plain file names; `AtomicSwap` deletes siblings after landing the target, so `replaces: ["foo.jar"]` on `foo.jar` (or `Foo.jar` on Windows) deletes the freshly installed payload and `RecordInstalled` records it as installed. No `AtomicSwap.postcheck`. *Fix:* semantic checks using `HotfixPaths.key()` (currently dead code) and a post-swap re-hash.
- Still open: 1.12 (service control ignores access-denied), 1.13 (hotfix `StopService` compensation restarts a service the operator had stopped; the marker fix exists only in the upgrade copy), 1.16 (disk-space check on the wrong volume), 1.19 (`StageFiles extends ReadOnly`, staging never cleaned; read-only attribute on delete; pre-import zips never pruned), 3.2 (Linux init assumes systemd).

### Adapter (jrs)

- **J1 · Authentication failure silently selects the service-stopping strategy.** 401/403 on a probe counts as "capability absent" (`RestJrsAdapter.java:214-219`), and `Strategies.select` (`:97-105`) falls back to `VendorCliStrategy` on *any* `RuntimeException` from `capabilities()`. A password typo turns a zero-downtime REST import into a service stop plus `js-import`. *Fix:* treat 401/403 and login failure as hard errors; fall back only on 404/405/501/unreachable.
- **J2 · REST import swaps the keystore under a running server.** `RestStrategy` inserts `ImportSourceKeystore` (runs `js-import --keystore`) with no stop/start; the running JVM keeps the old keys. *Fix:* force the vendor strategy when `sourceKeystore` is present, or wrap in stop/start.
- Still open: 2.2 (transient HTTP errors are fatal and trigger compensation, i.e. a snapshot re-import while the server-side import is still running), 2.4 (download/upload stalls uncancellable), 2.5, 2.6, 2.7 (404 counts as "present", asserted by a test), 2.8/N12 (`.bat` re-parses arguments; `&`, `|`, `%` in a URI break or execute), 2.9.

### App and console

- **A1 · `/api/auth/launch` is unauthenticated, Host-check exempt and unthrottled** (`ConsoleServer.java:163-164`). It is the one endpoint that returns the bearer token, and the launch code is still an argv secret on Linux, so 4.1 shrank the window to a race rather than closing it. *Fix:* keep the Host check, 10 s TTL, audit rejected exchanges.
- **A2 · `mock.js`, `web/README.md` and `?mock=1` ship in the production jar.** `app.js:342` honours the query parameter and offers "Use sample data" when the backend is unreachable, so an operator can be shown a fully fake successful run at the real console URL. *Fix:* exclude from the shaded jar; gate `enableMock()` on `file:` origin; assert absence in `WebAssetsTest`.
- Still open: 4.3 (Ctrl-C never exits 5; unmapped exceptions exit 4 unlogged, even in read-only commands), 4.4 (`--non-interactive` acts as `--yes`; piped stdout forces exit 2 for an attended operator), 4.5 (logback INFO reaches stderr in `--json`; the test that asserts silence captures picocli's writers, not `System.err`, so it cannot fail), 4.8, 4.9, 3.5, 3.7.

### Build, CI, release

- **B1 · Dependency audit fails spec §20.** Runtime SBOM ships jackson-databind 2.17.2 (2 High), commons-lang3 3.14.0, logback-core 1.5.17, sqlite-jdbc 3.48.0.0; 11 Dependabot PRs sit unmerged.
- **B2 · Parallel forks (`forkCount=1C`) enabled without evidence.** BUILD_STATUS already records Windows FS flakiness under concurrency; the same commit bumped a process-runner test timeout 1→3 s; `jrs` `FakePlatform.defaultHome()` returns a fixed shared temp path. No post-change timing recorded.
- **B3 · Git hooks are dead.** `core.hooksPath` is only documented, never set; `.githooks/pre-commit` is committed `100644`; it runs Spotless over the whole reactor on every commit.
- **B4 · `needs-docker` gate is vacuous** (0 tagged tests) and `release` depends on it; `nightly.yml` referenced but absent.
- **B5 · Licence contradiction.** `LICENSE` is GPL-3.0; `README.md:272-273` says "All rights reserved. Licensed under the Apache License, Version 2.0." GPLv3 is an unusual choice for an Actian-branded tool bundling OpenJDK. Needs a legal decision and an ADR.
- **B6 · `tmp-real-home/config.yaml` is tracked** in a public repository (real host layout; password references only, checked). `.claude/settings.json` (personal plugin enablement) is tracked too.

---

## 5. Architecture and maintainability

**What is right.** The six-module split matches spec §4 and the import graph is exactly `app → ops → jrs → core` with `dist` standalone. Sealed `Plan`/`Step`/`StepFailure`/`RunOutcome` and no `default` over any sealed type (the eleven `default ->` hits are all over strings or enums). `Platform → FileOps/ProcessRunner/ServiceController` with `Platforms.forTesting` is a good seam; `DefaultProcessRunner` is list-args only with pumps and tree-kill. Secrets are `char[]` throughout core. Every public top-level type but two has an invariant paragraph. Plans are built by `Default*Operations` from immutable inputs and executed by steps holding a runtime record: clean plan/execute separation (one exception: `planUpgrade` writes a re-pack zip at plan time).

**What to change.**

1. **`engine` ↔ `state` package cycle.** `Runner` imports `StateStore`/`RunLock`; `Recovery` in `state` imports `Runner`. Introduce a small `Journal` interface in `engine` implemented by `StateStore`, move `Recovery` into `engine`. This also makes 1.10 testable with a throwing fake.
2. **Cancellation is half-plumbed.** `Sleeper.sleep(Duration)` and `PollingServiceController.await` cannot see the token. Add the token parameter; 1.11 falls out.
3. **`StateStore` (826 lines)** is a flat DAO, repetitive rather than complex. Split by aggregate over a shared `SqliteSession` (`RunJournal`, `HotfixRegistry`, `SnapshotIndex`, `AuditLog`) and, while doing so, introduce one `Timestamps` codec and one `PathKeys.canonical(Path, OsFamily)`; that fixes 1.14 and 1.15 in one place each. Make `executeUpdate(String)` package-private and give `ops` typed `deleteHotfix`/`deleteSnapshotsOfRun` (today `ApplySteps.java:943` and `DefaultCustomizationOperations.java:128` concatenate ids into SQL).
4. **The Step abstraction is being fought in `ops`.** `TakeSnapshot` and `BackupSteps.Additive` declare `mutating()==false` while writing; `RestoreFromPreImportSnapshot` declares `mutating()==true` while writing nothing (as a phase anchor); `PlanHotfixReapply.mutating()` depends on a constructor arg; `EmbeddedStep` and `Rephased` are two decorators for id/phase override. Either add `Step.kind(): READ_ONLY | ADDITIVE | MUTATING | ANCHOR` in core, or document the idiom once. Give `Step.compensate` a default for non-mutating steps so the `ReadOnly` base class can go.
5. **Duplication that has already caused drift.** `ops/hotfix/ServiceSteps` vs `ops/upgrade/UpgradeServiceSteps` are the same three steps; only the upgrade copy received the 1.13 marker fix. Also duplicated: `Failures` ×2, `HotfixRuntime`/`UpgradeRuntime`, `deleteRecursively` ×4, `configHash` ×3, log helpers ×3, `NonClosingOutputStream` ×2, `stripTrailingSlash` ×2, `StartExport`/`StartImport` and `PollExport`/`PollImport` (~60 % shared), the `ExportRequest → flags` mapping ×2, `SHUTDOWN_GRACE` ×2, the writability probe ×2, rename-retry ×2, OS detection ×2.
6. **Split the god files.** `ApplySteps` (1017 lines, nine nested steps) by phase, mirroring `ops/upgrade`; `Archives` (502) into a sealed `ArchiveFormat { Tar, Zip }`; `DefaultHotfixOperations` (491) by extracting `RollbackChain` and `BundleWorkspace`; `ConsoleViews` (670) into `RunViews`/`ServerViews`/`HotfixViews` returning records instead of `Map<String,Object>` so the JSON schemas cannot drift.
7. **Business logic in `app`.** `PlanRegistry` (rebuilding plans from stored args) and `RunService` (claim/lock/runner/recovery glue) are engine concerns both CLI and console need; move to `ops`. `RunsCommand` opens a `RunLock` itself for `prune` and several commands read `StateStore` directly. A `Command<T>` base (open bootstrap, map `--json`, translate exceptions) would remove roughly a third of 3,000 command lines and make 4.3/4.5 fixable once.
8. **"All I/O through Platform" is honoured as "through `core.platform` or `java.nio.file.Files`."** `Durability`, `SnapshotStore`, `EncryptedSecretStore`, `RunLock`, `StateStore`, and most of `jrs` call `Files` directly. Either widen `FileOps` or record the exemption in an ADR; today the rule is aspirational.
9. **Capability provenance.** `capabilities()` conflates probed and matrix-assumed capabilities; callers cannot tell. Return provenance (`PROBED`/`ASSUMED`) so strategy selection and `doctor` can reason (also needed for J1). Unknown capability names in `matrix.yaml` are silently dropped; add a load-time check. `ExportImportStrategy` is documented as sealed but is an open interface in another package with a manual `Kind` enum; move the two implementations or amend ADR-0004.

---

## 6. Test strategy

| Module | Test files | Tests | Notes |
|---|---|---|---|
| core | 36 | 218 | 2 jqwik properties (`Redactor`); `WindowsFileOps` has 3 tests, `LinuxFileOps.lockHolder` none |
| jrs | 28 | 130 | WireMock + 10 recorded `serverInfo` fixtures; no fault/delay usage |
| ops | 42 | 227 | `IdempotencyCoverageTest` scans all 58 Steps; SQL compensation tested only by calling `compensate` directly |
| app | 20 | 112 | `JsonOutputSchemaTest` table-driven; `ConsoleServerTest` hand-written, one server per test |
| acceptance | 13 | 50 | Against the shaded jar; real `taskkill`/`kill -9`; `verb_phrase` naming (undocumented deviation from spec §15) |

Strengths: fakes over mocks (16 `Fake*`/`Stub*`, Mockito imported in two files); all sockets on dynamic ports; `@TempDir` in 76 files; no retries, no `@Disabled`, no `@RepeatedTest`; 77 % of test names follow `should_x_when_y`.

Gaps, in priority order:

1. **No Runner-path failure test for any mutating ops step** (would have exposed P0-1).
2. **No fault injection anywhere**: no `FailingFileOps` decorator, no WireMock `Fault`/`withFixedDelay`, no second-JVM SQLite writer, no clock-skew case (5.4).
3. **One masked assertion**: `--json` stderr silence captures picocli's writers, not `System.err`.
4. **Windows ACL semantics** (owner-only file created-empty-then-written, inheritance disabled) untested; the POSIX branch runs on Linux CI, which does not exist.
5. `SqlScript` directive through the runner; `Archives` zip-slip, `..`-segment round-trip, out-of-tree link target; `PointBIntegrity` hash drift; `ManifestValidator` self-replace and case collisions; `ServiceSteps` compensation from a stopped service; retention protecting a failed upgrade's point B; `RestJrsAdapter` 401/403 on probes and FORM login rejection; `VendorTools` banner false positives; `EncryptedSecretStore` legacy salt.
6. Mockito is declared in four modules and used in two files; jqwik in one. Drop the unused declarations.
7. Phase tags are no longer 1:1 with features after phase 8; consider feature tags with `-Dphase` as an alias.

---

## 7. Recommendations, in order

### Now (before anything else is merged)

1. Fix `ci.yml` line 162 and add a workflow-parse assertion to `Phase0SkeletonTest`. Watch one green run on both OSes. (P0-2)
2. Mark `v1.0.0` as a pre-release or delete it; note in BUILD_STATUS that the release gates were not met. (P0-3)
3. Fix `Runner.failed` to compensate the failing mutating step, with a Runner test and one ops test each for `atomic-swap`, `apply-sql`, `run-vendor-upgrade`. (P0-1)
4. Secret-store v2 with legacy-salt fallback before any Linux operator upgrades the binary. (C1)
5. Fix the SQL delimiter re-split (O1) and the customisation-reapply service handling (O2); both are small.
6. Auth failure must not select the vendor strategy (J1); keystore import under REST must stop the service (J2).
7. Exclude `mock.js` from the jar and gate `?mock=1` (A2); Host-check `/api/auth/launch` (A1).

### Before re-cutting 1.0

8. Merge the Dependabot PRs (jackson 2.18+, commons-compress ≥1.27 with lang3 3.18, logback 1.5.19, sqlite-jdbc ≥3.50.2); run OWASP locally once as a baseline; make `NVD_API_KEY` required in CI.
9. Release profile: `enforcer:requireReleaseVersion`, `outputTimestamp` for jar/zip, SHA-pinned actions, `timeout-minutes`, wrapper `distributionSha256Sum`; let the CI `release` job produce and sign all artefacts.
10. Resolve the licence (GPL-3.0 vs Apache-2.0 vs proprietary) with an ADR; align README, dist README, third-party notice, CONTRIBUTING and the code-of-conduct contact.
11. Either add the two `needs-docker` tests ADR-0007 promises or drop `integration` from `release.needs` and mark ADR-0007 Deferred. Rename or record the recorded-fixture harness (5.3) so the adapter suite can run per matrix row without Docker.
12. Engine semantics: 1.10, 1.11 (token into `Sleeper` and service polls), 1.18 (`quick_check`; never throw from `close()`), then CLI exit/confirm: 4.3, 4.4, 4.5 (with a real `System.err` assertion).
13. Untrack `tmp-real-home/config.yaml` and `.claude/settings.json`; set `core.hooksPath` from `scripts/mvn.*`; make the hook executable and staged-files-only.
14. Revert `forkCount` to 1 for `app` (keep `1C` for core/jrs/ops), run `verify` three times on Windows, and record the timing before trusting parallel forks.

### Next quarter

15. Unify `ServiceSteps`/`UpgradeServiceSteps` (fixes 1.13 in both places), then the other duplication pairs listed in §5.5.
16. Split `StateStore` by aggregate with a single timestamp codec and canonical path key (fixes 1.14, 1.15); break the `engine`↔`state` cycle with a `Journal` interface.
17. Split `ApplySteps`, `Archives`, `DefaultHotfixOperations`, `ConsoleViews`; move `PlanRegistry`/`RunService` to `ops`; introduce a `Command<T>` base in `app`.
18. Fault-injection test layer (`FailingFileOps`, WireMock faults, second-JVM writer, `ManualClock` backwards jumps) and a JaCoCo `check` at 80 % line on `core`/`ops`.
19. Portability refusals (3.1, 3.2, 3.3, 3.4), then console hardening (4.7, 4.8, 4.9), then Windows console encoding (3.5).
20. HTTP resilience in the adapter (2.2, 2.4, 2.9) with capability provenance and an environment allowlist for vendor scripts.

---

## Appendix A. Full prior-finding status at HEAD

| id | title | status |
|---|---|---|
| 1.1 | Windows lock probe renames a live jar | Fixed (guard-link residue, P1 C3) |
| 1.2 | No fsync on snapshots/manifests | Fixed (two P2 gaps) |
| 1.3 | Vendor upgrade marker after script | Fixed (done marker not forced) |
| 1.4 | Reapplied hotfixes record pre-upgrade hashes | Fixed |
| 1.5 | Rollback accepts pruned point B | Fixed |
| 1.6 | Linux archive drops symlinks/ownership | Fixed (create/extract asymmetry, P1 O3) |
| 1.7 | Silent per-user home fallback | Fixed (stale Javadoc) |
| 1.8 | SQL compensation and splitting | Fixed (delimiter re-split, P1 O1) |
| 1.9 | Failing step never compensated | **Open — P0-1** |
| 1.10 | Journal failure escapes | Open |
| 1.11 | Cancellation invisible in waits | Open |
| 1.12 | Service control ignores access denied | Open |
| 1.13 | StopService compensation restarts stopped service | Open (fixed in upgrade copy only) |
| 1.14 | Timestamp string ordering | Open |
| 1.15 | Raw-string path comparison on Windows | Open |
| 1.16 | Disk-space check wrong volume | Open |
| 1.17 | Salt depends on DNS | Fixed with regression (P1 C1) |
| 1.18 | No quick_check; close() throws | Open |
| 1.19 | Retention and cleanup gaps | Open |
| 2.1 | js-import false success | Fixed (substring false positives, P2) |
| 2.2 | Transient HTTP errors fatal | Open |
| 2.3 | No re-login; no CSRF header | Partial (header yes) |
| 2.4 | Download/upload stalls | Open |
| 2.5 | Keystore lookup | Open |
| 2.6 | Keystore import flags | Open |
| 2.7 | Probes cannot say absent | Open |
| 2.8 | Vendor scripts re-split args | Open |
| 2.9 | Proxy and trust store | Open |
| 3.1 | Unsupported OS/arch not refused | Open |
| 3.2 | Linux init assumes systemd | Open |
| 3.3 | Lock detection root-only on Linux | Open |
| 3.4 | SQLite native lib temp dir | Open |
| 3.5 | Windows console encoding | Open |
| 3.6 | dist-linux on !windows | Open |
| 3.7 | Console log timestamps | Open |
| 4.1 | Console token in child argv | Partial (launch code; race remains) |
| 4.2 | Token file readable before restricted | Partial (POSIX yes, Windows no) |
| 4.3 | Ctrl-C exit 5; unmapped → 4 | Open |
| 4.4 | Confirmation semantics | Open |
| 4.5 | stderr not silent in --json | Open (masked test) |
| 4.6 | step_transitions.detail unredacted | Fixed |
| 4.7 | SSE starvation/unbounded queue | Partial |
| 4.8 | Truncated bundle as 200 | Open |
| 4.9 | Console shutdown order | Open |
| 5.1 | Stale deps, no Dependabot | Partial |
| 5.2 | needs-docker gate vacuous | Open |
| 5.3 | No real-JRS substitute | Open |
| 5.4 | No fault injection | Open |
| 5.5 | Build/CI hardening | Partial |
| 5.6 | Documentation gaps | Partial |

## Appendix B. New P2 findings by module (summary)

- **core**: `SnapshotStore.java:116` bypasses `Durability.move`; `syncDirectory` hides POSIX failures; WAL pragma result unchecked; `RunLock` I/O failure exits 4; `executeUpdate(String)` public and used with concatenation; resume runs the interrupted step's precheck twice, once outside the lock; one corrupt manifest disables all snapshot maintenance; `StateStore.close()` throws; `Runner.run` fingerprint guard trivially satisfiable (caller passes `plan.fingerprint()`); redactor misses `pwd=`, `secret=`, `token=`, `apikey=`; `secrets.enc` not owner-only on Windows; `isLocked` reports permission errors as locks; dead `Map.copyOf…isEmpty` idiom.
- **jrs**: `StartImport` retry can start a second server-side import; export file name kept only in memory across resume; `ImportSourceKeystore` trusts exit 0 without banner evidence; every `IOException` including TLS handshake becomes "unreachable" and is retried five times; secrets materialised as `String` in form bodies and `--storepass` argv; pre-auth token in the URL query (logged by Tomcat); `restLoginExists()` sends a failed login that counts toward lockout; vendor scripts inherit the full operator environment; isolated-mode allowlist compares host only; trust-store load failure is an `IllegalStateException` (exit 4, not 2); network I/O under the adapter lock.
- **ops**: `web-inf` case-insensitivity not handled; unbounded signature/payload sizes before signature check; rollback SQL scripts never hash-verified; JDBC has no statement/network timeouts and loads drivers from an unchecked directory; `PointB` stages inside `webapps/` (Tomcat would deploy a leftover); upgrade `StopService` marker written after the stop; `referenced_by` column is write-only and a failed upgrade's point B is unprotected from pruning; `planUpgrade` writes at plan time; symlinked hotfix targets are replaced by regular files.
- **app**: operator password kept as unzeroed `byte[]`; duplicate SSE events on attach; CSP allows `unsafe-inline` styles it does not need; `jrsctl` with no subcommand prints usage to stdout with exit 0; light-theme `--ink-3` fails WCAG AA (2.75:1); SSE queue unbounded and heartbeat shared; support bundle computed inside the response stream; `//api/health` and `/api/../x` handling relied upon but unasserted.
- **build**: `SignArtifacts` holds the key as `String`; wrapper without checksum; `ci.yml` uses bare `mvn` while `publish-package.yml` uses `./mvnw`; `publish-package.yml` on `workflow_dispatch` publishes whatever `main` is; `dist/src/image/bin/jrsctl` and `.githooks/pre-commit` lack the exec bit in the index; `b4f28b2` mixes 68 files of behaviour, dependency and tooling changes in one commit.
