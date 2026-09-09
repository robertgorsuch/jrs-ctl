# jrsctl hotfix authoring guide

This guide is for people who write hotfix bundles for JasperReports Server: Jaspersoft support and engineering, and customers who package their own fixes. It covers the bundle format, the manifest and the rules `jrsctl` enforces on it, building and signing a bundle with `jrsctl hotfix build` and `jrsctl keys`, and how to test a bundle before handing it to an operator. The normative text is spec §8 (hotfix subsystem) and §11.1 (bundle signing); the schema is `core/src/main/resources/schema/hotfix-manifest.schema.json`. This guide is embedded in the tool: `jrsctl docs hotfix-authoring`.

## What a hotfix is, in jrsctl terms

A hotfix is a **signed ZIP bundle** that replaces, adds or deletes files under the server's Tomcat or installation directory, optionally runs SQL against the repository database, and optionally checks the server before and after. `jrsctl hotfix apply` turns it into a plan of idempotent steps with snapshots and compensation; `jrsctl hotfix rollback` undoes it later from those snapshots. Everything an operator sees in the plan (files, service restart, SQL, rollback promise, applicability) comes from the manifest you write, so the manifest is the contract.

The author's responsibilities, in short:

- list every file the bundle ships and say what happens to it (`add`, `replace`, `delete`);
- say honestly whether the service must be stopped (`restart`), and it must be for anything under `WEB-INF/lib` or `WEB-INF/classes`;
- make every SQL script idempotent and give it a rollback script, or declare the hotfix irreversible and say why;
- describe exactly which server versions, editions and tenancy modes the fix applies to;
- sign the bundle with a key the operator trusts.

## Bundle layout

```
<bundle dir>/
  manifest.json        # the contract, see below; `hotfix build` fills in every sha256
  payload/             # files mirrored to their destination, relative to the Tomcat (or install) dir
    webapps/jasperserver-pro/WEB-INF/lib/foo-1.2.3.jar
    webapps/jasperserver-pro/WEB-INF/classes/fix.properties
  sql/                 # optional: ordered *.sql per database, with rollback scripts
    postgresql/001.sql
    postgresql/001-rollback.sql
    mysql/001.sql
    mysql/001-rollback.sql
  checks/              # optional: JSON check definitions referenced by the manifest
    scheduler.json
```

`jrsctl hotfix build` zips this directory as:

```
manifest.json          # first entry, with every sha256 completed
SIGNATURE              # second entry: detached Ed25519 signature over manifest.json, base64
payload/...            # exactly the files the manifest lists
sql/...
checks/...
```

The signature covers `manifest.json` only; the manifest carries the SHA-256 of every file under `payload/`, `sql/` and `checks/`. Verification checks the signature, then every listed hash, and **fails on any file in the ZIP that the manifest does not list**. `hotfix build` applies the same rule to the source directory: a stray file (an editor backup, a `.DS_Store`, a README) is refused with `files not listed in the manifest: ...`; remove it or list it.

Paths are relative, use `/`, and may not start with `/`, contain `..` or contain `:`. A path under `payload/` is mirrored to the same path under the server's Tomcat directory (`server.tomcatDir`), so `webapps/jasperserver-pro/WEB-INF/lib/foo.jar` lands in Tomcat's `webapps/jasperserver-pro/WEB-INF/lib/`. Use the webapp name the target edition actually has: `jasperserver-pro` for PRO, `jasperserver` for CE; a bundle for both editions needs two file entries or two bundles.

## The manifest

```json
{
  "id": "JRS-10.0.0-HF-0007",
  "version": "1",
  "title": "Fix scheduler NPE on paused jobs",
  "description": "Backport of JS-61234. Replaces the scheduler jar and adds one property.",
  "applies": {
    "versions": [">=10.0.0 <10.1.0"],
    "editions": ["PRO"],
    "tenancy": ["SINGLE", "MULTI"]
  },
  "requires": ["JRS-10.0.0-HF-0003"],
  "conflicts": [],
  "files": [
    { "action": "replace", "path": "webapps/jasperserver-pro/WEB-INF/lib/js-scheduler-10.0.0-hf7.jar", "replaces": ["js-scheduler-10.0.0.jar"] },
    { "action": "add",     "path": "webapps/jasperserver-pro/WEB-INF/classes/scheduler-fix.properties" },
    { "action": "delete",  "path": "webapps/jasperserver-pro/WEB-INF/lib/js-scheduler-compat-0.9.jar" }
  ],
  "sql": [
    { "db": "postgresql", "file": "sql/postgresql/001.sql", "idempotent": true, "rollbackFile": "sql/postgresql/001-rollback.sql" },
    { "db": "mysql",      "file": "sql/mysql/001.sql",      "idempotent": true, "rollbackFile": "sql/mysql/001-rollback.sql" }
  ],
  "checks": [
    { "file": "checks/scheduler.json" }
  ],
  "restart": "required",
  "prechecks": [
    { "type": "fileExists", "path": "webapps/jasperserver-pro/WEB-INF/lib/js-scheduler-10.0.0.jar" },
    { "type": "http", "url": "/rest_v2/serverInfo", "expect": 200 }
  ],
  "postchecks": [
    { "type": "http", "url": "/login.html", "expect": 200 },
    { "type": "sha256", "path": "webapps/jasperserver-pro/WEB-INF/classes/scheduler-fix.properties", "sha256": "<filled by build>" }
  ],
  "rollback": "snapshot"
}
```

