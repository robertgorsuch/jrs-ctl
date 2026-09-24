# ADR-0038: remove the web console; jrsctl is a CLI

Status: proposed · Date: 2026-09-24 · Spec: §1, §4, §11.2, §13, §14 phase 6 · Issues #75, #64, #150, #151 · Supersedes ADR-0007 · Narrows ADR-0023 · Plan: `docs/superpowers/plans/2026-09-24-remove-web-console.md`

## Context

Spec §1 describes jrsctl as "a single self-contained application with a CLI and a local web console",
and §13 specifies that console: a Javalin server behind a per-launch bearer token, 25 endpoints, an
SSE stream per run, and a static single-page UI served from the jar. Phase 6 shipped it, and it has
been maintained through every release since (39 commits between 2026-09-08 and 2026-09-22, three
security fixes in `docs/BUILD_STATUS.md`, the Javalin 7 migration and the Kotlin stdlib pin forced by
the OWASP dependency audit).

The intended operator runs jrsctl on the server that hosts JasperReports Server. On Linux that server
has no desktop. Issue #64 documented the workaround (start the console with `--no-open`, reach it from
the operator's own machine through an SSH tunnel) and the field test of 1.3.0 and 1.4.0 on a headless
Linux VM (issue #75) found that the support engineer did not use it and asked for a terminal
interface instead. Guided mode (#71, ADR-0023 option B, ADR-0037) has since become the interactive
front end. The console is the only surface the field tests never exercised.

What the console costs today, measured on `main` at 76275f4:

| Area | Size |
|---|---|
| `app/console` Java | 38 classes, 4,457 lines (one third of the app module's main code) |
| Static UI under `app/src/main/resources/web` | 16 files, 4,672 lines |
| Tests | 9 app test classes, 2,317 lines, plus `Phase6ConsoleTest` (277 lines) |
| JSON schemas | 17 `api-*.schema.json` plus `console.schema.json` |
| Runtime dependencies | Javalin, Jetty and the Kotlin stdlib, about 9.7 MB of the 30 MB shaded jar |
| Configuration | the `console:` block (7 keys, `JRS_CONSOLE_PASSWORD`) in core `Config`, `ConfigLoader`, `ConfigWriter` and app `ConfigKeys` |
| Threat model | four rows of `docs/security.md` (DNS rebinding, network exposure, token on disk, TLS material) that exist only because a socket listens |

Nothing in `core`, `jrs` or `ops` depends on the console. `PlanRegistry`, `RunService` and `PlanJson`
in `ops` are shared with the CLI's `PlanExecutor` and stay. Javalin is loaded only under the
`console` command, so startup (ADR-0035) is unaffected either way.

One capability exists only behind the console: the support bundle (`GET /api/runs/{id}/support-bundle`,
`SupportBundle` and `VendorLogs` in `app.console`, and the operator guide's support section sends the
operator there). Everything else the console offers has a CLI counterpart: `runs show --json` for the
run document, `runs recover --resume|--rollback` for the buttons, `doctor`, `smoke`, `customizations`,
`runs prune`, `config show`, `selfcheck`, `keys list`. The repository folder picker
(`GET /api/repository/tree`) and the doctor cache are conveniences of the form UI with no CLI need.

## Options considered

### Option A: remove the console entirely (chosen)

Delete the package, the UI, the endpoints, the schemas, the dependencies and the configuration block;
first give the support bundle a CLI command. About 4 to 5 engineering days; ships as 2.0.0 since a
documented command and HTTP API disappear.

Pros: about 12,000 lines and 30 percent of the jar gone; no listening socket, token file or TLS
material in the threat model; no further Jetty and Kotlin audit churn; one front end to keep correct;
`verify` loses one acceptance fork and the largest app unit test.

Cons: no remote live view of a run other than the log file and `runs show`; an existing `config.yaml`
with a `console:` block needs handling (below); anyone who scripted the API loses it; after a few
releases a revert is a re-port, not a `git revert`.

### Option B: keep the console as it is

Zero effort now. The audit churn, the security surface and the unused code stay, and every new
command or JSON field keeps paying the "both front ends" tax (`ConsoleSchemaTest`, the typed `*Doc`
records, the form views).

### Option C: move the console to an optional artifact

Split `app.console` and the UI into a separate jar or a `jrsctl-console` distribution flavour. Keeps
the option open at the cost of a second build product, a second SBOM and signature, the same
dependencies to audit, and the CLI still needing a stable service layer for it. The field test
evidence does not justify carrying it.

## Decision

Remove the web console. jrsctl is a command-line tool with guided mode as its interactive front end.

1. **Support bundle first.** `SupportBundle` and `VendorLogs` move to `app` and become
   `jrsctl runs support-bundle <id> [--out <zip>]`, with its `--json` document and schema, a help
   example, and a unit test that asserts the entries and the absence of every registered secret (the
   existing `ConsoleServerTest` case, re-homed). This lands and is released before anything is deleted,
   in its own PR, so no release ever lacks the bundle.
2. **Delete the console.** `app.console`, `resources/web`, `ConsoleCommand`, `ConsoleShutdown`, the
   nine console test classes, `Phase6ConsoleTest` and the `phase6` acceptance directory, the 18
   schemas and their `JsonSchemas` entries, the `console` help example, the guided-mode footer
   sentence, the `web/app.js` and `mock.js` assertions in `Phase0SkeletonTest`, and the shade
   exclusions for `web/mock.js` and `web/README.md`. `fast.sh` and `fast.cmd` drop `ConsoleSchemaTest`
   from `guards`.
3. **Dependencies.** Javalin leaves `app/pom.xml` and the root `dependencyManagement`; the Kotlin
   stdlib pin and the Jetty comments go with it; `dist/src/image/LICENSE-THIRD-PARTY.txt` drops the
   three rows. Spec §13.3 removes `javalin` from the approved runtime list. The jlink module list is
   derived by `jdeps` at build time (ADR-0008); `java.net.http` stays because the REST client uses
   it, and the PR records the `dist.modules` result.
4. **Configuration.** `ConfigLoader` refuses unknown keys and `config.schema.json` is
   `additionalProperties: false`, so dropping the block outright would stop every existing
   `config.yaml` that has one. For 2.0.0 the `console:` block is accepted and ignored with one warning
   naming this ADR (`console.* is no longer used; remove it from config.yaml`), `config set console.*`
   is refused with the same message, and `config keys` no longer lists the keys. The block is removed
   from the loader in the release after. `Config.Console`, `Config.ConsoleAuth`, `ConsoleAuthMode` and
   `Config.Tls` leave the record now; `Config.secretRefs()` no longer includes the console password.
5. **Documentation and spec.** Draft 1.2: §1 and §4 describe one front end; §11.2 and §13 are
   replaced by a pointer to this ADR; §14 phase 6 becomes "removed, see ADR-0038"; §5 drops
   `console.token` from the home layout. README loses "Use the web console"; the operator guide loses
   the `console` command, the `console.token` row and points the support section at the new command;
   `docs/security.md` loses the four rows and the token lifecycle section; ADR-0007 is marked
   superseded; `docs/BUILD_STATUS.md` and `docs/spec-changelog.md` record the change.
6. **Release.** 2.0.0. The release notes say what the console did, what replaces each part, and that
   `console:` in `config.yaml` is ignored until the next release.

Order of work: PR 1 (step 1) merges and ships in the next minor. PR 2 (steps 2 to 6) follows on its
own branch; it is one logical change, large and almost entirely deletions, and merges after a full
`verify`, a green ubuntu leg and the Linux laptop gate.

## Consequences

- The only interactive front end is guided mode. Issue #75 (full-screen terminal UI) stays open and
  is the place to decide whether a live dashboard is still wanted; a terminal UI would build on
  `ops` directly, as guided mode does, so nothing removed here would have been its foundation. ADR-0023
  option A is unchanged.
- No remote view of a run in flight. The run log under the home directory and `runs show --json`
  remain, and both are what the support bundle packages.
- The threat model shrinks to the local files, the vendor tools and the REST client. `OwnerOnlyFiles`
  no longer has a token file to guard.
- A `config.yaml` written by 1.x keeps working for one release with a warning.
- Anyone who scripted `/api/*` loses it; there is no evidence of such a user and the API was never
  a documented integration surface beyond the operator guide's support paragraph.
- Reversal: the code stays in history at 76275f4. Reinstating it after the config block is gone and
  the `ops` services have moved on is a port, so this decision should be treated as final unless #75
  concludes that a browser UI is what operators want after all.

## Action items

1. [ ] PR 1 (issue #150): `runs support-bundle` command, schema, help example, test; operator guide
       support section; ships in the next minor.
2. [ ] PR 2 (issue #151): remove `app.console`, the UI, the tests, the schemas, the dependencies;
       tolerated `console:` block with a warning; spec Draft 1.2; docs; ADR-0007 superseded; `fast`
       guards list; 2.0.0.
3. [ ] Release after: drop the tolerated `console:` block from `ConfigLoader` and `config.schema.json`.
4. [ ] Revisit #75 with the field testers once 2.0.0 is in their hands.
