# jrsctl — agent guide

Single source of truth: `docs/spec.md` (Draft 1.1). Revision log: `docs/spec-changelog.md`. Decisions: `docs/decisions/`. Progress and gaps: `docs/BUILD_STATUS.md`. Read the spec before changing architecture.

## Build

- Always build through `scripts\mvn.cmd` (Windows) or `scripts/mvn.sh`; they select JDK 21. The machine's default `java` is 11.
- **Three tiers; iterate on the first two, leave the third to CI.** A full `verify` takes 7 to 15 minutes; do not run it between edits.
  1. `scripts\fast.cmd test <core|jrs|ops|app> <TestClass[,TestClass]>` (`scripts/fast.sh` on Linux and Git Bash): compile with Error Prone and `-Werror`, then only those classes. About 10 seconds warm, a minute on a fresh checkout. It fails when the name matches no test.
  2. `scripts\fast.cmd guards`: the tests that catch a change that is right in one module and wrong across them: `Phase0SkeletonTest` (the `core.engine` to `core.state` rule), `IdempotencyCoverageTest`, `HelpExamplesTest`, `JsonOutputSchemaTest`. About a minute. Run it before every commit; a new leaf command or JSON field needs it. `scripts\fast.cmd phase <N>` runs one acceptance phase against the shaded jar; `scripts\fast.cmd fmt` formats.
  3. `scripts\mvn.cmd verify` = compile, unit tests, Spotless check, acceptance for every phase, Jacoco floors. Push the branch and read CI (`gh pr checks <n>`, `gh run view <id> --log-failed`) instead of running it locally, unless a failure needs the full log on this machine.
- Do not run `-Dtest=...` through `mvn` directly: it needs `-pl <module> -am`, `-Dsurefire.failIfNoSpecifiedTests=false` and `-Djacoco.skip=true` (a partial run cannot meet the coverage floor). `fast` sets them.
- Formatting: `scripts\mvn.cmd spotless:apply` (or `fast fmt`) before committing (google-java-format). The check runs in the `validate` phase and as the first CI step, so a slip fails in seconds.
- `docs/spec-changelog.md` and `docs/BUILD_STATUS.md` merge with git's `union` driver (`.gitattributes`): a local rebase keeps both sides. GitHub's own merge does not apply it, so rebase locally before merging a PR that touches them.
- Tests tagged `needs-jrs` / `needs-docker` are excluded by default (no Docker here); they are release gates, not phase gates.
- The unit under acceptance test is the shaded jar `app/target/jrsctl.jar`; acceptance never imports app classes.
- Acceptance classes run in four forks at once (`acceptance.forkCount`, ADR-free: they share nothing). A new acceptance test must use its own `@TempDir`, a dynamic port and a process marker that includes its own directory; if one fails only under load, rerun with `-Dacceptance.forkCount=1` before calling it a real failure.

## Module map (spec §4)

| Module | Owns | Depends on |
|---|---|---|
| `core` | config + schema, secrets, `Platform`, snapshots, compat matrix, redaction, sealed `Event`s, engine (`Plan`, `Step`, `Runner`, retry, cancel, `EventBus`, `Journal`, run lock, `Recovery`), state store (SQLite, implements `Journal`) | — |
| `jrs` | REST v2 client, `RestJrsAdapter` (capability-driven, one impl), probes, `ExportImportStrategy` (`Rest`, `VendorCli`), vendor-tool wrappers, keystore inspection, the service stop/start/wait steps every plan shares (`jrs.service`, ADR-0015) | `core` |
| `ops` | `hotfix`, `export`, `import`, `upgrade`, `customizations` → `Plan`; `init`, `doctor`, `smoke` → report; plan rebuilding and run glue both front ends share (`PlanRegistry`, `RunService`, `PlanJson`) | `core`, `jrs` |
| `app` | picocli commands, `--json`, progress renderer, Javalin console + SSE + static UI, support bundle, `Main` | `ops` |
| `dist` | jlink image, portable ZIP/tar.gz, SBOM, checksums; signing runs in CI only | `app` |
| `acceptance` | `PhaseNXxxTest` tagged `phaseN`, run against the shaded jar | all |

Package root `com.jaspersoft.jrsctl.<module>`.

## Non-negotiables (spec §0, §6, §15)

- Mutations only inside a `Step`; every `Step.execute` is idempotent and has a compensation or is `irreversible()` with a justification comment.
- Read-only ops (`init`, `doctor`, `smoke`, `selfcheck`, `list`) are plain functions returning a report.
- Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first, on both OSes. No replace-on-restart.
- SQLite `state.db` is the only run journal (WAL, `synchronous=FULL`, append-only `step_transitions`).
- No `Runtime.exec(String)`, no shell; arguments as lists via `Platform.processes()`.
- All I/O through `Platform`; no `java.io.File`; streaming only, no whole-file byte arrays.
- Secrets in `char[]`, never logged; every output stream passes the redaction filter.
- No new third-party dependency without an ADR (approved list in spec §13.3; BouncyCastle and JNA are not approved).
- Ambiguity → safer option + ADR in `docs/decisions/NNNN-title.md`, then continue.

## Conventions

`core.engine` must not import `core.state`: the engine names the journal it needs and the store implements it (`Phase0SkeletonTest` fails on a new import). Coverage floors live in each module's pom as `jacoco.line.minimum` and run in `verify`.

Java 21; records + sealed interfaces; pattern-matching `switch` with no `default` over sealed types; no `null` returns from public APIs (`Optional` or sealed result). One-paragraph Javadoc stating invariants on every public class. Tests `should_<behaviour>_when_<condition>`. Conventional Commits, one logical change per commit, branch per phase.

## Exit codes (spec §18)

0 ok · 1 usage · 2 precheck/doctor/fingerprint, nothing mutated · 3 failed + rolled back · 4 failed, rollback incomplete · 5 cancelled · 6 unsupported · 7 signature · 8 recovery required · 9 lock held. Constants in `app/ExitCodes`.

## Branding

Product name `jrsctl`, vendor line "Actian Jaspersoft". Colour and type tokens live in `docs/branding.md` and `app/src/main/resources/web/brand.css`; values are provisional until the official brand guide is supplied.