You may leave every `sha256` (and `rollbackSha256`) out of the source manifest: `hotfix build` computes them from the files and writes the completed manifest into the bundle. A `sha256` you do write must match the file, or the build is refused.

### Fields

| Field | Required | Meaning and rules |
|---|---|---|
| `id` | yes | The hotfix identity and the state-store key: `JRS-<major>.<minor>.<patch>-HF-<nnnn>` (for example `JRS-10.0.0-HF-0007`). The version in the id is the product line the fix was made for; `applies.versions` decides where it installs. Reusing an id for different content is never acceptable: `hotfix list` and `requires` work on ids. |
| `version` | yes | Manifest format version; always `"1"`. |
| `title` | yes | One line (1–200 characters) shown in `hotfix list`, `hotfix verify` and the plan summary. |
| `description` | no | Free text for the operator; shown by `hotfix verify`. |
| `applies.versions` | yes | One or more npm-style semver ranges, e.g. `">=10.0.0 <10.1.0"`, `"8.2.x"`, `"9.0.0 || 9.0.1"`. The server version is coerced (`8.2` reads as `8.2.0`). The bundle applies when any range matches. |
| `applies.editions` | no | `CE` and/or `PRO`; empty or absent means any edition. |
| `applies.tenancy` | no | `SINGLE` and/or `MULTI`; empty or absent means either. |
| `requires` | no | Hotfix ids that must be `INSTALLED` before this one. A hotfix cannot require itself. |
| `conflicts` | no | Hotfix ids that must **not** be installed. A hotfix cannot conflict with itself. Overlapping files with an installed hotfix are refused at apply time regardless of this list. |
| `files[]` | yes (may be empty for an SQL-only fix) | See "File entries". |
| `sql[]` | no | See "SQL entries". |
| `checks[]` | no | Check-definition files shipped under `checks/`; each needs `file` (`checks/*.json`) and gets a `sha256`. |
| `restart` | yes | `required` or `none`. See "Service restart". |
| `prechecks[]`, `postchecks[]` | no | Inline checks run before the first mutation and after the service is back; see "Checks". |
| `rollback` | yes | `snapshot` (default promise: every file is restorable from its snapshot and every SQL script has a rollback) or `irreversible`. |
| `rollbackNote` | when `rollback` is `irreversible` | Plain text shown in the plan summary explaining what cannot be undone and what the operator must do instead. Must not be blank. |

The schema has `additionalProperties: false` at every level: a misspelled key is an error, not a silent no-op.

### File entries

| Key | Rules |
|---|---|
| `action` | Mandatory. `add` (the path must not exist on the server), `replace` (it must exist and is snapshotted first) or `delete` (it must exist, is snapshotted, then removed). |
| `path` | Destination relative to the Tomcat directory; no leading `/`, no `..`, no `:`; listed once. For `add` and `replace` the same path under `payload/` must exist in the bundle directory; for `delete` it must **not**. |
| `sha256` | Required for `add` and `replace` (the build fills it in); not allowed for `delete`. |
| `replaces` | Optional, `replace` and `add` only: plain file names (no directories) of sibling files the new file supersedes, e.g. the old jar name when the version is in the file name. `hotfix apply` removes them from the same directory after snapshotting; the upgrade reconciler uses them to decide whether the hotfix is still `REAPPLICABLE` on the new version. A `delete` entry cannot list `replaces`. |

Library jars with the version in their name are the common case: ship the new jar as `replace` (or `add`) and name the old jar in `replaces`; never ship a jar that would sit next to its older self on the classpath.

### Service restart

