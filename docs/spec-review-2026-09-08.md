# jrsctl Technical Design Spec — Review and Recommendations

Status: superseded. Every item below was applied to Draft 1.1 on 2026-09-08 or recorded as a
non-goal in `docs/decisions/0001` to `0006`; `docs/spec-changelog.md` maps each item to its
change. Kept for the history of the decisions; `docs/spec.md` is the contract.

Reviewed: `jrsctl-technical-design-spec.md` (Draft 1.0, 2026-09-08)
Purpose: changes to make before the build agent starts Phase 0.

Priority key: **P1** = fix before building (correctness or scope risk). **P2** = fix before the affected phase. **P3** = nice to have.

---

## A. Scope and sizing (P1)

The v1 surface is very large: 9 Maven modules, 8 phases, 4 version adapters, 2 OSes x 2 architectures, three packaging technologies, a web console, plus hotfix, export/import and upgrade. Recommended cuts, each recorded as an ADR:

1. **Drop GraalVM native-image from v1.** jlink already delivers "no JDK required". native-image with sqlite-jdbc, Jackson, Javalin/Jetty and reflection-heavy libs is weeks of reachability-metadata work for no operator benefit.
2. **Drop ARM64 (both OSes).** JasperReports Server itself is only supported on x86_64. This halves the distribution matrix.
3. **Phase 7 ships portable archives only** (ZIP for Windows, tar.gz for Linux, bundled jlink runtime). MSI/EXE/DEB/RPM move to a later phase. Air-gapped customers need the portable archive most.
4. **Drop "reproducible build" from Phase 7.** jlink and jpackage embed timestamps; this becomes a rabbit hole. Replace with signed SHA-256 checksums + SBOM.
5. **Collapse version adapters into one capability-driven `RestJrsAdapter`.** The REST v2 export/import API is essentially unchanged from 7.1 through 10.x; what varies is auth (form vs `/rest_v2/login` vs preauth token) and keystore presence, which §7.1 already probes as capabilities. Four speculative adapter classes are code to write and test before any real drift is observed. Add version-specific quirk classes only when the nightly contract suite finds a difference.
6. **Reduce modules.** Suggested: `core` (config, secrets, platform, state, engine), `jrs` (REST client, adapter, vendor wrappers), `ops`, `app` (CLI + console + main), `dist`, `acceptance`. Fewer modules means less Maven friction for an autonomous agent and fewer cross-module API decisions.
7. **Reorder phases so hotfix precedes export/import.** Hotfix is the first-listed customer problem, has no dependency on export/import, and is fully testable without a real JRS (file ops + WireMock). Export/import needs a real server to be meaningful. Suggested order: 0 Skeleton, 1 Core+Engine, 2 Adapter+Doctor, 3 Hotfix, 4 Export/Import, 5 Upgrade, 6 Console, 7 Distribution, 8 Hardening. Console last because it only renders events every op already emits.

## B. Correctness gaps that will bite (P1)

### B1. Upgrade rollback to point B does not restore the database
§10.1 "rollback point C = restore B" restores webapp, keystore and config, but `js-upgrade-samedb` migrates the repository database schema in place. Restoring the old webapp against the upgraded schema leaves a broken server. The `FullExport` taken in Phase B can only be restored by re-initialising the DB (`js-init`/drop) and `js-import`, which is destructive and slow, and §1.3 puts DB backup out of scope.

Recommendation:
- Make `--mode newdb` the default. The old DB stays untouched; rollback = restore webapp/config pointing at the old DB.
- For `samedb`, add a hard gate: `doctor` requires `--db-backup-confirmed` (audited), and the Plan summary states in plain text that DB rollback is the operator's responsibility.
- Rewrite §10.1 rollback semantics accordingly.

### B2. "Replace on restart" file strategy should be removed
§5.3 and §8.2 step 8 propose swapping locked jars via a staged directory and a "startup hook". Tomcat has no such hook, and replacing a jar under a running JVM is unsafe on Linux too (mapped zip pages). Rule: **any change under `WEB-INF/lib` or `WEB-INF/classes` requires service stop on both OSes.** `restart: none` in the manifest is only valid for files Tomcat does not hold open. This removes the hardest platform-specific code in the spec.

