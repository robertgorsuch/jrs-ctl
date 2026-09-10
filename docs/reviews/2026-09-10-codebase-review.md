# jrsctl codebase review — 2026-09-10

Scope: all six modules at commit `7ef7be1` plus the uncommitted Javadoc reflow in `ServiceSteps.java`. Six focused reviews (core engine, jrs adapter, ops plans, app CLI/console, cross-cutting portability, tests/CI/docs) were run against the code and `docs/spec.md` Draft 1.1. Every finding below was verified in source; line numbers are as of that commit. Nothing was modified.

Priority key: **P0** = can lose or silently corrupt server state; **P1** = a realistic failure the tool handles badly; **P2** = quality, hardening, or ergonomics.

---

## 1. Fault tolerance and correctness of mutating plans

### P0

**1.1 Windows lock probe renames a live jar inside a read-only precheck.**
`core/platform/WindowsFileOps.java:57-94, 337-365`; called from `ops/hotfix/ApplySteps.java:369` (Preflight, `mutating()==false`), `:650`, and `RollbackSteps.java:148`. `isLocked` moves the real file to `*.jrsctl-lockprobe` and moves it back. A kill between the two moves, or 20 failed `renameBack` attempts (logged at ERROR, swallowed, reported as "not locked"), leaves `WEB-INF/lib` missing a jar with nothing journaled and an exit code that says "nothing mutated". This is a mutation outside a `Step`, which violates spec §0.
*Fix:* probe without renaming (open for write with share-deny, or `DELETE_ON_CLOSE` on a hard link); if a rename probe must stay, make `renameBack` failure fatal and add a `doctor`/startup sweep for `*.jrsctl-lockprobe`, `*.jrsctl-tmp`, `*.jrsctl-restore`, `*.part` leftovers.

**1.2 No fsync on snapshot payloads, manifests, or replaced files.**
`core/snapshot/SnapshotStore.java:116, 329-343`; `core/platform/DefaultFileOps.java:62-88, 250-261`; `core/secrets/EncryptedSecretStore.java:331-334`. The only `force()` in core is the run lock. On power loss after the step has mutated the originals, delayed allocation can leave zero-length payloads behind a valid manifest. `verify()` detects it but rollback is then impossible.
*Fix:* write through `FileChannel`, `force(true)` each payload and the manifest, fsync the parent directory on POSIX before the manifest rename; same for `atomicReplace` staging.

**1.3 `RunVendorUpgrade` idempotency marker is written after the script runs.**
`ops/upgrade/VendorSteps.java:238-241, 279-288`. A crash after `js-upgrade-samedb` completes but before the marker is written means resume re-runs an in-place schema migration on an already migrated database, which jrsctl cannot undo (§10.1).
*Fix:* write an "attempted" marker before invoking; on resume with the marker present, refuse and require explicit operator confirmation.

**1.4 `--reapply-hotfixes` records pre-upgrade hashes, so a later `hotfix rollback` fails.**
`ops/upgrade/DefaultUpgradeOperations.java:239-243` builds the embedded apply plan at plan time, so `FileTarget.resolve` hashes the old webapp. `ApplySteps.java:910` stores that stale `before` hash in `hotfix_files`; `RollbackSteps.java:181-190` then reports "files differ from the snapshot after restore". `AtomicSwap.compensate` (`:712`) uses plan-time `existedBefore()` the same way.
*Fix:* resolve targets in a step that runs after `wait-for-server`, and record `before` from the snapshot manifest, not the plan.

**1.5 Upgrade rollback accepts a run whose config/keystore snapshots were pruned.**
`DefaultUpgradeOperations.java:277` checks only the webapp archive; `RestoreSteps.java:260-268` WARNs and skips a missing `backup-config`/`backup-keystore`. `RetentionProtection.java:58` protects only the latest successful upgrade and `SnapshotStore.prune` deletes per stepId, so an older upgrade run keeps its archive but loses config: rollback yields the old webapp pointed at the new database.
*Fix:* refuse the plan (exit 2) unless every point-B artefact is present and verified; prune upgrade runs as a unit.