`restart: required` makes the plan stop the service before the first file is swapped and start it (and wait for it to answer) afterwards. **Any file under `WEB-INF/lib` or `WEB-INF/classes` requires it**; a manifest that declares `restart: none` for such a file is refused by `hotfix build` and again by `hotfix apply` with `restart 'none' is not allowed for files under WEB-INF/lib or WEB-INF/classes (spec §5.3)`. There is no replace-on-restart: jrsctl never leaves a file to be picked up "next time Tomcat restarts". Files that Tomcat reads at request time (themes, JavaScript, JSP, images, `WEB-INF/bundles` properties reloaded by the application) may use `restart: none` and are swapped with the service running. When in doubt, require the restart; the plan tells the operator before anything happens.

### SQL entries

| Key | Rules |
|---|---|
| `db` | `postgresql`, `mysql`, `oracle`, `mssql` or `db2`. Only the entries matching the configured `database.type` run, in manifest order; a bundle may ship scripts for several databases. |
| `file` | `sql/<...>.sql` inside the bundle. |
| `sha256` | Filled by the build. |
| `idempotent` | Must be `true`, and the script must actually be so: running it twice leaves the database as after running it once (`CREATE TABLE IF NOT EXISTS`, `INSERT ... WHERE NOT EXISTS`, guarded `ALTER`). `runs recover --resume` re-executes an interrupted SQL step. |
| `rollbackFile`, `rollbackSha256` | The script that undoes `file`, also idempotent. Required unless the manifest declares `"rollback": "irreversible"` with a `rollbackNote`. The build fills `rollbackSha256` in. |

There is no transactional promise across scripts: DDL auto-commits on several supported databases, so write every script to be safe to re-run from any point. SQL in a bundle requires the operator's configuration to have a `database` section; `hotfix apply` refuses with a remediation when it is absent. Scripts run with the configured repository credentials; do not assume superuser rights.

### Checks

Inline `prechecks` run before the first mutation (after the snapshot phase has been planned) and `postchecks` after the service is back up. A failing precheck stops the run with exit 2 and nothing changed; a failing postcheck fails the `apply` phase and triggers rollback.

| `type` | Keys | Passes when |
|---|---|---|
| `fileExists` | `path` (relative to the Tomcat directory, same rules as file paths) | the file exists |
| `fileAbsent` | `path` | the file does not exist |
| `http` | `url` (must start with `/`, relative to `server.baseUrl`), `expect` (100–599, default 200) | the server answers with that status |
| `sha256` | `path`, `sha256` | the file has that hash |

Use `fileExists` prechecks for the files a `replace` needs (so an operator with a differently patched server gets a clear refusal instead of a half-applied bundle), `http` postchecks for the pages the fix touches, and a `sha256` postcheck when a file is written by something other than the file swap (for example a properties file the SQL step also rewrites).

### Irreversible hotfixes

Set `"rollback": "irreversible"` only when a SQL change cannot be undone by a script (a data migration, a destructive `DROP`). The files of such a hotfix are still snapshotted and restored on rollback; the note is there for the database. The plan summary prints your `rollbackNote` as a warning and the operator must confirm it; write it for them: what will remain after `hotfix rollback`, and what they must do (for example "restore the repository database from the backup taken before this hotfix").

## Building and signing

### 1. Get a signing key

Operators trust bundles signed by keys in their key ring (`jrsctl keys list`). The Jaspersoft publisher key is bundled with the tool; customer-built bundles need a customer key that the operator adds.

```
jrsctl keys generate customer --private-out C:\secure\customer-hotfix.key
```

This trusts the public key under the name `customer` on *this* machine and writes the private key once, owner-only, as one base64 PKCS#8 line. Keep it outside the jrsctl home and out of version control; it is never written anywhere else. To let another machine verify your bundles, give its operator the matching public key (`<home>/keys/trusted/customer.pub`) and have them run `jrsctl keys add customer customer.pub`. To store the private key in the encrypted store instead of a file: `jrsctl secrets set hotfix-key --from-file C:\secure\customer-hotfix.key`, then reference it as `enc:hotfix-key`.

### 2. Build

```
jrsctl hotfix build C:\work\JRS-10.0.0-HF-0007 --key file:C:\secure\customer-hotfix.key --out C:\out\JRS-10.0.0-HF-0007.zip
```

`hotfix build` validates the manifest against the schema, completes the hashes, applies the semantic rules above (and lists every problem at once), refuses unlisted or missing files, signs `manifest.json` with the private key and writes the ZIP. The source directory is not modified. Exit 0 on success; 1 for an unparseable `--key` reference; 2 for any manifest, file or key problem. `--key` accepts `file:/path`, `env:NAME` or `enc:NAME`; the key text is the one-line base64 PKCS#8 written by `keys generate`.