### B3. Hotfix SQL has no database connectivity, no rollback, and an inconsistent transaction promise
- `config.yaml` has no `database` section; JRS keeps DB credentials in `buildomatic/default_master.properties` and in `js.jdbc.properties` (encrypted via keystore from 7.5). Add `database: { url, username, passwordRef, driverDir }`, with `driverDir` defaulting to the JRS-supplied JDBC jars under `buildomatic/conf_source/db/<db>/jdbc/`, loaded via URLClassLoader. `doctor` can offer to prefill from `default_master.properties`.
- "Inside a transaction where the DB supports it" produces different semantics per DB (MySQL and Oracle auto-commit DDL). Drop the transaction promise; require `idempotent: true` for every script and reject bundles that omit it.
- `"rollback": "snapshot"` cannot undo SQL. Require either `sql[].rollbackFile` per script or `"rollback": "irreversible"` with the reason surfaced in the Plan and exit path.

### B4. Two sources of truth for run state
§5.4 stores `runs`/`steps` in SQLite; §5.5 stores the same transitions in fsync'd JSONL. Two stores drift, and resume logic has to pick one. Make SQLite (WAL, `synchronous=FULL`, append-only `steps` table via trigger) the single journal. Emit JSONL only as an export inside the support bundle. SSE replay reads SQLite.

### B5. Steps need idempotency, not partial-state detection
§6.6 "resume re-runs prechecks for the interrupted Step" cannot detect a step that was half-complete (3 of 10 files swapped). Require every `Step.execute` to be idempotent: re-execution converges to the same end state. Add this to the Step contract and to the crash-injection tests in Phase 8. This is the simplest crash-safety rule and removes the need for per-step progress inspection.

### B6. Service control assumes a named service
Many Linux JRS installs (bundled installer) are started via `ctlscript.sh` or `catalina.sh`, not systemd. Add `service.kind: windows-service | systemd | ctlscript | catalina | manual`. `manual` prompts the operator to stop/start and polls `serverInfo` until the state changes. Use `sc.exe`/`systemctl` via `ProcessRunner`; poll state, do not assume synchronous stop.

### B7. Concurrency lock
Nothing prevents two `jrsctl` processes (CLI and console, or two operators) from mutating the same server. Add a `runs.lock` file lock in `$JRSCTL_HOME`; a second mutating run fails with a distinct exit code.

### B8. Non-interactive runs with a pending journal
§5.5 "offers resume or rollback" on startup. With `--yes` there is nobody to answer. Add exit code 8 "pending recovery required" and make `runs recover` the only path forward.

## C. Design simplifications (P2)