**1.6 Linux webapp archive drops symlinks and ownership.**
`ops/upgrade/Archives.java:79-84, 239-246`. Non-following walk plus `isRegularFile()` skips symlinked files and never descends symlinked directories; only mode is stored, not uid/gid. A restore is missing linked jars and is owned by root, so Tomcat cannot write to it.
*Fix:* emit `LF_SYMLINK` entries and record/re-apply owner and group; at minimum fail `create` on encountering a symlink.

**1.7 Silent per-user home fallback defeats the run lock and splits the journal.**
`core/platform/AbstractPlatform.java:225-231`, `app/LogFile.java:49-54`, `core/state/RunLock.java:47`. A non-root Linux operator or a second non-admin Windows user silently gets `~/.jrsctl` with its own `state.db` and `runs.lock`. Two operators can mutate one install concurrently; exit 9 never fires. The two resolvers also use different writability tests, so logs and state can land in different homes.
*Fix:* one resolver; WARN loudly on fallback; scope the lock to the install directory, or refuse when the system home exists but is unwritable.

**1.8 SQL compensation and splitting are unsafe.**
`ApplySteps.java:797-807` runs every `rollbackFile` in reverse, including for scripts that never started; `SqlRunner.java:399-401` runs statement by statement on driver-default autocommit (`DefaultJdbcConnector.java:28`). `SqlScript.java:44-55` splits on line-ending `;`, so a `;` inside a string literal, `$$` body, PL/SQL block or block comment splits mid-statement after earlier statements have committed. `ManifestValidator` never requires rollback scripts to be idempotent.
*Fix:* journal per-script start/finish and compensate only started scripts; require `idempotent` rollback files; replace the splitter with a small lexer aware of quotes, dollar-quoting and comments, or a `-- jrsctl:delimiter` directive.

### P1

**1.9 The failing step is never compensated.**
`core/engine/Runner.java:343-345` (targets = succeeded mutating steps) and `:173-178` (recovery rollback ignores `FAILED`). A step that copies 3 of 5 files then returns `Recoverable` is left partially applied unless it self-cleans, while the cancel path (`:336`) does compensate the in-flight step. Spec §6.6 says "every succeeded Step", so this is a spec decision as much as a bug.
*Fix:* include the failed mutating step in compensation (compensate is already required to be idempotent); record as an ADR.

**1.10 Journal failure mid-run escapes with no event and no terminal state.**
`Runner.java:446-501`. A `StateStoreException` (disk full, busy over 5 s) propagates raw; no `RunFailed` is emitted, the console shows a hung run, and nothing tells the operator recovery is required.
*Fix:* catch in `proceed`, emit `RunFailed("journal unavailable; run jrsctl runs recover")`, rethrow a typed exception mapped to exit 8.

**1.11 Cancellation is invisible inside long waits.**
`Runner.java:278-283` (retry sleep then re-execute with no checkpoint), `core/platform/PollingServiceController.java:195-211` (up to `stopTimeoutSeconds` with no token check). A console cancel during a 60 s backoff or a 10-minute stop wait is ignored and the step re-executes.
*Fix:* `ctx.cancel().checkpoint()` after the sleep; pass the token into `await`.

**1.12 Service control ignores "access denied" and burns the full timeout.**
`WindowsServiceController.java:66,76`, `SystemdServiceController.java:63,73`, `PollingServiceController.java:217-230`. The `sc.exe`/`systemctl` result is discarded (a missing binary logs WARN and returns empty), then `await()` polls for `stopTimeoutSeconds`. Non-admin or non-root ends with a misleading "service did not stop within 180s".
*Fix:* fail fast on non-zero exit with "run elevated / as root" remediation; add a `doctor` elevation check.

**1.13 Hotfix `StopService` compensation starts a service the operator had stopped.**
`ops/hotfix/ServiceSteps.java:71-78` returns ok when already STOPPED, yet `compensate` unconditionally starts. `UpgradeServiceSteps.java:164-205` already solves this with a marker (though it writes the marker after the stop, `:168-172`; write it before).
*Fix:* same marker pattern in `ServiceSteps`.

