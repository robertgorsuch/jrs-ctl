# jrsctl spec changelog

## Draft 1.1 amendment — 2026-09-10

- §6.3, §6.6, §14 phase 1: a `Recoverable` failure compensates the failing Step itself first (when it is mutating and its `execute` ran) and `runs recover --rollback` also compensates a mutating Step the journal left `RUNNING` or `FAILED`. Draft 1.1 compensated only succeeded Steps, which left a half-swapped `WEB-INF/lib` or a half-migrated webapp in place and then restarted the service on it while reporting exit 3. Recorded in ADR-0009; found as item 1.9 of `docs/reviews/2026-09-10-codebase-review.md`.

## Draft 1.1 — 2026-09-08

Applies every recommendation from `docs/spec-review-2026-09-08.md`. Item references (A1, B3, …) point at that review.

### Scope (A1–A7, §1.2, §1.3, §4, §14)
- Removed GraalVM native-image, ARM64, MSI/EXE/DEB/RPM installers, reproducible-build check, and OS keyrings from v1; each is now an explicit non-goal to be recorded as ADR-0001..0006.
- Phase 7 delivers portable ZIP/tar.gz with a jlink runtime only.
- Four version adapters collapsed into one capability-driven `RestJrsAdapter`; quirk classes added only on demonstrated drift.
- Modules reduced from nine to six: `core`, `jrs`, `ops`, `app`, `dist`, `acceptance`. Engine lives in `core`; CLI and console live in `app`.
- Phases reordered: Hotfix (3) before Export/Import (4); Upgrade (5) before Console (6).

### Correctness (B1–B8)
- §10.1: `--mode newdb` is the default; `samedb` requires `--db-backup-confirmed` and the Plan states that database rollback is the operator's responsibility.
- §5.3, §8.1, §8.2: replace-on-restart strategy removed; any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service on both OSes; `restart: none` validated against that rule.
- §5.1, §8.1, §8.2: `database` config section added; JDBC driver loaded from the JRS buildomatic tree; SQL must be idempotent and carry `rollbackFile` or the bundle is marked irreversible; transactional-SQL promise removed.
- §5.4, §5.5: JSONL journal removed; SQLite `step_transitions` (WAL, `synchronous=FULL`, append-only) is the single source of truth; JSONL is only a support-bundle export.
- §6.1: every `Step.execute` must be idempotent; resume re-executes the interrupted step; crash-injection tests verify convergence.
- §5.1, §5.3: `service.kind` with `windows-service | systemd | ctlscript | catalina | manual`; state polled, never assumed.
- §5.5: `runs.lock` file lock; exit code 9.
- §5.5: pending recovery in non-interactive mode fails with exit code 8.

### Design simplifications (C1–C19)
- §15, §16: Java 21. §5.1: `vendor.javaHome` for buildomatic, verified by `doctor` against the matrix.
- §11.1, §13.3: BouncyCastle removed; JDK Ed25519 and AES-GCM used.
- §5.2: secret sources are `env:`, `file:`, `enc:`; non-interactive passphrase via `JRSCTL_PASSPHRASE` or `--passphrase-file`.
- §7.3: `VendorCliAdapter` replaced by `ExportImportStrategy { Rest, VendorCli }`; vendor strategy always stops the service.
- §8.1: signature covers `manifest.json` only; manifest hashes cover `payload/`, `sql/`, `checks/`; unlisted files fail verification.
- §8.1: `files[].action` (`add | replace | delete`) mandatory.
- §5.4, §8.3: `hotfix_files` table; file-overlap conflict on apply; LIFO rollback with `--cascade`.
- §10.2: hotfix reapply and customization reapply are report-first; 3-way comparison for customizations; `--reapply-hotfixes` for non-interactive runs.
- §9.4: import rollback documented as best-effort in the Plan summary and operator guide.
- §5.1, §9.3: `server.runAsUser`; keystore resolved from that account's home.
- §5.7: compat matrix shipped unsigned.
- §0 rule 4: Plan/Step applies to mutating operations only.
- §5.1, §7.5: isolated mode defined as an HTTP host allowlist and enforced in the client.
- §11.2: per-launch console bearer token on all binds; `Host` header check; CSRF section replaced.
- §6.1: `CheckResult` for pre/postcheck; `phase()` on Step; sealed `StepFailure`; `StepRollbackFailed` event.
- §6.2: `PlanFingerprint` inputs enumerated; plans stored with a 30-minute TTL for the console.
- §5.9: sealed typed `Event` hierarchy replaces `Map<String,Object>`.
- §5.3, §15: streaming I/O requirement; Phase 1 acceptance includes a >2 GB hash under a heap cap.
- §5.8: redaction covers raw, Base64 and URL-encoded forms.

### Missing pieces (D1–D6)
- §8.4: `jrsctl hotfix build`.
- §12.0: `jrsctl init` detection wizard.
- §12.2: `smoke.reportUri` configurable; WARN if absent; PDF check specified.
- §5.1: `server.webappName` explicit.
- §18: exit codes 8 and 9; note that override flags do not change the exit code.
- §7.4, §19 Q5: `default_master.properties` written into the invoked buildomatic directory with snapshot/restore until Q5 is answered.

### Process (E1–E6)
- §0 rule 1: local acceptance gates the next phase; both-OS CI gates merge.
- §0 rule 10, §14, §16: `needs-jrs` is a release gate, never a phase gate; §19 Q6 names the image question.
- §0 rule 12: Windows 11 is the dev machine; Docker Desktop; `taskkill /F`.
- §0 rule 11: signing executes in CI only; local artifacts unsigned and recorded.
- §13.3: `logstash-logback-encoder`, `networknt json-schema-validator`; `bouncycastle` and `jna` listed as not approved without ADR.
- §0 rule 13, §17: spec lives at `docs/spec.md` with this changelog.

### Minor (F)
- §5.1: `auth.mode` default `basic`.
- §12.3: `selfcheck` verifies per-jar hashes against a build-time manifest.
- §8.1: `id` is the state-store key; `applies.versions` governs applicability.
- §5.6: retention also protects the most recent successful upgrade's snapshots.

## Draft 1.0 — 2026-09-08

Initial build contract. Preserved as `jrsctl-technical-design-spec.v1.0-backup.md` alongside the original in Downloads.