1. **Java 21, not 17.** The tool ships its own runtime, so the server's Java is irrelevant. Java 21 gives pattern-matching switch over the sealed interfaces the spec already mandates, plus virtual threads for the SSE/poll loops. Separately, add `vendor.javaHome` to config: buildomatic must run on the *server's* Java (JRS 8 = 11, JRS 9+ = 17), never on jrsctl's bundled runtime.
2. **Drop BouncyCastle.** JDK 15+ has Ed25519 (`KeyPairGenerator.getInstance("Ed25519")`) and AES-GCM natively. One fewer dependency and a smaller jlink image.
3. **Drop OS keyrings from v1.** Windows Credential Manager needs JNA (not on the approved list); Linux Secret Service needs a D-Bus session that headless servers lack. Keep `env:`, `file:` (0600 enforced) and the AES-GCM encrypted file. Specify how non-interactive runs unlock the encrypted file (`JRSCTL_PASSPHRASE` env or `--passphrase-file`), or it cannot be used from cron.
4. **Split `VendorCliAdapter` out of `JrsAdapter`.** It cannot implement `login()`, `health()` or `identity()`. Model it as `ExportImportStrategy { Rest, VendorCli }` chosen by the ops layer. Also: `js-import` should not run against a running server (vendor guidance); the vendor strategy plan must include StopService.
5. **Sign only `manifest.json`.** The manifest already carries the SHA-256 of every payload file; verifying the signature over the manifest and then hashing every payload gives the same guarantee with less code. Add `checks/` files to the manifest hash list (they are currently unhashed).
6. **Manifest needs explicit file actions.** `files[]` only expresses replace. Add `"action": "add" | "replace" | "delete"`; §8.2 step 5 already assumes removals exist.
7. **Hotfix rollback ordering.** Rolling back hotfix N when hotfix N+1 touched the same file corrupts state. Rule: rollback must be LIFO per file; otherwise refuse with the list of blocking hotfixes. Track installed file paths per hotfix in the state store and detect file-level overlap on apply.
8. **Post-upgrade hotfix and customization reapply must be report-first.** §10.1 steps 10–11 auto re-run apply and blindly copy customized files. After a version change the `replaces` targets often no longer exist and customized XML conflicts with new defaults. Do a 3-way comparison (original at register time, customized, new) and auto-apply only when the new file equals the original; everything else is reported for operator confirmation.
9. **Import rollback is best-effort, say so.** Re-importing a pre-import export does not delete resources the failed import created. State this in the Plan summary and operator guide rather than implying a true restore.
10. **Keystore location depends on the server's OS user.** `.jrsks`/`.jrsksp` live in the home of the account running Tomcat, not the account running jrsctl. Add `server.runAsUser` and have `doctor` resolve and check it.
11. **Compat matrix signing adds nothing** while the matrix is bundled in the same JAR as the key. Ship it unsigned; sign only if you add `jrsctl matrix update <file>`.
12. **Rule 4 (everything is a Plan) should apply to mutating operations only.** `doctor`, `smoke`, `list`, `keys list`, `selfcheck` are reads; forcing them through Plan/Step is ceremony.
13. **Isolated mode needs an enforceable definition.** "No outbound network" still has to reach `baseUrl`. Define isolated = HTTP client allowlist containing only the `baseUrl` host; any other host is refused and audited. That is testable.
14. **Console on loopback still needs a token.** Any local user on a shared host can otherwise POST `/api/run`. Generate a per-launch bearer token, print it and write it to a 0600 file (the Jupyter pattern). This replaces the CSRF-without-cookies design, which is contradictory as written. Validate the `Host` header to block DNS rebinding.
15. **Step contract consistency.** `precheck` returns a `Precheck` object, `postcheck` "failure == exception". Make both return a sealed `CheckResult`. Add `phase()` to Step (or a `Phase` grouping in Plan) since §6.3 rolls back "to the nearest phase boundary" but only upgrade defines phases. Add `STEP_ROLLBACK_FAILED` to `EventType` (needed for exit code 4). Make failure classification explicit: Steps return a sealed `StepFailure { Retryable, Recoverable, Fatal }` rather than the Runner sniffing exceptions.
16. **Plan hash inputs must be enumerated.** §6.2 refuses to run if "inputs changed". Define `PlanFingerprint` = server identity + bundle/archive hash + SHA-256 of every target file + resolved config. Also give `POST /api/plan` results a TTL and storage so `POST /api/run {planHash}` can find them.
17. **Typed events.** `Map<String,Object> data` defeats the `--json` schema validation in Phase 8. Use a sealed `Event` hierarchy with per-type records.
18. **Streaming I/O requirement.** Full exports can be multi-GB and the webapp backup ~1 GB. State explicitly: no whole-file byte arrays; SHA-256 computed while streaming; downloads written to disk incrementally.
19. **Redaction test must cover encodings.** `Authorization: Basic` is base64; URL-encoded passwords appear in form logins. The property test should assert absence of raw, base64 and URL-encoded forms.

## D. Missing pieces (P2)