**1.14 Timestamp string ordering is wrong at sub-second granularity.**
`core/state/StateStore.java:204` (`installed_at`, the hotfix LIFO order), `:409, 424` (`expires_at > ?`), `:500, 521`. `Instant.toString()` emits 0/3/6/9 fractional digits, so `…:00Z` sorts after `…:00.500Z`.
*Fix:* store epoch millis, or a fixed-width formatter with 3 fractional digits.

**1.15 Paths compared as raw strings on Windows.**
`StateStore.java:267-271, 324, 338` (overlap and `filesOwnedBy`), `ops/hotfix/HotfixPaths.java:91-93` (`requiresServiceStop` is case-sensitive, so `web-inf/lib/x.jar` with `restart: none` passes validation). `HotfixPaths.key` (`:152`, case-folded) exists but is unused; `AbstractPlatform.java:672-675` lowercases for dedupe. A `tomcatDir` re-entered in different case makes LIFO blocking and overlap checks miss rows.
*Fix:* persist and query a canonical key column (`toRealPath`, lowercased on Windows) next to the display path.

**1.16 Preflight disk-space check measures the wrong volume and size.**
`ApplySteps.java:321-340` checks `paths.commonBase()` only, but staging and snapshots may sit on another volume, and `2×payload` ignores the size of the existing files being snapshotted. `BackupSteps.java:311-318` has no free-space check at all before archiving a multi-GB webapp. `SnapshotStore.create` never calls the existing `FileOps.freeSpaceBytes`.
*Fix:* per-FileStore check with payload + existing sizes + margin, in both hotfix preflight and upgrade backup.

**1.17 Machine-bound salt depends on the DNS resolver.**
`EncryptedSecretStore.java:383-390`. `getLocalHost().getHostName()` flips between short name and FQDN with DNS/DHCP changes, yielding intermittent "wrong passphrase".
*Fix:* persist a random machine id in `$JRSCTL_HOME` (owner-only), or use `COMPUTERNAME`/`/etc/machine-id`.

**1.18 No corruption detection for `state.db`; `RunLock.close()` can turn a success into an error.**
`StateStore.java:61-87` never runs `PRAGMA quick_check`; `RunLock.java:107-137` with `Runner.java:57-61` throws `IllegalStateException` out of `run()` if truncate/release fails after `SUCCEEDED` was recorded.
*Fix:* `quick_check` on open and in `doctor` with a "move aside / restore from bundle" message; log and swallow in `close()`.

**1.19 Retention and cleanup gaps.**
`StageFiles` (`ApplySteps.java:542`) extends `ReadOnly`, so `Runner` never calls its compensate and staging trees accumulate. `SnapshotStore.list` (`:183-186`) reads only manifest dirs, so `snapshots/pre-import/*.zip` and upgrade `full-export.zip`/archives are never pruned (BUILD_STATUS already notes the upgrade case). `Archives.deleteRecursively` (`:202`) fails on Windows read-only attribute files.
*Fix:* mark `StageFiles` mutating or clean in `AtomicSwap.compensate`; extend `runs prune` to those artefacts under the same protection rules; clear the read-only attribute before delete.

---

## 2. Compatibility and the jrs adapter

### P0

**2.1 `js-import.sh` exits 0 when keystore/database validation fails.**
Verified in the 10.0.0 script on this machine: `js-ant validate-database validate-keystore; if [ $? -eq 0 ]; then bin/js-import-export.sh $*; fi` has no else branch, so a validation failure exits 0. `RunJsImport.java:103-105` treats `Completed(0)` as success (`VendorRun.java:21`). A Linux vendor import can silently import nothing and be recorded as verified.
*Fix:* parse output for `BUILD FAILED` / "Checking Ant return code: BAD" and require positive evidence (the import summary line) before `ok()`.

### P1

