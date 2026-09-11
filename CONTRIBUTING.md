# Contributing to jrsctl

jrsctl is the JasperReports Server lifecycle tool from Actian Jaspersoft. It stops services,
swaps files under `WEB-INF`, runs vendor upgrade scripts and re-imports repositories, so the
project puts execution safety, idempotent steps, honest rollback and credential hygiene ahead of
everything else. This guide says how to build, test and submit a change that keeps those
promises. The contract is `docs/spec.md` (Draft 1.1); `CLAUDE.md` at the repository root is the
short form every contributor and agent follows.

## Before you start

- Read `docs/spec.md` before changing architecture, and `docs/decisions/` before proposing a new
  third-party dependency, a new platform, or a change to the module layout. The approved
  dependency list is spec §13.3; anything else needs an ADR first.
- `docs/BUILD_STATUS.md` says what is done, what is stubbed and what is known to be missing.
  `docs/reviews/` holds the code reviews and the assessment that drive the current follow-ups.

## Repository layout

Six Maven modules, dependencies flowing one way (spec §4):

| Module | Owns |
|---|---|
| `core` | configuration and schema, secrets, `Platform` (file, process and service abstractions), the SQLite run journal, snapshots, the compatibility matrix, redaction, the sealed `Event` types, the engine (`Plan`, `Step`, `Runner`, retry, cancellation, `EventBus`), the run lock |
| `jrs` | REST v2 client, the capability-driven `RestJrsAdapter`, probes, the export/import strategies, vendor-tool wrappers, keystore inspection |
| `ops` | `hotfix`, `export`, `import`, `upgrade`, `customizations` as plans; `init`, `doctor`, `smoke` as reports |
| `app` | picocli commands, `--json`, the progress renderer, the Javalin console with SSE and the static UI, the support bundle, `Main` |
| `dist` | jlink image, portable ZIP and tar.gz, SBOM, checksums; signing runs in CI only |
| `acceptance` | `PhaseNXxxTest` classes tagged `phaseN`, run against the shaded jar `app/target/jrsctl.jar` |

Package root `com.jaspersoft.jrsctl.<module>`. The acceptance module never imports app classes: it
drives the packaged jar the way an operator would.

## Building

- Java 21 and Maven 3.9 or newer. The machine's default `java` may be older; the wrapper scripts
  select JDK 21 for you.
- Windows: `scripts\mvn.cmd`; Linux: `scripts/mvn.sh`. Both delegate to the Maven Wrapper
  (`mvnw`, `mvnw.cmd`), which you can also call directly once `JAVA_HOME` points at JDK 21.
- `scripts\mvn.cmd verify` is the gate: compile with `-Werror` and Error Prone, unit tests,
  Spotless (google-java-format), then every acceptance phase against the shaded jar. It takes
  about eight minutes on a laptop.
- One acceptance phase only: `scripts\mvn.cmd verify -Dphase=N`.
- Format before committing: `scripts\mvn.cmd spotless:apply`. The pre-commit hook in `.githooks`
  runs the Spotless check; enable it once with `git config core.hooksPath .githooks`.
- Tests tagged `needs-jrs` (a real JasperReports Server) or `needs-docker` are excluded by default;
  they are release gates, not phase gates (spec §0 rule 10).
- Distribution: `scripts\build-dist.cmd` or `scripts/build-dist.sh` (Maven profile `dist`).
- Windows and Linux on x86-64 are the supported platforms (ADR-0002); nothing else is built or
  tested.

## Rules that are not negotiable (spec §0, §6, §15)

- Mutations only inside a `Step`. Every `Step.execute(Context, EventSink)` is idempotent and has a
  `compensate` that converges from any partial state, or the step is `irreversible()` with a
  justification comment. The Runner compensates the failing step first, then the succeeded ones
  (ADR-0009), and `runs recover` re-runs compensations, so a compensation may run more than once.
- Read-only operations (`init`, `doctor`, `smoke`, `selfcheck`, `list`) are plain functions that
  return a report; they never write.
- Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first, on both platforms.
- SQLite `state.db` is the only run journal: WAL, `synchronous=FULL`, append-only step
  transitions written before the matching event is emitted.
- No `Runtime.exec(String)`, no shell; arguments as lists through `Platform.processes()`. No
  `java.io.File`; streaming I/O only.
- Secrets live in `char[]`, are never logged, and every output stream passes the redaction filter.
  The Runner redacts every event before any subscriber sees it; do not rely on a sink to redact.
- No new third-party dependency without an ADR. BouncyCastle and JNA are not approved.
- When the spec is ambiguous, take the safer option, write `docs/decisions/NNNN-title.md`, and
  continue.

## Conventions

- Java 21: records and sealed interfaces; pattern-matching `switch` with no `default` over a
  sealed type; no `null` returns from public APIs (`Optional` or a sealed result).
- One-paragraph Javadoc stating the invariants on every public class.
- Test names read `should_<behaviour>_when_<condition>`. Acceptance tests use a verb phrase that
  names the observable outcome.
- Write the test first and watch it fail before writing the fix. A test that passes on the first
  run proves nothing; the reviews in `docs/reviews/` cite several defects that only a failing test
  would have caught.
- Every mutating `Step` needs an execute-twice test and a compensate-twice test;
  `IdempotencyCoverageTest` in `ops` scans the classpath and fails when a step has none. A step
  that can fail part-way (file swap, SQL, vendor script) also needs a test that drives that failure
  through the `Runner` and checks the server afterwards.
- Adapter changes are tested against WireMock fixtures recorded from real servers
  (`jrs/src/test/resources/fixtures/`), one per supported version and edition.

## Commits and pull requests

- Conventional Commits, one logical change per commit: `fix(core): ...`, `feat(app): ...`,
  `test(ops): ...`, `docs: ...`. The subject says what changed; the body says why and how it was
  verified.
- One branch per phase or per fix. Work on `main` is accepted only for the maintainer's own phase
  boundaries.
- Before opening a pull request: `scripts\mvn.cmd verify` green locally; `docs/BUILD_STATUS.md`
  updated when a phase, a gap or a review follow-up changed; `docs/spec-changelog.md` and the
  affected ADR updated when the contract changed; `docs/operator-guide.md` updated when a command,
  flag or exit code changed (the guide is embedded in the jar and `--explain` prints its sections,
  so `Phase8ExplainDocsTest` fails when a command lacks its section).
- CI (`.github/workflows/ci.yml`) runs the full gate on Ubuntu and Windows, packages both
  archives, and audits dependencies. A pull request must be green on both operating systems.

## Reporting a problem

- Bugs and feature requests: open a GitHub issue with the jrsctl version (`jrsctl --version`),
  the operating system, the JasperReports Server version and edition, the exact command, and the
  relevant lines of `logs/jrsctl.log` (already redacted). A support bundle from the console
  contains all of that.
- Security vulnerabilities: do not open a public issue. Use GitHub's private vulnerability
  reporting for this repository (Security tab, "Report a vulnerability"), as described in
  `docs/security.md`.

## Code of conduct

`CODE_OF_CONDUCT.md` applies to every issue, pull request and discussion.
