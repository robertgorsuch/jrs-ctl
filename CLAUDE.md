# jrsctl — agent guide

Single source of truth: `docs/spec.md` (Draft 1.1). Revision log: `docs/spec-changelog.md`. Decisions: `docs/decisions/`. Progress and gaps: `docs/BUILD_STATUS.md`. Read the spec before changing architecture.

## Build

- Always build through `scripts\mvn.cmd` (Windows) or `scripts/mvn.sh`; they select JDK 21. The machine's default `java` is 11.
- `scripts\mvn.cmd verify` = compile (`-Werror`, Error Prone), unit tests, Spotless check, acceptance for every phase.
- One phase only: `scripts\mvn.cmd verify -Dphase=N`.
- Formatting: `scripts\mvn.cmd spotless:apply` before committing (google-java-format).
- Tests tagged `needs-jrs` / `needs-docker` are excluded by default (no Docker here); they are release gates, not phase gates.
- The unit under acceptance test is the shaded jar `app/target/jrsctl.jar`; acceptance never imports app classes.

## Module map (spec §4)

| Module | Owns | Depends on |
|---|---|---|
| `core` | config + schema, secrets, `Platform`, state store (SQLite = journal), snapshots, compat matrix, redaction, sealed `Event`s, engine (`Plan`, `Step`, `Runner`, retry, cancel, `EventBus`), run lock | — |
| `jrs` | REST v2 client, `RestJrsAdapter` (capability-driven, one impl), probes, `ExportImportStrategy` (`Rest`, `VendorCli`), vendor-tool wrappers, keystore inspection | `core` |
| `ops` | `hotfix`, `export`, `import`, `upgrade`, `customizations` → `Plan`; `init`, `doctor`, `smoke` → report | `core`, `jrs` |
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

Java 21; records + sealed interfaces; pattern-matching `switch` with no `default` over sealed types; no `null` returns from public APIs (`Optional` or sealed result). One-paragraph Javadoc stating invariants on every public class. Tests `should_<behaviour>_when_<condition>`. Conventional Commits, one logical change per commit, branch per phase.

## Exit codes (spec §18)

0 ok · 1 usage · 2 precheck/doctor/fingerprint, nothing mutated · 3 failed + rolled back · 4 failed, rollback incomplete · 5 cancelled · 6 unsupported · 7 signature · 8 recovery required · 9 lock held. Constants in `app/ExitCodes`.

## Branding

Product name `jrsctl`, vendor line "Actian Jaspersoft". Colour and type tokens live in `docs/branding.md` and `app/src/main/resources/web/brand.css`; values are provisional until the official brand guide is supplied.