**2.2 Transient HTTP errors are fatal.**
`jrs/strategy/Polling.java:93` calls `attempt.get()` with no try/catch; `PollExport.java:95` / `PollImport.java:91` throw `RestException`/`JrsUnreachableException`, which `Runner.java:238-242` maps to `Recoverable` (never retried; neither poll step overrides `retryPolicy()`). `StartExport.java:93-98`, `StartImport.java:88-93`, `DownloadExport.java:91-98` catch only `JrsUnreachableException`, so a 503 from a still-deploying Tomcat, 502 from a proxy, or 429 propagates as non-retryable. `RestClient.send` (`:545-553`) has no retry at all. One blip during a two-hour import fails the run while the server-side task continues.
*Fix:* catch both exceptions inside the poll tick and `Continue` up to N consecutive failures; classify 408/429/502/503/504 as retryable in the start/download steps (they are already idempotent via the handle file); honour `Retry-After`.

**2.3 Form sessions are never re-established; no CSRF header on cookie-authenticated mutations.**
`RestJrsAdapter.java:315-321` logs in only when `session` is empty; nothing inspects `RestException.authenticationFailure()`. After a restart (hotfix/upgrade flows) or session timeout every call 401s until the process restarts. `RestClient.send` (`:536-543`) never sends `X-REMOTE-DOMAIN: 1`, which JRS's CSRFGuard requires for POST/PUT/DELETE on a `JSESSIONID`.
*Fix:* on 401 in FORM mode clear the session, re-login once, replay; always send `X-REMOTE-DOMAIN: 1` (harmless under Basic).

**2.4 Download and upload stalls hang forever.**
`RestClient.java:58, 390, 422, 538`. `HttpRequest.timeout` covers only time-to-headers; the 2 h `downloadTimeout` does not bound body transfer. A half-open connection during `getToFile` blocks `DownloadExport` with no retry or cancel.
*Fix:* `sendAsync` with a body-progress idle watchdog; honour the cancel token.

**2.5 Keystore lookup ignores `keystore.init.properties` and Windows service accounts.**
`KeystoreInspector.java:86-87, 112-116`. Always `<home>/.jrsks`; Windows users are resolved only under `C:\Users`, so `NT AUTHORITY\SYSTEM` or `NetworkService` yields "not found" and the fingerprint check degrades to WARN. The real 10.0.0 install here sets `ks=C:\Users\rgorsuch` in `buildomatic/keystore.init.properties`.
*Fix:* read `ks`/`ksp` from `keystore.init.properties` first; map well-known service accounts to their profile directories.

**2.6 Keystore import flags are probably wrong.**
`VendorTools.java:171-187` passes only `--keystore <.jrsks> --storepass`. The 10.0.0 message bundle documents `--keystore` as "import the key from a java keystore" together with `--keyalias`/`--keypass`; `import-export.xml` only ever passes `--keyalias/--keypass/--secret-key`.
*Fix:* test `ImportSourceKeystore` against the live 10.0.0 install and encode the verified option set per version in `VendorFlags`.

**2.7 Capability probes cannot say "absent"; unknown versions get an empty set.**
`RestJrsAdapter.java:195-219, 451-466`. `probeEndpoint` counts 404 as present, but a server without `/rest_v2/export` also answers 404. `KEYSTORE_ENCRYPTION/TOKEN_AUTH/PREAUTH` come only from the matrix, so a future 11.x or a 7.0 gets nothing and `keystore()` reports "pre 7.5, nothing to carry across". With wrong Basic credentials all probes return 401 and `doctor` only WARNs. Edition fallback defaults to PRO (`ServerIdentities.java:85`).
*Fix:* inspect the 404 body (JRS returns a JSON errorCode for a missing task) or use HEAD/OPTIONS; treat unmatched versions as "unknown", not "absent"; fail probing on 401/403.

**2.8 Vendor scripts re-split arguments.**
`VendorTools.java:104`, `VendorFlags.java:40`. `--uris` is joined with `,`; `js-export.bat` re-reads `%1` where comma/semicolon/equals are cmd delimiters, and Java only quotes args containing spaces, so `--uris /a,/b` becomes three arguments on Windows. On Linux `js-import.sh` expands `$*` unquoted, so an archive path with a space breaks.
*Fix:* quote `--uris "..."` on Windows (or one `--uris` per URI if accepted); precheck-reject paths with spaces for the VendorCli strategy.

