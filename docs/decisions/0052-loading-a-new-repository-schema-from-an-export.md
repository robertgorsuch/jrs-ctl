# ADR-0052: loading a new repository schema from an export, and where that lives

Status: accepted (design) · Date: 2026-11-09 · Spec: §10.5 (new) · Closes the option B that ADR-0047 left open · Issue #269

## Context

`jrsctl upgrade --mode newdb` drives `js-upgrade-newdb`, which drops and recreates the database that the installed `default_master.properties` names and imports the point-B export into it. That database is the one the old server uses, so a newdb upgrade cannot leave the old server running (ADR-0047 chose to say so and ask for `--db-rebuild-confirmed`, and left a separate target database as "option B, open").

The vendor documents the other route by hand (upgrade guide 10.1 p.62): on the new version's buildomatic, create an empty schema under a new name, initialise it, load the minimal content, then import the export with `js-import`. Nothing on the old server or its database changes; the new server starts against the new schema beside the old one, is validated for as long as it takes, and traffic moves last. The field team reported in October 2026 that this is how customers going from 9 to 10 usually move, with a separate and often newer database server, and a container deployment has no other route at all, because the vendor's newdb script would have to run inside the image.

The dangerous step is the first one. Every buildomatic target acts on the database that `default_master.properties` names; `create-js-db` or `drop-js-db` pointed at the live database by mistake destroys production. The 2026-10-27 field run (ADR-0047) saw exactly that attempted. Whatever jrsctl adds here must make that mistake impossible rather than merely warned about.