1. **`jrsctl hotfix build <dir> --key <ref>`.** Nothing in the spec creates signed bundles, yet Phase 4 needs sample signed bundles and customers need to author internal hotfixes. This is also the fixture generator for tests.
2. **`jrsctl init`.** A detection wizard that finds the install dir (common paths, running Tomcat process cwd, Windows registry), reads `default_master.properties`, and writes `config.yaml`. Highest-impact onboarding feature; `doctor` alone assumes config already exists.
3. **Smoke report source.** §12.2 runs "a reference report (bundled sample)". Sample reports only exist if JRS was installed with samples. Make the report URI configurable, default to a known sample, WARN if absent, and offer `--mutating` upload of a bundled minimal JRXML under `/temp/jrsctl`.
4. **Webapp name and edition.** Add `server.webappName` (`jasperserver` vs `jasperserver-pro`) rather than deriving it from `baseUrl`.
5. **Exit code 8** (pending recovery, see B8) and **exit code 9** (lock held by another run, see B7).
6. **buildomatic property overrides.** §7.4 says overrides go in "copies in a run-scoped temp dir". `js-ant` reads `default_master.properties` from its own buildomatic directory. Verify whether an alternate path is supported; if not, allow writing `default_master.properties` into the *target package's* buildomatic dir, which is customer-supplied and is the documented upgrade procedure anyway.

## E. Process and agent instructions (P2)

1. **Rule 1 blocks on CI.** An autonomous agent working locally cannot wait on GitHub Actions on both OSes before starting the next phase. Change to: acceptance passes locally on the dev OS before the next phase starts; both-OS CI must be green before merge.
2. **Phase 2 contradicts Rule 10.** Phase 2 acceptance says a Testcontainers test "passes against at least one real JRS CE image"; Rule 10 says stub when unavailable. Make the real-JRS suite a separate non-blocking gate (`needs-jrs`) throughout, green before release rather than before phase progression. Name the image to use (the `js-docker` build or a private registry tag) since there is no reliably maintained public CE image.
3. **Dev environment is Windows.** The build machine here is Windows 11; Testcontainers needs Docker Desktop, and `kill -9` crash injection becomes `taskkill /F`. State both.
4. **Signing needs credentials the agent will not have.** Say explicitly that Phase 7 code-signing and publisher-key steps are implemented but executed only in CI with secrets; the agent produces unsigned artifacts locally and records that in `BUILD_STATUS.md`.
5. **Dependency list cleanup.** `logback-json` is not a real artifact (use `logstash-logback-encoder`). Pick `networknt json-schema-validator` (maintained, JSON Schema 2020-12). Remove `bouncycastle` (see C2). Add `jna` only if OS keyrings return.
6. **Copy the spec into the repo** (`docs/spec.md`) so `CLAUDE.md` can point at a versioned file, and add a `docs/spec-changelog.md` so ADRs can cite spec revisions.

## F. Minor (P3)

- §5.1 `auth.mode` default should be `basic`; every REST v2 call accepts it and it avoids cookie/session management. Form login is only needed for very old builds.
- `selfcheck` "binary hash against embedded manifest" is circular for the launcher; verify per-jar hashes in the runtime image against a build-time manifest instead, or drop.
- `Smoke` "page count > 0" for PDF needs a PDF parser; specify a cheaper check (`%PDF` magic + content-length above a threshold).
- §8.1 `id` embeds a version (`JRS-10.0.0-HF-0007`) while `applies.versions` is a range. Fine, but say which one is the state store key.
- Retention: "never prunes a snapshot referenced by an installed hotfix" also needs "or by the most recent successful upgrade".
- Exit code table: state that `--allow-unsupported` and `--allow-unsigned` overrides do not change the exit code, only the audit row.

---

## Suggested minimal edits to the spec before Phase 0

1. Apply A1–A4 and A7 (scope cuts and phase reorder) and record as ADR-0001..0005.
2. Rewrite §10.1 rollback semantics per B1; default `--mode newdb`.
3. Delete the replace-on-restart strategy (B2); add the WEB-INF stop rule to §5.3 and §8.2.
4. Add `database`, `service.kind`, `server.runAsUser`, `server.webappName`, `vendor.javaHome` to §5.1.
5. Merge journal into SQLite (B4); add the idempotent-Step rule (B5) to §6.1.
6. Add the lock file and exit codes 8/9 (B7, B8).
7. Add `hotfix build` and `init` commands (D1, D2).
8. Change Rule 1 and Phase 2 gating (E1, E2).