**2.9 Proxy and trust store handling.**
`RestClient.java:122-126, 645-655`. `Authenticator` Basic is disabled by the JDK for CONNECT tunnels, so every `https://` base URL through an authenticated proxy gets 407; `ProxySelector.of` has no bypass for localhost. A custom trust store replaces the JDK CAs (`ctx.init(null, tmf, null)`), so a corporate-CA-only store breaks a JRS behind a public certificate; no client-cert support.
*Fix:* set `jdk.http.auth.tunneling.disabledSchemes=""` at client build (documented); add `network.proxy.noProxy`; composite trust manager (custom + default).

---

## 3. Portability

**3.1 (P1) Unsupported OS/arch is never refused.**
`core/platform/Platforms.java:51-58`, `app/Bootstrap.java:63`. Every `os.name` without "win" maps to `LINUX`; `Arch.OTHER` is computed but never checked. macOS/BSD/ARM64 hosts run with `/var/lib`, `systemctl` and `/proc` assumptions instead of exit 6.
*Fix:* `OsFamily.OTHER`; in `Bootstrap.open` and `selfcheck` exit 6 when os/arch is not the ADR-0002 pair.

**3.2 (P1) Linux init assumes systemd.**
`ops/init/InitOperation.java:235, 315, 352-365`. Only `systemctl list-units` is probed; failure (Docker, WSL1, SysV, OpenRC, supervisord) is logged at DEBUG and `catalina.sh` is chosen. A supervised Tomcat is stopped behind the supervisor's back and may be respawned mid-hotfix.
*Fix:* detect `/proc/1/comm`, probe `/etc/init.d`, surface "no service manager detected" in the init report and `doctor`.

**3.3 (P1) Lock detection is root-only on Linux.**
`LinuxFileOps.java:71-83`, `TomcatProcesses.java:93`. `/proc/<pid>/fd|cwd` are unreadable for another uid; errors are swallowed so `isLocked` is always false for non-root.
*Fix:* report "cannot inspect (not root)" as a WARN rather than "not locked".

**3.4 (P1) SQLite native library needs an exec-able temp dir.**
`StateStore.java:63`; no `org.sqlite.tmpdir` anywhere. On Linux with `/tmp` mounted `noexec` (CIS baseline) every stateful command dies with `UnsatisfiedLinkError`.
*Fix:* set `org.sqlite.tmpdir` to `$JRSCTL_HOME/runs` in Bootstrap; add a selfcheck item.