Build on a machine that has jrsctl but not necessarily a server: `hotfix build` needs no configuration and no server.

## Testing a bundle

Test on a staging server that matches the target version and edition, with the same jrsctl version the operators use.

1. **Verify the bundle as the operator will.** `jrsctl hotfix verify C:\out\JRS-10.0.0-HF-0007.zip` checks the signature against the key ring, every hash, and applicability to the configured server; it mutates nothing and exits 0 only when all three pass (7 otherwise). `--json` gives the machine-readable report. Run it once on a server the bundle should *not* apply to and confirm `applicability` fails with the right message.
2. **Read the plan.** `jrsctl hotfix apply C:\out\JRS-10.0.0-HF-0007.zip --plan` prints the whole plan (phases `verify`, `backup`, `apply`, `record`; every file; the service stop and start; the SQL scripts for the configured database; the rollback promise and your `rollbackNote` if any) and exits 0 without running anything. Check that the plan says what you intended: the right webapp, the right jars in `replaces`, a restart where `WEB-INF` is touched. `--explain` on the same command prints the operator guide section for `hotfix apply` if you want the operator's view of the guarantees.
3. **Apply it.** `jrsctl hotfix apply C:\out\JRS-10.0.0-HF-0007.zip --yes` (add `--allow-unsigned` only while iterating on an unsigned build; the override is audited). Exit 0 means applied, postchecks passed and recorded; `jrsctl hotfix list` shows it `INSTALLED`, `jrsctl runs show <id>` every step.
4. **Apply it again.** The second `hotfix apply` of an installed id must be refused cleanly at planning (exit 2, nothing changed); applying on top of a server that already has the files is what `runs recover --resume` relies on, so `add` entries must not be present beforehand and `replace` targets must be what the prechecks expect.
5. **Roll it back.** `jrsctl hotfix rollback JRS-10.0.0-HF-0007 --yes` must restore every file to its exact pre-apply hash (jrsctl verifies this), run your rollback SQL, restart the service when needed and leave the server as it was; `jrsctl smoke` afterwards. Then apply once more to confirm apply-after-rollback works.
6. **Break it on purpose.** Copy the ZIP, change one byte inside a payload file, and confirm `hotfix verify` reports `hashes` FAIL and `hotfix apply` refuses with exit 7. Edit the manifest inside the copy and confirm the `signature` check fails. This is what protects operators from a corrupted download.
7. **Interrupt it.** On staging, kill the jrsctl process (`taskkill /F` or `kill -9`) during the `apply` phase, then run `jrsctl runs recover <id> --resume` and, in a second attempt, `--rollback`. Both must end with a consistent server; if resume fails on your SQL, the script is not idempotent.
8. **Test the upgrade story** if the fix is meant to survive upgrades: after a staging upgrade, `plan-hotfix-reapply` classifies the hotfix `REAPPLICABLE` only when `applies` matches the new version and every name in `replaces` exists in the new webapp; otherwise it is marked `SUPERSEDED`. Widen `applies.versions` only to versions you have actually tested.

## Delivering a bundle

Ship the ZIP and its SHA-256 (and, for bundles signed with a key the operator does not have yet, the public key to add with `jrsctl keys add`). Tell the operator the id, the `applies` range, whether the service restarts and what the SQL does, and point them to `jrsctl hotfix verify` and `jrsctl hotfix apply --plan` before the real apply. Never ask an operator to use `--allow-unsigned` for a bundle you did not build in front of them.

## Checklist

- [ ] `id` is new, `title` is one clear line, `applies.versions` covers only tested versions.
- [ ] Every shipped file is listed with the right `action`; old jars are named in `replaces`; no stray files in the directory.
- [ ] `restart: required` for anything under `WEB-INF/lib` or `WEB-INF/classes`.
- [ ] Every SQL script is idempotent and has a rollback script, or the manifest is `irreversible` with an honest `rollbackNote`.
- [ ] Prechecks guard the files a `replace` needs; postchecks cover the pages the fix touches.
- [ ] `hotfix build` exits 0; `hotfix verify` exits 0 on the target, 7 on a non-target server.
- [ ] Apply, apply again (refused), roll back, apply again, smoke — all on staging.
- [ ] A tampered copy is refused with exit 7.
- [ ] Private key is outside the jrsctl home and version control; the public key is with the operator.