Where the work lives is a second question. The `jrs-upgrade` repository, split out to carry a larger upgrade tool, was paused on 2026-10-27 (bug fixes land on `main` of jrs-ctl only). The `export record` (#263), `import --pause-jobs` (#264) and `import verify` (#262) commands that frame this procedure all landed in jrs-ctl.

## Options

1. **A plan in jrs-ctl `ops`** that drives the vendor tools of a buildomatic tree on a host with no Tomcat installation: create, initialise, load the minimal content, import. New refusals make the live database unreachable from it.
2. **The same in `jrs-upgrade`.** It would reopen a paused repository for one feature and separate it from `import verify` and `export record`, which belong to the same procedure.
3. **Leave it manual.** The operator runs the four vendor commands by hand; jrsctl contributes `export record` before and `import verify` after. Honest, but the step the whole procedure turns on, and the one with the destructive failure mode, stays outside the journal and outside the refusals.

## Decision

Option 1, as a design to be implemented behind its own issue. The shape:

**A tools-only configuration.** `init` learns `--tools-only --buildomatic-dir <dir>` (and `--remote <url>` for the target server's address, since the two go together): it writes `server.baseUrl`, `server.buildomaticDir` and `vendor.javaHome`, and no `installDir`, `tomcatDir` or service. `Config.Server` gains `namesToolsOnly()`: a buildomatic directory with no installation. `doctor` skips `layout`, `service`, `running-tomcat`, `service-manager`, `permissions`, `keystore` and `tomcat` with *tools-only configuration*, and runs `vendor`, `vendor-java` and `database` as it does today. Every command that today requires `namesLocalInstallation()` for the vendor tools keeps requiring it; the new plan is the only one that accepts a tools-only configuration.

**The plan**, working name `jrsctl repository load --schema <name> --from <export> [--include-events] [--key-alias <alias>] [--key-password-ref <ref>] [--plan] [--yes] [--json]`, five phases:

| Phase | Steps | Rollback point |
|---|---|---|
| `preflight` | `doctor`; `verify-buildomatic` (the tree ships `js-ant`, `js-import` and the bundled Ant; `vendor.javaHome` is a Java major the buildomatic's own version accepts, read from its `default_master.properties` or `js.config.properties`); `verify-target-schema` (below); `adopt-export` (the archive exists, is an export, its SHA-256 is recorded, its sidecar is read when present) | nothing mutated |
| `create` | `create-js-db` | the schema this run created is dropped |
| `initialise` | `init-js-db-<ce\|pro>`, `import-minimal-<ce\|pro>` | same |
| `load` | `js-import --input-zip <export> --include-server-settings` with `--include-*-events` on request and `--keyalias` from the flag or the sidecar | same |
| `verify` | `record-load` (what was loaded, where, from which export) | nothing further |

**The refusals in `verify-target-schema`**, all exit 2 before anything is created:

- `js.dbName` in the buildomatic's `default_master.properties` must equal `--schema`. The plan never edits that file to point elsewhere; the operator stages it, and jrsctl checks that the two agree.
- The schema must not exist yet. The step connects with the configured `database` settings (which name the target database server) and asks the catalog (`pg_database`, `information_schema.schemata`, the vendor's per-database equivalents); a schema that exists is refused by name, even if it is empty. There is no `--force`: dropping an existing schema is the DBA's act, by name, with the vendor's `drop-js-db` deliberately not used.
- The schema name must differ from the source server's database. The source is whatever the configuration and the export's sidecar say: `database.url`'s database name when the configuration carries one, the `js.dbName` of any installed buildomatic the configuration names, and the database the sidecar's exporting server identity implies when the sidecar carries it. A match is exit 6, not 2: it is the one mistake this command exists to make impossible, and the message names the collision.
- `appServerType` must be `skipAppServerCheck` in that `default_master.properties` (the WAR is not deployed by this buildomatic), else exit 2 naming the key.

**Compensation.** The `create` step writes `snapshots/<runId>/schema.created` naming the schema after `create-js-db` returns. Every compensation in the `create`, `initialise` and `load` phases is the same: drop the schema **only if** that marker names it, and only after re-reading `default_master.properties` and finding `js.dbName` still equal to it; otherwise the compensation refuses (exit 4 with the schema named and the DBA's command to run). The drop goes through the configured JDBC connection by name, never through `js-ant drop-js-db`, so a stale `default_master.properties` cannot redirect it. `repository load` has no `rollback` subcommand in the first implementation: a failed run drops what it created, and a finished run is undone by the DBA dropping the schema by name.

**What stays outside the plan**, said in the plan summary: the server that will use the schema (deployed by the operator; a container image, say), the superuser and jasperadmin passwords (a plain `js-import` leaves the target's own, as the vendor's manual procedure does), the site's own tables (an import loads repository resources only), and the check that the loaded repository matches the export, which is `jrsctl import verify` against the running target afterwards. Pausing the imported jobs is the server's REST call once it runs (`import --pause-jobs` cannot help here, since the import went through `js-import`); the summary says so and names the call.

**Stored arguments and fingerprint.** `PlanRegistry` gains `repositoryLoadArgs` (schema, export path and hash, events, key alias, key password reference) so `runs recover` rebuilds the plan; the fingerprint covers the buildomatic's `default_master.properties` (minus passwords), the export hash and the schema name.

## Consequences

- The procedure the field team described becomes one journaled run with the destructive mistake refused at plan time, and the same `runs show`, `support-bundle` and recovery as every other plan.
- Three new vendor-tool steps and a JDBC catalog probe need fake-buildomatic tests in the style of `Phase5UpgradeTest`, and `IdempotencyCoverageTest` entries. Nothing in them can be proven against a live installation on the build machine; a field test on a copy of a real repository is the gate before the command leaves the release notes' "verified only against fake vendor scripts" list.
- `init --tools-only` is the first configuration shape with no server installation and no `--remote`-only REST; the operator guide's "two modes" sentence becomes three.
- A later `repository load rollback` (drop by name, with the same marker and `js.dbName` guard) and a `--pause-jobs` equivalent for the vendor route are natural follow-ups and are not part of this design.
- Option B of ADR-0047 is answered by a separate command, not by a mode of `upgrade`: `upgrade --mode newdb` keeps rebuilding the installed server's own database, and the plan summary of a `--tomcat-dir` newdb run points at `repository load` as the way to keep the old server running.