**3.5 (P2) Windows console encoding.**
`app/Ansi.java:26-30`, `app/ProgressRenderer.java:222-230`: colour and ✔✖↻↩ glyphs are enabled whenever `System.console()` exists; `bin/jrsctl.cmd` sets no `stdout.encoding` and nothing enables VT mode, so cmd.exe/PowerShell 5 shows `?` and literal escapes. `DefaultProcessRunner.java:48-52` decodes child output with `native.encoding` (Cp1252) but `sc.exe`/`reg.exe` write OEM (cp437/850), so a non-ASCII `InstallLocation` is misdecoded and `init` misses the install.
*Fix:* ANSI only with `WT_SESSION`/`TERM`/ConEmu (picocli's `Help.Ansi.AUTO` implements this); ASCII glyphs unless stdout is UTF-8; decode Windows child output with the OEM code page; add `--color` and `--ascii`.

**3.6 (P2) `dist-linux` profile activates on `!windows`.**
`dist/pom.xml:64`. A macOS or ARM64 builder produces an archive labelled `linux-x64` with a foreign runtime.
*Fix:* activate on `<name>Linux</name><arch>amd64</arch>`; assert `os.arch` in `RuntimeModules`.

**3.7 (P2) Console log timestamps are local time without date or zone.**
`core/src/main/resources/logback.xml:17`. Windows workstations and UTC servers cannot be correlated in support bundles.
*Fix:* `%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}`.

---

## 4. CLI, console, and security

**4.1 (P1) Console token leaks via child-process argv.**
`app/ConsoleCommand.java:114-117, 185-186`. The URL with `#token=` is passed to `rundll32`/`xdg-open`, readable by any local user via `ps`/Task Manager, defeating §11.2.
*Fix:* open the bare base URL and exchange a single-use, short-TTL launch code for the token; default `--no-open` when the home is not user-private.

**4.2 (P1) Token file is readable before it is restricted.**
`app/OwnerOnlyFiles.java:35-41`. Content is written under the default umask, then restricted.
*Fix:* create with `rw-------` as a file attribute, or create-empty → restrict → write; verify Windows disables inheritance.

**4.3 (P1) Ctrl-C never produces exit 5; unexpected exceptions exit 4 with no trace.**
`app/PlanExecutor.java:204-233`: the shutdown hook cancels and joins, then the JVM halts with 130/143 and `renderer.outcome()` may be cut mid-line. `app/ExitCodes.java:143-175`: every unmapped exception, including in read-only commands, exits 4 "FailedRollbackIncomplete", nothing is logged, and a null message prints `error: null`.
*Fix:* hook waits for the worker and bounded rendering, then `halt(outcome.exitCode())`; log unmapped exceptions (redacted), print "see logs/jrsctl.log", and exit 2 unless a run has started.

**4.4 (P1) Confirmation semantics are wrong in two directions.**
`app/GlobalOptions.java:39-42, 63-65`, `PlanExecutor.java:98`: `--non-interactive` is documented as "fail where a human is needed" but `yes()` treats it as `--yes`, so `hotfix apply x --non-interactive` runs unprompted. `app/Terminal.java:15-20`: JDK 21 `System.console()` is null when stdout is piped, so `jrsctl hotfix apply … | tee run.log` exits 2 for an attended operator.
*Fix:* `--non-interactive` → exit 2 "confirmation required"; only `--yes` confirms; fall back to stdin confirmation when not `--json`.

**4.5 (P1) stderr is not silent in `--json`.**
`core/src/main/resources/logback.xml:15-20`, `ExitCodes.java:23-25`, `JrsctlCommand.java:44`. The CONSOLE appender emits INFO (Jetty startup, "console listening on") to stderr regardless; `jrsctl --json` with no subcommand prints picocli usage to stdout.
*Fix:* console-appender threshold set by `LogFile.configure` (WARN default, OFF with `--json`, DEBUG with `--verbose`).

**4.6 (P1) `step_transitions.detail` is written unredacted.**
`Runner.java:263-267, 298, 327, 435`. Events pass the redacting sink, but the raw cause (exception text, JDBC URL, HTTP body) is persisted in `state.db`, which ships in support bundles.
*Fix:* redact before `appendTransition`.

**4.7 (P2) SSE: one stalled browser starves every session; unbounded queue; endless reconnect on unknown run.**
`app/console/SseSession.java:22, 44-63`, `ConsoleServer.java:51-57`, `ConsoleApi.java:307-317`, `web/api.js:246-262`. `sendEvent` blocks on the socket while holding the lock; the shared heartbeat executor then blocks for all sessions; `LinkedBlockingQueue` is unbounded. Javalin commits `200 text/event-stream` before the handler runs, so `notFound` for an unknown run reaches the client as an empty 200 and the JS retries every 30 s forever.
*Fix:* bounded queue that closes with a "lagged" comment; per-session heartbeat from the writer thread; validate the run id in a `before` handler; cap client retries.

**4.8 (P2) Support bundle can ship truncated as HTTP 200.**
`ConsoleApi.java:426-432`, `app/SupportBundle.java:77-80`. Headers are committed, then live `doctor` probes run inside the zip write; an exception mid-stream delivers a corrupt zip. `events.jsonl` is uncapped and the log path ignores `-Djrsctl.log.file`.
*Fix:* compute server/doctor before opening the stream; tail `events.jsonl`; read `LogFile.PROPERTY`.

**4.9 (P2) Console shutdown drops clients before cancellation is visible.**
`ConsoleServer.java:210-219`, `ConsoleCommand.java:118-128`. `a.stop()` precedes `manager.shutdown()`; on Ctrl-C the store is never closed.
*Fix:* "closing" flag that refuses new runs, cancel-and-wait, then stop the listener; close the store in the hook.

---

## 5. Dependencies, tests, CI, docs

**5.1 (P1) logback 1.5.6 carries CVE-2024-12798 and CVE-2024-12801**, fixed in 1.5.13+. Nothing local catches it because OWASP dependency-check runs only in CI with an optional NVD key. Javalin 6.3.0 resolves Jetty 11.0.23 (11.0.24 fixed CVE-2024-8184/6763); sqlite-jdbc 3.46.0.0 predates the 2025 SQLite advisories. The rest of the set (Jackson 2.17.2, commons-compress 1.26.2, picocli 4.7.6, json-schema-validator 1.4.0, semver4j 5.3.0, JUnit 5.10.2, Testcontainers 1.19.8, Error Prone 2.28.0, surefire 3.2.5, dependency-check 9.2.0) is roughly two years stale. No Dependabot/Renovate.
*Fix:* bump logback now; add `dependabot.yml` (maven + github-actions, weekly) and `versions-maven-plugin:display-dependency-updates`; run dependency-check once locally as a baseline.

**5.2 (P1) The `needs-docker` CI gate is vacuous.**
`.github/workflows/ci.yml` `integration` job runs `-Dgroups=needs-docker`, but no test carries that tag (only `LiveJrsContainerTest` is `needs-jrs`). The Phase 3 Postgres SQL apply/rollback test and the ADR-0007 Playwright test do not exist, yet `release` depends on `integration`, so the release gate passes on zero tests. `ci.yml` also references a `nightly.yml` that does not exist, and `LiveJrsContainerTest` defaults to `registry.example.invalid` (spec Q6 unanswered).
*Fix:* add the two tests or amend ADR-0007 and BUILD_STATUS; fail the job when surefire runs zero tests.

**5.3 (P1) No substitute for the real-JRS gate.**
Spec §20 requires the adapter suite green across every `compat/matrix.yaml` row; that cannot happen without Docker.
*Fix:* record WireMock JSON fixtures from the live 10.0.0 install on this machine (`serverInfo`, capability probes, export/import task lifecycle, the 404 body shape, session-expiry 401) under `jrs/src/test/resources/recorded/<version>/` and drive `RestJrsAdapterCapabilitiesTest` per matrix row. This also directly tests 2.2, 2.3 and 2.7.

**5.4 (P2) No fault-injection, cross-process, or clock-skew tests.**
`core/src/test/.../FakePlatform.java` and `ops/.../FakePlatform.java` only stub with `UnsupportedOperationException`; zero WireMock `Fault`/`withFixedDelay` usages; `StateStoreTest` has no multi-connection `SQLITE_BUSY` case; `RunLockTest` and `Phase1CoreEngineTest:83` take the lock in one JVM although spec §14 says "a second process (exit 9)"; no test moves a `Clock` backwards across plan TTL or retention age.
*Fix:* `FailingFileOps` decorator (IOException on the Nth write); WireMock `CONNECTION_RESET_BY_PEER` mid-poll; second-JVM StateStore writer; acceptance test launching the jar twice against one home; `ManualClock` backwards-jump cases.

**5.5 (P2) Build and CI hardening.**
No JaCoCo, no PITest, enforcer has only java/maven version rules (no `dependencyConvergence`); actions pinned to major tags, no `timeout-minutes` or `concurrency`, no build provenance attestation, `java-version: "21"` floats while `.mvn/jvm.config` `--add-exports` is JDK-sensitive; `spec-changelog.md` says reproducible builds were dropped "to be recorded as ADR" but no ADR exists and `dist/pom.xml` sets no `project.build.outputTimestamp`, so signatures attest unreproducible bytes; no Maven Wrapper, so CI and `scripts/mvn.cmd` resolve Maven differently.
*Fix:* JaCoCo check ≥80% on core/ops; enforcer `dependencyConvergence`; SHA-pin actions, `timeout-minutes: 60`, pin `21.0.x`, attest archives + SBOM; `outputTimestamp` plus an ADR; add `mvnw`.

**5.6 (P2) Documentation gaps.**
`docs/operator-guide.md` has no air-gapped install/offline verification section (proxy/truststore is a one-line `JRSCTL_JAVA_OPTS` aside), no rendered supported-version matrix, and no recovery runbook for exit 4/8 from a dead host (snapshot layout, manual restore). No `CONTRIBUTING.md`. The bundled publisher key is comments-only with no `selfcheck` warning.
*Fix:* `docs/recovery-runbook.md`; render `matrix.yaml` into the guide; "Air-gapped install and verification" section; short CONTRIBUTING (phase tags, `needs-*` tags, ADR process); `selfcheck` WARN when the bundled key is empty.

---

## 6. Enhancements worth scheduling

Operator-facing:
- `doctor` sweep for orphaned `*.jrsctl-lockprobe`, `*.jrsctl-tmp`, `*.jrsctl-restore`, `*.part` files in the install tree, plus an elevation check and a network-mounted `$JRSCTL_HOME` warning (fcntl/LockFileEx semantics are not guaranteed on NFS/SMB).
- Preflight refusal when `TomcatProcessFinder` shows a second Tomcat instance rooted in the same `webapps/`.
- Shell completion (`jrsctl completion bash|zsh|powershell`) via picocli `AutoComplete`, already a dependency.
- `--verbose`/`--quiet`, `--color=auto|always|never` (honour `CLICOLOR_FORCE`), `--ascii`.
- `jrsctl runs bundle <id> --out file.zip` for operators without the console; `jrsctl runs show <id> --follow`.
- `jrsctl console --idle-timeout <min>`; `--plan --json` diff against the last successful run of the same operation.
- Configurable `network:` connect/request/download/idle timeouts, with step timeouts derived from them; `RetryPolicy` total-elapsed cap.
- Structured import/export summary parsed from `ImportExportLogger` output instead of a 20-line tail; record `licenseType`/`expiration` in `doctor`.
- Persist the export `fileName` next to the handle file so a resumed run does not fall back to `export.zip`.

Platform:
- ARM64 packaging (`linux-aarch64`, `windows-arm64` profiles, `ubuntu-24.04-arm` in CI); ADR-0002 calls this packaging-only.
- musl detection in `bin/jrsctl` with a clear message on Alpine.
- A generic `service <name> stop|start` / OpenRC service kind, or explicit refusal with remediation.
- Container/K8s awareness: a service kind whose "restart" is a pod restart.

Engine:
- Take the run lock in `Recovery.resume` before the interrupted step's precheck (`core/state/Recovery.java:243` runs unlocked before `:258`).
- Free-space budget in `SnapshotStore.create` using the existing `FileOps.freeSpaceBytes`.

---

## Suggested order of work

1. **Server-safety fixes first** (1.1, 1.2, 1.3, 2.1, 1.7): each can lose data or silently do nothing on a customer server, and each is a small, well-bounded change.
2. **Upgrade rollback integrity** (1.4, 1.5, 1.6, 1.8): these decide whether "rollback to point B" is a promise the product can keep.
3. **HTTP resilience and session handling** (2.2, 2.3, 2.4) with the recorded-fixture harness (5.3) so they are testable without Docker.
4. **Engine semantics** (1.9 through 1.14) and the CLI exit/confirm fixes (4.3, 4.4, 4.5), each small.
5. **Dependency bump and CI gate repair** (5.1, 5.2, 5.5) before any v1.0 tag.
6. Portability refusals and detection (3.1 through 3.4), then console hardening (4.1, 4.2, 4.7) and the encoding work (3.5).

Not carried forward from the reviews: a claim that the Linux crash-recovery branch has never run. `docs/BUILD_STATUS.md` records a full `scripts/mvn.sh verify` on Ubuntu 24.04 on 2026-09-09 with the real `kill -9` branch passing; CI itself has still never run because the repository has no remote.
