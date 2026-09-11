# jrsctl operator guide

`jrsctl` is the JasperReports Server lifecycle tool from Actian Jaspersoft. This guide covers every command: tool self-checks, detection and diagnostics, configuration, signed hotfixes, repository export and import, vendor upgrades with rollback and registered customizations, run history, recovery and snapshot retention, trusted keys, the encrypted secret store, the local web console and the documentation embedded in the tool itself.

This guide is embedded in the tool: `jrsctl docs operator-guide` prints it, and `jrsctl <command> --explain` prints the section of this guide that describes that command. No network access is needed for either.

Everything the tool stores lives under one directory, the **jrsctl home** (`--home`, else `$JRSCTL_HOME`, else `%ProgramData%\jrsctl` on Windows or `/var/lib/jrsctl` on Linux, falling back to `~/.jrsctl` when that is not writable):

| Path | Purpose |
|---|---|
| `config.yaml` | the configuration written by `init` and edited by you |
| `state.db` | SQLite journal: runs, step transitions, installed hotfixes, plans, snapshots, audit |
| `runs.lock` | the run lock; holds the run id and pid of the process that owns it |
| `runs/<runId>/` | staging and temp files of one run |
| `snapshots/` | backups taken before files are replaced or deleted |
| `keys/trusted/<name>.pub` | public keys trusted to sign hotfix bundles |
| `secrets.enc` | AES-GCM encrypted secrets referenced as `enc:NAME` |
| `console.token` | the per-launch console token while `jrsctl console` runs (owner-only) |
| `logs/jrsctl.log` | JSON log of every invocation |

## Installing (portable archive)

jrsctl ships as one portable archive per platform (ADR-0003, ADR-0008): `jrsctl-<version>-windows-x64.zip` and `jrsctl-<version>-linux-x64.tar.gz`, each with a `.sha256` sidecar, an SBOM (`jrsctl-<version>-sbom.json`) and, on releases, `.sig` signatures. The archive contains the application and its own trimmed Java 21 runtime; **no JDK, no `JAVA_HOME` and no PATH change are needed on the host**, and nothing is written outside the unpack directory and the jrsctl home.

1. **Verify the download** against the sidecar before unpacking:
   - Windows: `certutil -hashfile jrsctl-<version>-windows-x64.zip SHA256` and compare with the first field of `jrsctl-<version>-windows-x64.zip.sha256`, or in PowerShell `(Get-FileHash jrsctl-<version>-windows-x64.zip).Hash`.
   - Linux: `sha256sum -c jrsctl-<version>-linux-x64.tar.gz.sha256`.
   - Releases also carry `<file>.sig`: a Base64 Ed25519 signature by the Jaspersoft publisher key (the key bundled with the tool and shown by `jrsctl keys list`), verifiable with the JDK's `Ed25519` provider or any Ed25519 tool.
2. **Unpack anywhere** the operating user may read, on the JasperReports Server host (jrsctl does not run remotely):
   - Windows: right-click → Extract All, or `tar -xf jrsctl-<version>-windows-x64.zip` (Windows 10+ ships `tar`). For example into `C:\Jaspersoft\jrsctl-<version>\`.
   - Linux: `tar -xzf jrsctl-<version>-linux-x64.tar.gz -C /opt`. The launcher and the runtime binaries come out executable; no `chmod` is needed.
   The archive unpacks into a single directory `jrsctl-<version>/`:

   | Path | Purpose |
   |---|---|
   | `bin\jrsctl.cmd` / `bin/jrsctl` | the launcher; every command in this guide is `bin\jrsctl.cmd <command>` (Windows) or `bin/jrsctl <command>` (Linux) |
   | `lib/jrsctl.jar` | the application, with this documentation embedded (`jrsctl docs`) |
   | `runtime/` | the bundled Java runtime, used only by jrsctl (it is not a JDK and has no `javac`) |
   | `README.txt`, `LICENSE-THIRD-PARTY.txt` | quick start and the licences of the bundled components |
   | `MANIFEST.sha256` | SHA-256 of every file in the directory; `sha256sum -c MANIFEST.sha256` (Linux) verifies the unpacked tree |

3. **Run it**: `bin\jrsctl.cmd --version`, then `bin\jrsctl.cmd selfcheck`, then `bin\jrsctl.cmd init --install-dir <JRS install dir>` and `bin\jrsctl.cmd doctor`. Run as a user that may stop and start the server service (see "Least privilege" in `docs/security.md`). Adding `bin\` to `PATH` is optional; the launcher locates its own runtime and jar relative to itself and works through symlinks on Linux.
4. **Choose the jrsctl home.** Everything jrsctl stores (configuration, run journal, snapshots, keys, secrets, logs) lives under the home directory described above: `--home <dir>`, else `$JRSCTL_HOME` / `%JRSCTL_HOME%`, else the platform default. Set `JRSCTL_HOME` system-wide (or in the service account's profile) when several operators share one installation, so they share one journal and one run lock. The unpack directory itself is never written to; you can place it on a read-only share.
5. **Upgrading jrsctl**: unpack the new version next to the old one and point at it; the home directory (and its `state.db`) is version-independent, and `selfcheck` reports the state schema version. Remove the old directory once the new one passes `selfcheck` and `doctor`.

`JRSCTL_JAVA_OPTS` passes extra options to the bundled JVM when needed (proxy settings such as `-Dhttps.proxyHost=...`, an extra truststore with `-Djavax.net.ssl.trustStore=...`, or a heap limit). The runtime has no `jdk.localedata`, so output uses English formatting whatever the OS locale.

## Getting help offline

- `jrsctl --help`, `jrsctl <command> --help`, `jrsctl help <command>` — the usage synopsis: flags, parameters, one line each.
- `jrsctl <command> --explain` — the long form: what the command does, what it mutates (or that it is read-only), how it rolls back, its exit codes and every flag. It prints the command's section of this guide and exits 0 **without running anything**, so it is safe to add to any command line you are about to run, even one with required flags missing: `jrsctl hotfix apply --explain`. On a group (`jrsctl hotfix --explain`) it prints every subcommand's section; `jrsctl --explain` prints the whole command reference.
- `jrsctl docs` lists the embedded documents; `jrsctl docs operator-guide` prints this guide, `jrsctl docs hotfix-authoring` the bundle authoring guide, `jrsctl docs security` the security notes.

Neither `--explain` nor `docs` reads the configuration, the secret store or the server; they print text that was fixed when the jar was built. `jrsctl docs recovery-runbook` prints the recovery runbook: what to do after every non-zero exit code.

## Air-gapped operation

jrsctl is built to run on a server that has no route to the internet.

- **Nothing is downloaded at run time.** The portable archive carries its own Java runtime, every library, the console's static files, this documentation and the compatibility matrix. There is no update check, no telemetry and no CDN reference in the console (the assets are checked for that at build time).
- **`network.mode: isolated`** (the default written by `init`) makes the HTTP client refuse any request to a host other than the one in `server.baseUrl`. A refused request is logged as `FAIL` and audited, so a misconfigured proxy or a redirect to another host cannot leak anything. `network.mode: public` lifts the allowlist and honours `network.proxy` and `network.trustStore`.
- **Hotfix bundles** are signed ZIP files: build them on a connected machine with `jrsctl hotfix build`, carry them over by any means, and `jrsctl hotfix verify <bundle>` checks the signature against the key ring on the target before `hotfix apply` touches anything. Add the publisher's public key once with `jrsctl keys add <name> <publicKeyFile>`; the bundled Actian Jaspersoft publisher key is used automatically when present.
- **Upgrade packages** are the vendor's distribution directories, copied to the server and given to `jrsctl upgrade`; jrsctl runs the package's own buildomatic scripts with `vendor.javaHome` and never fetches anything.
- **Secrets** stay in `secrets.enc` on the machine, bound to its identity; `env:` and `file:` references work as well and need no network.
- **Support bundles, logs and `runs show --json`** are complete offline; the security notes say what they contain and what they never contain.

The only things that cannot happen without a network are the ones that need the JasperReports Server itself: `doctor`'s server checks, `smoke`, and the REST export and import strategy, all of which talk only to `server.baseUrl`. Dependency advisories for jrsctl's own libraries are checked in CI, not on the operator's machine.

## Global flags

Every command accepts these flags, before or after the command name.

| Flag | Meaning |
|---|---|
| `--home <dir>` | jrsctl home directory (see above) |
| `--set key=value` | override one configuration key by dotted path, e.g. `--set server.baseUrl=https://jrs:8443/jasperserver-pro`; repeatable; flag > environment > file > default |
| `--passphrase-file <file>` | file holding the passphrase that unlocks `secrets.enc` (alternative to `JRSCTL_PASSPHRASE`) |
| `--yes` | answer yes to every confirmation; implies `--non-interactive` |
| `--non-interactive` | never prompt; fail where a human would be needed (same as `--yes`) |
| `--no-color` | disable ANSI colour; status icons become ASCII words (`OK`, `FAIL`, `RETRY`, `UNDO`, `SKIP`) |
| `--json` | emit the result as JSON instead of text (mutating commands: the plan document, then one JSON object per event, then a final `{"outcome": ...}` line) |
| `--explain` | print the long-form explanation of the command and exit 0 without running it |
| `-h`, `--help` / `-V`, `--version` | usage / version banner |

`selfcheck`, `docs` and `help` need no configuration and therefore accept only `--json` (where it applies), `--explain` and `--help`.

Colour is used only when stdout is a terminal, `NO_COLOR` is unset and `--no-color` was not given. Every output line passes the redaction filter, so configured secrets never appear in text, JSON or the log.

Environment variables: `JRSCTL_HOME` (home directory), `JRSCTL_PASSPHRASE` (passphrase for `secrets.enc`), `NO_COLOR`, `JRSCTL_JAVA_OPTS` (JVM options for the bundled runtime), plus whatever `env:NAME` references your configuration uses (for example `JRS_PASSWORD`).

## How a mutating command runs

`hotfix apply`, `hotfix rollback`, `export`, `import`, `upgrade`, `upgrade rollback`, `smoke --mutating` and `runs recover` change the server or write archives. They all follow the same path (spec §6):

1. **Pending runs block everything.** If a previous run did not reach a terminal state (crash, kill, power loss), every mutating command exits **8** and prints the exact `jrsctl runs recover <id> --resume|--rollback` command to run first.
2. **The plan is shown first.** You see the operation and target, a summary (files touched, service restart or not, strategy, backups, rollback points, warnings marked `!`), the steps grouped by phase and numbered, the plan fingerprint, and the line `nothing has changed`. The plan is stored for 30 minutes. `--plan` stops here with exit 0.
3. **Confirmation.** `Run this plan? [y/N]` unless `--yes`. Without a terminal and without `--yes` the command exits **2**.
4. **Execution under the run lock.** Steps run in order; each transition is journaled to `state.db` before it is reported. One line per finished step: `✔  03  Snapshot files   0.4s` (`OK` without colour), `✖` on failure with the cause indented, `↻` for retries, `↩` when a step was rolled back, `-` when skipped. Phase headers appear as each phase starts.
5. **Failure handling.** A recoverable failure first undoes the failing step's own partial work, then compensates every succeeded step back to the start of the failing phase (or the whole plan with `--rollback-all`) and exits **3**; a compensation that itself fails exits **4** with the backup locations and the next manual action; a fatal failure exits **4** if anything was mutated, else **2**. If the run journal (`state.db`) itself cannot be written, the run stops at once with exit **4** (**2** when nothing had been mutated yet), says so in the outcome block, and names the `runs recover` command; nothing further is attempted because nothing further could be recorded. A service command the platform refuses (for example `sc.exe stop` answering "Access is denied", or `systemctl start` needing root) fails the step immediately with the command, its exit code and what rights to run with, instead of waiting out the service timeout.
6. **Ctrl-C** cancels through the run's cancellation token: the step in flight finishes or is compensated (never abandoned), then every succeeded step is compensated; exit **5**. A retry backoff or a wait for the service to stop or start ends within a fraction of a second of the cancellation rather than running to its own timeout. The process waits up to 30 s for the compensation to complete.
7. **The outcome block** at the end names the step, cause, affected paths, backup location and next action.
8. **Retention pruning** runs automatically after every successful mutating run (best effort; it never changes the run's exit code): per-step snapshots older than `backups.retentionDays` (default 30, `0` disables) or beyond `backups.maxSnapshots` (default 20) are deleted, except those an installed hotfix, a registered customization, the most recent successful upgrade or a run pending recovery still needs. `jrsctl runs prune --dry-run` shows what the next pruning would remove.

Two rollbacks are weaker than the rest and the plan summary says so in plain text: **`import` rollback is best effort** (it re-imports the pre-import snapshot, which restores overwritten resources but cannot delete resources the failed import created), and **`upgrade --mode samedb` rollback restores files only** (the migrated database must be restored from your own database backup).

## Commands

Every section below has the same shape so that `jrsctl <command> --explain` answers the same four questions for every command: what it **mutates** (or that it is read-only), how it **rolls back**, which **exit codes** it returns, and what its **flags** do. The global flags above apply everywhere and are not repeated.

### `jrsctl selfcheck [--json]`

Verifies the tool itself, with no configuration and no server: the Java runtime it is running on, the bundled resources (schemas, compatibility matrix, publisher key, embedded documentation), the key ring and the state schema version. Run it after unpacking a new version and before opening a support ticket.

- **Mutates:** nothing; read-only and independent of the jrsctl home.
- **Rollback:** not applicable.
- **Exit codes:** 0 when every item passes; **2** when any item fails (the line names the resource or runtime property).
- **Flags:** `--json` — the report as `{"items": [...], "ok": bool}`.

### `jrsctl init [--install-dir <dir>] [--force]`

Detects the JasperReports Server installation (Tomcat layout, the Windows service or systemd unit, `server.xml` port, `buildomatic/default_master.properties`, database settings) and writes `config.yaml` into the jrsctl home. Every detected value is shown with its source first and you are asked before the file is written. Secrets are never copied: the database and server passwords are written as `env:` placeholders for you to fill in (or to replace with `enc:` references after `secrets set`).

- **Mutates:** only `config.yaml` in the jrsctl home, after confirmation (skipped with `--yes`). Nothing on the server.
- **Rollback:** an existing `config.yaml` is kept unless `--force`; with `--force` the previous file is overwritten, so copy it first if you may want it back.
- **Exit codes:** 0 when the file was written or shown; **2** when no installation is found at the given directory, when the directory is not a JasperReports Server tree, or when confirmation is needed but no terminal is present (pass `--yes`).
- **Flags:** `--install-dir <dir>` — root of the installation when auto-detection does not find it (the directory holding `apache-tomcat/` or the Tomcat tree itself); `--force` — overwrite an existing `config.yaml`; `--json` — print the detection report and the proposed configuration as JSON without writing anything.

### `jrsctl doctor [--allow-unsupported] [--json]`

Read-only health report, the check to run before every change: tool, configuration, secrets, server reachability and identity, authentication, compatibility matrix, service controller, installation layout, keystore, database, vendor Java. Items are sorted FAIL, WARN, PASS, SKIP and every non-PASS item carries a remediation (`-> ...`).

- **Mutates:** nothing. Logs in and logs out of the server with the configured credentials; reads files under the installation.
- **Rollback:** not applicable.
- **Exit codes:** 0 all good; **2** at least one item failed; **6** the only failure is the compatibility check (the server/configuration combination is outside the matrix).
- **Flags:** `--allow-unsupported` — downgrade the compatibility failure to WARN so diagnostics can continue on an unsupported server; the override is written to the audit table and never changes any other result; `--json` — the report as `{"items": [...], "counts": {...}}`.

### `jrsctl smoke [--mutating] [--json]`

Exercises the server end to end: login, repository listing, a sample report run to PDF (`smoke.reportUri`, a missing sample report is a WARN not a FAIL), a scheduler query and an export round trip. The same checks form the `verify` phase at the end of an upgrade.

- **Mutates:** nothing by default. With `--mutating` it additionally creates, runs and deletes a temporary report under `/temp` as a journaled plan with rollback, like any other mutating command (plan, confirmation, run lock).
- **Rollback:** `--mutating` deletes the temporary resource in its compensation; a failed deletion is reported with the resource uri to remove by hand.
- **Exit codes:** 0 every check passed; **2** a check failed (read-only), **3**/**4** only with `--mutating` when the temporary report could not be removed cleanly.
- **Flags:** `--mutating` — include the create/run/delete round trip; `--json` — the report as JSON.

### `jrsctl config show [--json]`

Prints the effective configuration after precedence is applied (flag `--set` > environment > `config.yaml` > built-in default) as YAML, with the source of each value in a comment. Every secret appears as its reference (`env:NAME`, `file:/path`, `enc:NAME`), never as a value, so the output is safe to paste into a ticket.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when `config.yaml` is missing, malformed or violates the schema (the message names the key).
- **Flags:** `--json` — the same document as JSON.

### `jrsctl hotfix build <dir> --key <secretRef> --out <bundle>`

Builds a signed bundle from a directory holding `manifest.json`, `payload/` and optional `sql/` and `checks/` (see `jrsctl docs hotfix-authoring`): fills in the SHA-256 of every listed file, validates the completed manifest against the schema and the semantic rules (`action`, the `restart`/WEB-INF rule, the SQL rollback rule), refuses any file in the directory that the manifest does not list, signs `manifest.json` with the Ed25519 private key behind `<secretRef>` and writes the ZIP (`manifest.json` first, `SIGNATURE` second, then the files).

- **Mutates:** only the output file. The source directory is never modified; the private key is held in memory as `char[]` and wiped after signing.
- **Rollback:** not applicable; delete the output file if you do not want it.
- **Exit codes:** 0; **1** when `--key` is not a parseable secret reference; **2** when the manifest is invalid (every problem is listed), a listed file is missing, an unlisted file is present, or the key cannot be read or is not an Ed25519 PKCS#8 key.
- **Flags:** `<dir>` — the bundle directory; `--key <secretRef>` (required) — `file:/path` to the one-line base64 PKCS#8 key written by `keys generate`, or `env:NAME` / `enc:NAME` holding the same text; `--out <bundle>` (required) — the ZIP to write (an existing file is replaced).

### `jrsctl hotfix verify <bundle> [--json]`

Checks a bundle without touching the server: the signature over `manifest.json` against the trusted key ring, the SHA-256 of every listed file (and that no unlisted file is present), and applicability (`applies.versions`, `editions`, `tenancy`) to the configured server. Prints a report with the items `signature`, `hashes`, `applicability` and `manifest`, then `bundle <id> ok` or `bundle <id> rejected`.

- **Mutates:** nothing; read-only. Reads the server's `serverInfo` for applicability.
- **Rollback:** not applicable.
- **Exit codes:** 0 when signature, hashes and applicability all pass; **7** when any of them fails; **2** when the bundle cannot be read or the server cannot be reached for applicability.
- **Flags:** `<bundle>` — the ZIP; `--json` — the report as JSON (`signatureValid`, `signedBy`, `hashesValid`, `hashProblems`, `applicable`, `applicabilityProblems`, `manifestId`, `title`, `ok`).

### `jrsctl hotfix apply <bundle> [--plan] [--yes] [--allow-unsigned] [--rollback-all] [--json]`

Verifies the bundle, builds the plan, shows it and runs it after confirmation (see "How a mutating command runs"). The phases are `verify` (signature, manifest validation against the detected server and the state store, `requires`/`conflicts`, file overlap with installed hotfixes, preflight, the manifest's prechecks), `backup` (snapshot of every file to be replaced or deleted, with permissions), `apply` (stop the service when the manifest says `restart: required`, stage the payload, atomic swap per file, SQL scripts for the configured database, start the service and wait for it, the manifest's postchecks) and `record` (state store and audit).

- **Mutates:** the files the manifest lists under the Tomcat or installation directory, the repository database when the bundle carries SQL, and the service state while the swap happens. **Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first, on both operating systems**; the manifest must declare `restart: required` for such files and there is no replace-on-restart. Bundles whose files stay outside `WEB-INF/lib` and `WEB-INF/classes` may declare `restart: none` and are applied with the service running.
- **Rollback:** complete for files: a failure compensates every step back to the start of the failing phase (the whole plan with `--rollback-all`), restoring each file from its snapshot and verifying its hash; SQL is undone by the bundle's `rollbackFile` scripts, or not at all when the manifest declares `"rollback": "irreversible"` (the plan summary shows the author's `rollbackNote` and you must confirm it). After success, `hotfix rollback <id>` undoes the hotfix at any later time.
- **Exit codes:** 0 applied; **2** planning or a precheck failed, nothing mutated (also: confirmation needed without a terminal); **3** a step failed and the plan was rolled back cleanly; **4** rollback incomplete, manual action required (the outcome block lists the snapshot paths); **5** cancelled; **6** the server is unsupported; **7** the bundle's signature is missing or untrusted, or a hash does not match (refused before planning); **8** a previous run needs recovery; **9** another jrsctl process holds the run lock.
- **Flags:** `<bundle>` — the ZIP; `--plan` — show the plan and exit 0 without running; `--yes` — skip the confirmation; `--allow-unsigned` — apply a bundle whose signature is missing or not from a trusted key anyway; the override is written to the audit table (use it only for bundles you built yourself); `--rollback-all` — on failure compensate every step of the plan, not just the failing phase; `--json` — plan document, one JSON object per event, then the outcome.

### `jrsctl hotfix rollback <id> [--cascade] [--plan] [--yes] [--json]`

Undoes an installed hotfix: restores every file it replaced or deleted from the snapshots taken at apply time (verifying hashes before and after), removes the files it added, runs the bundle's SQL rollback scripts when it has them, stops and restarts the service when the files require it, and marks the hotfix `ROLLED_BACK`. Rollback is last-in-first-out per file: if a later hotfix owns one of the same files the rollback is refused and the blocking ids are listed.

- **Mutates:** the files the hotfix touched, the database when rollback SQL exists, the service state while files under `WEB-INF/lib` or `WEB-INF/classes` are swapped (the service is stopped first, as on apply).
- **Rollback:** this command is itself a rollback; if one of its steps fails, the files already restored are put back to their hotfixed state from the snapshot taken by this run (exit 3), and a failed compensation exits 4 with the paths to restore by hand. A hotfix declared `"rollback": "irreversible"` has no SQL rollback; its files are still restored and the plan says which database changes remain.
- **Exit codes:** 0; **2** the id is unknown, not `INSTALLED`, or blocked by a later hotfix (without `--cascade`); **3**, **4**, **5**, **8**, **9** as for every mutating command.
- **Flags:** `<id>` — the hotfix id from `hotfix list`; `--cascade` — roll back the later hotfixes that block this one first, newest to oldest, in one plan; `--plan`, `--yes`, `--json` — as for every mutating command.

### `jrsctl hotfix list [--json]`

Every hotfix recorded in the state store: id, title, installed timestamp, number of files, state (`INSTALLED`, `ROLLED_BACK`, `SUPERSEDED` after an upgrade that did not re-apply it) and the run that installed it.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when the state store cannot be opened.
- **Flags:** `--json` — the rows as a JSON array.

### `jrsctl export [--uri <uri>]... [--users-roles] [--access-events] [--audit-events] [--monitoring] [--settings] [--full-server] [--strategy rest|vendor] --out <file> [--plan] [--yes] [--json]`

Exports repository content to a ZIP archive and writes a sidecar `<file>.jrsctl.json` next to it (when it was taken, from which server and version, the request flags, the SHA-256 of the archive and the server keystore fingerprint an import must match). Runs as a plan with one phase, `export`.

**Strategy rules** (spec §9.2). The plan summary's `strategy` line names the strategy and why it was chosen:

- `rest` — the server stays up; used when the server answers the async export/import probe (`EXPORT_ASYNC` / `IMPORT_ASYNC` capability) and the request is not `--full-server`.
- `vendor` — `js-export` / `js-import` from `<server.installDir>/buildomatic` with `vendor.javaHome` as its JDK; the service is stopped first and started (and waited for, up to 10 minutes) afterwards; used for `--full-server`, for an import that brings `--source-keystore` (the keystore swap needs the server down, whatever `--strategy` says), when the probe fails or the server is unreachable, or when `--strategy vendor` is given. A probe the server refuses with HTTP 401 or 403 is not a failed probe: the command stops with exit **2** naming `server.auth`, because falling back to the vendor tools would stop the service over a wrong password. `server.installDir`, `vendor.javaHome` and a working `service` block are prechecked before the service is touched.

- **Mutates:** the output archive and its sidecar only; the repository is read, never changed. With the `vendor` strategy the service is stopped for the duration of the export and started again.
- **Rollback:** on failure the partial archive and sidecar are deleted and the service, if it was stopped, is started again.
- **Exit codes:** 0; **2** planning failed (unreachable server, bad configuration, vendor tools not found); **3** a step failed and was compensated; **4**, **5**, **8**, **9** as for every mutating command.
- **Flags:** `--uri <uri>` — repository folder or resource to export, repeatable; without it the whole repository (`/`); `--users-roles` — include users and roles; `--access-events`, `--audit-events`, `--monitoring` — include the corresponding events; `--settings` — include server settings; `--full-server` — export everything (repository, users, roles, settings) with the vendor `js-export` tool, service stopped meanwhile; `--strategy rest|vendor` — force a strategy instead of letting the rules choose; `--out <file>` (required) — the archive to write, an existing file is replaced; `--plan`, `--yes`, `--json` — as for every mutating command.

### `jrsctl import <archive> [--update] [--skip-user-update] [--access-events] [--audit-events] [--monitoring] [--settings] [--skip-themes] [--source-keystore <path>] [--source-keystore-password-ref <ref>] [--strategy rest|vendor] [--plan] [--yes] [--json]`

Imports an export archive. The plan has three phases:

1. `precheck` — the archive's sidecar is read and its keystore fingerprint compared with this server's (see below); with the vendor strategy the tools are located. A failure here exits **2** and nothing has been touched.
2. `backup` — **pre-import snapshot**: the affected subtree (the uris recorded in the sidecar, else the whole repository; a full-server export when `--update` targets the root) is exported with the *same strategy as the import* to `<home>/snapshots/pre-import/pre-import-<archive>-<hash>.zip`, sidecar included. The path is listed under `backups` in the plan summary.
3. `import` — the import itself (`POST /rest_v2/import` and polling, or `js-import` with the service stopped and restarted).

**Keystore mismatch.** Repository passwords inside an archive are encrypted with the keystore (`~/.jrsks`) of the server that exported it (JasperReports Server 7.5 and later). The sidecar records that keystore's fingerprint; `import` compares it with the keystore in the home directory of `server.runAsUser` (the current user's home when `runAsUser` is unset). A mismatch is refused with exit **2** before anything is snapshotted or imported:

> `keystore fingerprint mismatch: archive was exported with keystore <a> but this server uses <b>`

Remediation: copy the source server's `.jrsks` and `.jrsksp` to this host and run the import again with `--source-keystore <path> --source-keystore-password-ref <ref>` (the plan then imports that keystore first and backs up the current one, restoring it on rollback), or export the data again from a server that shares this keystore. No sidecar, a sidecar without a fingerprint, or a server whose keystore cannot be found produce a warning and the import continues.

- **Mutates:** the repository (resources, and users, roles, events or settings when the corresponding flags are given), the server keystore when `--source-keystore` is used, and the service state with the vendor strategy.
- **Rollback:** **best effort.** If the import phase fails, jrsctl re-imports the pre-import snapshot with `update`. The plan summary states this in so many words: *Rollback re-imports the pre-import snapshot; it restores overwritten resources but cannot delete resources the failed import created.* Check the repository after a rolled-back import (exit **3**) and remove any resources the failed archive added; the snapshot stays under `snapshots/pre-import/` and can be re-imported by hand with `jrsctl import <snapshot> --update`. If the restore itself fails the run exits **4** and the outcome block names the snapshot to re-import. A keystore imported with `--source-keystore` is restored from its backup on rollback.
- **Exit codes:** 0; **2** precheck failed (keystore mismatch, unreadable archive, unreachable server, vendor tools missing), nothing touched; **3** the import failed and the snapshot was re-imported (best effort, see above); **4** the snapshot could not be re-imported; **5**, **8**, **9** as for every mutating command.
- **Flags:** `<archive>` — the export ZIP; `--update` — overwrite resources that already exist (otherwise existing resources are kept); `--skip-user-update` — with `--update`, do not overwrite users that already exist; `--access-events`, `--audit-events`, `--monitoring` — import the corresponding events; `--settings` — import server settings; `--skip-themes` — do not import themes; `--source-keystore <path>` — the source server's `.jrsks`, imported first when its fingerprint differs from this server's (vendor-documented keystore import via `js-import`, needs buildomatic); `--source-keystore-password-ref <ref>` — `env:NAME`, `file:/path` or `enc:NAME` holding that keystore's password, never given in clear; `--strategy rest|vendor` — force a strategy (same rules as `export`); `--plan`, `--yes`, `--json` — as for every mutating command.

`runs recover <id>` rebuilds export and import plans from their stored arguments (the archive must still be where it was; the snapshot path is derived from the archive's hash, so a resumed import finds the same snapshot).

### `jrsctl upgrade --to <version> --package <dir> [--mode newdb|samedb] [--db-backup-confirmed] [--reapply-hotfixes] [--plan] [--yes] [--rollback-all] [--json]`

Upgrades the server with the vendor's own scripts from an unpacked target distribution (`--package` must hold `buildomatic/` with `js-ant` for this operating system and the `jasperserver[-pro]` webapp or a `.war`). The plan has the five phases of spec §10.2; every phase boundary is a rollback point:

| Phase | Steps | Rollback point |
|---|---|---|
| `preflight` | `doctor`, `verify-target-package`, `confirm-db-backup` (samedb only) | nothing mutated |
| `backup` | `full-export-stop-service`, `full-export` (`js-export --everything`), `full-export-start-service`, `full-export-wait-for-server`, `backup-keystore`, `backup-webapp`, `backup-config` | **B**: `snapshots/<runId>/` holds the export, the webapp and buildomatic archives (`zip` on Windows, `tar.gz` on Linux), the keystore and the configuration files |
| `vendor-upgrade` | `write-master-properties`, `stop-service`, `run-vendor-upgrade`, `start-service`, `wait-for-server` | **C** = restore B |
| `reconcile` | `plan-hotfix-reapply`, `plan-customization-reapply` (plus the apply steps of each re-applied hotfix with `--reapply-hotfixes`; plus `plan-customization-reapply-stop-service`, `-start-service` and `-wait-for-server` around the reapply when a registered customization lives under `WEB-INF/lib` or `WEB-INF/classes`) | restore B |
| `verify` | `smoke`, `record-upgrade` | a smoke failure offers `jrsctl upgrade rollback <runId> --to-point B` |

- `doctor` must pass. Two of its findings are judged against the *target* instead: `compat` and `vendor-java` (an 8.x server needs Java 11 for its own buildomatic, a 9.x target needs Java 17, so the vendor JDK matches only one of them). `verify-target-package` checks the package, that the compat matrix lists the path from the running version to `--to`, and that `vendor.javaHome` is exactly the Java major the target needs; an unlisted path exits **6** before anything is planned.
- `write-master-properties` copies the installed `buildomatic/default_master.properties` into the target package's buildomatic directory **without any password key** (spec §7.4) and adds `appServerType=tomcat` and `appServerDir`; a file already there is kept and restored on rollback. Add `dbPassword` (and any other password the vendor scripts need) to that file yourself before running.
- `run-vendor-upgrade` runs `js-upgrade-newdb`/`js-upgrade-samedb` from the target buildomatic when the package ships that script, else `js-ant upgrade-newdb`/`upgrade-samedb`, with `JAVA_HOME=vendor.javaHome`, output streamed and redacted, two-hour timeout. Its compensation is the point-B restore.
- `plan-hotfix-reapply` lists every installed hotfix as `REAPPLICABLE` (its `applies` matches the new server and every `replaces` target exists in the new webapp) or `SUPERSEDED`. Nothing is re-applied unless `--reapply-hotfixes` was given, in which case the normal apply plan of each re-applicable hotfix (re-packed from `runs/<installRunId>/bundle/`; "bundle no longer available, re-apply manually" otherwise) runs inside the reconcile phase under the run id `<runId>-hf-<hotfix>`. `record-upgrade` marks every hotfix not re-applied `SUPERSEDED`.
- `plan-customization-reapply` compares, per registered customization, the original hash, the registered copy and the upgraded file: when the upgraded file still equals the original the registered copy is put back (the upgraded file is snapshotted first); otherwise a `CONFLICT` with a unified diff is logged and the file is left alone. Nothing is ever blind-copied. When any registered customization is under `WEB-INF/lib` or `WEB-INF/classes` the service is stopped before this step and started and probed again after it, the same rule a hotfix follows; the plan summary warns that this second stop will happen.
- `record-upgrade` writes `snapshots/<runId>/upgrade.json`, registers the snapshot set with `referenced_by = upgrade` so retention never prunes the most recent successful upgrade, and audits `upgrade.completed`.

- **Mutates:** the webapp, `buildomatic/`, the configuration files and the keystore under the installation; the service state; and the repository database: **`--mode newdb` (the default)** makes the vendor script create a *new* repository database and leaves the old one untouched; **`--mode samedb`** migrates the existing database in place.
- **Rollback:** a failure compensates back to the start of the failing phase; `--rollback-all` goes back to point B in the same run; `upgrade rollback <runId> --to-point B` does the same later. With `newdb` the rollback to point B is a complete restore: webapp, keystore, configuration and the connection back to the old database. **With `samedb` the rollback restores files only and jrsctl cannot undo the database migration.** The command refuses to plan (exit **2**) unless `--db-backup-confirmed` states that you hold your own database backup; the confirmation is audited and the plan summary says in plain text: *Rollback restores files only. Restore the database from your own backup before running rollback.* Taking that database backup, and restoring it, is the operator's responsibility.
- **Exit codes:** 0 upgraded and smoke-tested; **2** preflight failed (doctor, package, `samedb` without `--db-backup-confirmed`, confirmation without a terminal), nothing mutated; **3** a step failed and the run was compensated to the phase start (or to point B with `--rollback-all`); **4** a compensation failed, the outcome block lists `snapshots/<runId>/` and the next action; **5** cancelled; **6** the path from the running version to `--to` is not in the compatibility matrix, or `vendor.javaHome` is the wrong major; **8**, **9** as for every mutating command.
- **Flags:** `--to <version>` (required) — the target version, must be a path the matrix lists; `--package <dir>` (required) — the unpacked target distribution; `--mode newdb|samedb` — `newdb` (default) creates a new repository database, `samedb` migrates in place; `--db-backup-confirmed` — `samedb` only: confirm that the repository database has been backed up (audited); `--reapply-hotfixes` — re-apply every installed hotfix classified `REAPPLICABLE` after the vendor upgrade; `--plan` — show the plan and exit; `--yes` — skip confirmations; `--rollback-all` — on failure compensate back to point B instead of the failing phase; `--json` — plan, events and outcome as JSON.

### `jrsctl upgrade rollback <runId> --to-point B|C [--plan] [--yes] [--json]`

Restores the point-B backups of an earlier upgrade run: `stop-service`, `restore-webapp`, `restore-buildomatic`, `restore-config`, `restore-keystore`, `start-service`, `wait-for-server`, `record-rollback`. Point C restores the same artefacts as point B (spec §10.2: "rollback point C = restore B"); the letter only records where the upgrade got to. Archives are hash-verified before extraction.

- **Mutates:** the webapp and `buildomatic/` trees (replaced from the archives), the configuration files and the keystore (overwritten from the backups), the service state. The database is never touched.
- **Rollback:** the replaced webapp and buildomatic trees are moved to `runs/<rollbackRunId>/aside/` and put back if the rollback itself has to be compensated; the configuration and keystore files being overwritten are snapshotted under the rollback run first. **Files only**: after a `samedb` upgrade, restore the database from your own backup before running this; after a `newdb` upgrade the restored configuration points back at the old database, which the upgrade left untouched.
- **Exit codes:** 0; **2** the run id is not a completed upgrade run or its snapshot set is missing; **3**, **4**, **5**, **8**, **9** as for every mutating command.
- **Flags:** `<runId>` — the upgrade run from `runs list`; `--to-point B|C` (required) — the rollback point, both restore the point-B artefacts; `--plan`, `--yes`, `--json` — as for every mutating command.

### `jrsctl customizations register <path> [--original <file>] [--json]`

Registers an operator-customised file under `server.installDir` or `server.tomcatDir` so an upgrade can reconcile it (spec §10.3): the file is snapshotted under `snapshots/cust-<hash>/file` (retention-protected) and its current hash recorded as the "original". Pass `--original <file>` pointing at the vendor's unmodified copy so the upgrade can tell "the vendor did not change this file" (re-apply automatically) from "the vendor changed it" (report a conflict).

- **Mutates:** the state store row and the snapshot under the jrsctl home. Nothing under the installation.
- **Rollback:** `customizations unregister <path>` removes the row and the snapshot.
- **Exit codes:** 0; **2** when the file is outside the installation, does not exist, or is already registered.
- **Flags:** `<path>` — the customised file; `--original <file>` — the vendor's unmodified copy whose hash becomes the original; `--json` — the registration as JSON.

### `jrsctl customizations unregister <path>`

Forgets a registered customization: removes its row from the state store and deletes its snapshot. The file under the installation is not touched.

- **Mutates:** the state store row and the snapshot under the jrsctl home only.
- **Rollback:** register the file again with `customizations register`.
- **Exit codes:** 0; **2** when the path is not registered.
- **Flags:** `<path>` — the registered file.

### `jrsctl customizations list [--json]`

Every registered customization: path, original hash, registered hash, when it was registered and whether the file on disk still matches the registered copy.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0.
- **Flags:** `--json` — the rows as a JSON array.

### `jrsctl customizations diff <path> [--json]`

Prints a unified diff between the registered copy of a customised file and the file on disk, mirroring `diff(1)`.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0 when the file still matches its registered copy; **1** when it differs; **2** when the path is not registered.
- **Flags:** `<path>` — the registered file; `--json` — `{path, originalSha256, registeredSha256, currentSha256, same, diff}`.

### `jrsctl runs list [--json] [--limit <n>]`

Runs, most recent first: run id, operation, started, duration, outcome (terminal state and exit code, or `PENDING` for a run that needs recovery).

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0.
- **Flags:** `--limit <n>` — maximum rows (default 50); `--json` — the rows as a JSON array.

### `jrsctl runs show <id> [--json]`

One run: the plan summary it was started from (operation, target, files, service, strategy, fingerprint, warnings), every step transition from the journal (`PENDING -> RUNNING -> SUCCEEDED ...` with details such as retry reasons), the snapshots it took and, for a failed run, the outcome block.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when the id is unknown.
- **Flags:** `<id>` — the run id from `runs list`; `--json` — the run document (the same one the console and the support bundle use).

### `jrsctl runs recover <id> --resume | --rollback [--yes] [--json]`

For a run that never reached a terminal state (crash, `kill -9`, power loss). The plan is rebuilt from the stored arguments (the bundle or archive must still be where it was), then either continued or undone. Both modes take the run lock, journal every transition and print progress like any other run; the recovery request is audited. Until the run is recovered every other mutating command exits **8**.

- **Mutates:** whatever the interrupted plan mutates. `--resume` re-runs the precheck of the interrupted step and re-executes it (steps are idempotent, so a step that half-finished completes without duplicating its effect), then continues to the end. `--rollback` compensates every succeeded step in reverse.
- **Rollback:** with `--resume`, a failure later in the plan is handled as in the original run; if the interrupted step's precheck fails only `--rollback` is offered (exit 2, nothing touched), and a run that already began rolling back cannot be resumed. `--rollback` ends the run as `ROLLED_BACK`; a compensation that fails leaves the run `ROLLBACK_INCOMPLETE` with the backup paths printed.
- **Exit codes:** `--resume`: 0 when the plan completed, **2** when the precheck refused, **3**/**4** as for the original run; `--rollback`: **3** (rolled back cleanly, the code every rolled-back run carries), **4** when a compensation failed; **5** cancelled; **9** another process holds the lock.
- **Flags:** `<id>` — the pending run id (printed by the exit-8 message); `--resume` — continue from the interrupted step; `--rollback` — undo the run; exactly one of the two is required; `--yes`, `--json` — as for every mutating command.

### `jrsctl runs prune [--dry-run] [--json]`

Applies the snapshot retention policy by hand: deletes per-step snapshots that are older than `backups.retentionDays` (default 30; `0` disables age pruning) or beyond `backups.maxSnapshots` (default 20, newest kept), **never** deleting a protected snapshot. Protected are the snapshots of every `INSTALLED` hotfix (its rollback needs them), of every registered customization, of the **most recent** `SUCCEEDED` upgrade only (older upgrades' sets become prunable), and of every run pending recovery (`runs recover --rollback` needs them). `maxSnapshots` counts protected snapshots too, so the total on disk can stay above the cap when enough are protected. The text output is a `SNAPSHOT / RUN / STEP / PATH` table and a summary line, or `nothing to prune`.

The same pruning runs automatically after every successful mutating run (best effort: the finished run's own snapshots are protected, a failure is logged as a warning and never changes the run's exit code), so this command is for reclaiming space between runs or for previewing what the next automatic pruning would remove. Known gap: the upgrade set directories (`snapshots/<runId>/upgrade/` with the webapp archive and the full export) are not per-step snapshots and are not pruned; only the per-step snapshots of that run are.

- **Mutates:** snapshot files and directories under `<home>/snapshots/` and their rows in the state store. Nothing on the server. Not a journaled run (no entry in `runs list`), but it takes the run lock while it deletes and writes one audit row with action `runs.prune`.
- **Rollback:** none; a pruned snapshot is gone. Protected snapshots are never candidates, and `--dry-run` shows the exact list before anything is removed.
- **Exit codes:** 0 (also when nothing qualifies); **2** when the configuration or state store cannot be read; **9** when another jrsctl process holds the run lock (the message names its run id and pid).
- **Flags:** `--dry-run` — list what would be removed and remove nothing (read-only, no lock); `--json` — exactly `{"dryRun": bool, "removed": [{"id", "runId", "stepId", "path"}], "kept": n, "protected": n}` where `kept` is the number of snapshots remaining on disk after the pass and `protected` how many of those belong to a protected run.

### `jrsctl keys list [--json]`

The trusted key ring for bundle signatures (`keys/trusted/`): name, fingerprint (first 16 hex of the key's SHA-256) and origin. The Jaspersoft publisher key is bundled with the tool, listed as such, and cannot be replaced or removed.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0.
- **Flags:** `--json` — the rows as a JSON array.

### `jrsctl keys add <name> <publicKeyFile>`

Trusts a public key for bundle signatures: the file holds one base64 line (X.509 SubjectPublicKeyInfo of an Ed25519 key, as published by the bundle's author) and is copied to `keys/trusted/<name>.pub`. Audited.

- **Mutates:** `keys/trusted/<name>.pub` in the jrsctl home.
- **Rollback:** `keys remove <name>`.
- **Exit codes:** 0; **2** when the file is not an Ed25519 public key, the name is already in use, or the name is the bundled publisher key.
- **Flags:** `<name>` — the key name, e.g. `customer`; `<publicKeyFile>` — the file with the key.

### `jrsctl keys remove <name>`

Stops trusting a customer key: deletes `keys/trusted/<name>.pub`. Bundles signed with it are refused from then on (exit 7 on `hotfix apply` unless `--allow-unsigned`). Hotfixes already installed are unaffected. Audited.

- **Mutates:** `keys/trusted/<name>.pub` in the jrsctl home.
- **Rollback:** `keys add <name> <publicKeyFile>` with the saved public key.
- **Exit codes:** 0; **2** when the name is unknown or is the bundled publisher key.
- **Flags:** `<name>` — the key name.

### `jrsctl keys generate <name> --private-out <file>`

Generates an Ed25519 key pair for signing your own bundles: the public key is trusted under `<name>` as if added with `keys add`, and the private key is written **once** to `<file>` as one base64 PKCS#8 line with owner-only permissions (`0600` on Linux; owner + SYSTEM/Administrators only on Windows). The reference to use is printed: `jrsctl hotfix build <dir> --key file:<file>`. Keep the private key out of the jrsctl home and out of version control; `secrets set <name> --from-file <file>` stores it as `enc:<name>` if you prefer. Audited.

- **Mutates:** `keys/trusted/<name>.pub` in the jrsctl home and the private key file.
- **Rollback:** `keys remove <name>` and delete the private key file. A lost private key cannot be recovered; generate a new pair.
- **Exit codes:** 0; **2** when the name is already in use or `<file>` already exists (an existing file is never overwritten).
- **Flags:** `<name>` — the key name; `--private-out <file>` (required) — where to write the private key.

### `jrsctl secrets init`

Creates an empty encrypted store `secrets.enc` in the jrsctl home (AES-256-GCM, key derived with PBKDF2-HMAC-SHA256 from a machine-bound salt and your passphrase). The passphrase comes from `--passphrase-file`, then `JRSCTL_PASSPHRASE`, then a console prompt when interactive. The store is bound to the machine identity (`/etc/machine-id` on Linux, the computer name on Windows): a copied `secrets.enc` cannot be unlocked on another machine even with the passphrase. A store created by a build before 2026-09-10 was bound to the DNS host name instead; the first command that unlocks it rebinds it to the machine identity in place and logs a warning, after which the old host name no longer unlocks it.

- **Mutates:** `secrets.enc` in the jrsctl home; refuses to overwrite an existing store.
- **Rollback:** delete `secrets.enc`.
- **Exit codes:** 0; **2** when the store exists or no passphrase is available non-interactively (the message names `JRSCTL_PASSPHRASE` and `--passphrase-file`).
- **Flags:** none beyond the global flags (`--passphrase-file <file>` supplies the passphrase).

### `jrsctl secrets set <name> [--from-env VAR | --from-file <path>]`

Stores a value under `<name>` so that configuration can reference it as `enc:<name>`. The value is read from an environment variable, a file (trailing newlines ignored) or, when neither is given, from the console (hidden) or standard input. The value is never echoed, logged or shown by `secrets list`.

- **Mutates:** `secrets.enc` (the entry is added or replaced).
- **Rollback:** `secrets remove <name>`, or `secrets set` again with the previous value.
- **Exit codes:** 0; **2** when the store does not exist, the passphrase is missing or wrong, the variable is unset or the file unreadable.
- **Flags:** `<name>` — the entry name; `--from-env VAR` — read the value from the environment variable `VAR`; `--from-file <path>` — read the value from a file; at most one of the two.

### `jrsctl secrets remove <name>`

Deletes an entry from `secrets.enc`. No passphrase is needed because the entry is removed without decrypting the others. Configuration still referencing `enc:<name>` fails at the next command with exit 2.

- **Mutates:** `secrets.enc`.
- **Rollback:** `secrets set <name>` with the value again.
- **Exit codes:** 0; **2** when the store or the entry does not exist.
- **Flags:** `<name>` — the entry name.

### `jrsctl secrets list [--json]`

Lists the entry names in `secrets.enc`; values are never shown. No passphrase is needed.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when the store does not exist.
- **Flags:** `--json` — the names as a JSON array.

### `jrsctl console [--bind <addr>] [--port <n>] [--open|--no-open]`

Serves the local web console (dashboard, new operation, runs with live progress, doctor, hotfixes) until Ctrl-C, or until you type `stop` and Enter. On start it prints one line:

```
Console: http://127.0.0.1:7420/#token=<token>
```

Open that URL: the token in the fragment is the per-launch key to the API and is never shown again (it is redacted from every log, response and support bundle). When a terminal is present the default browser is opened for you; pass `--no-open` to skip that, for example from a service or a script. Runs started from the console go through the same plan, confirmation, fingerprint, run lock and journal as the CLI; they are non-interactive, so a step that would need a terminal prompt fails instead of waiting. `GET /api/runs/<id>/support-bundle` (the "Support bundle" button on a run) downloads a redacted zip of the plan, journal, events, server identity, doctor report, effective configuration and the last 2000 log lines to attach to a ticket. A run that needs recovery blocks new runs in the console exactly as it does on the CLI; its run page offers Resume and Roll back.

- **Mutates:** by itself only `<home>/console.token` (owner-only, deleted on stop). Operations started from the console mutate exactly what the corresponding CLI command mutates, under the same rules, with audit actor `console`.
- **Rollback:** per operation, as on the CLI; the run page offers cancel and rollback through the same code as `runs recover`.
- **Exit codes:** 0 after a clean stop; **2** when the bind address or TLS material is refused or the port is busy.
- **Flags:** `--bind <addr>` — override `console.bind` (default `127.0.0.1`); a non-loopback bind is refused with exit 2 unless `console.tls.enabled: true` (`certPath` PEM chain, `keyPath` unencrypted PKCS#8 PEM) and `console.auth.mode: local` (`passwordRef` for the operator password) are both configured, see `docs/security.md`; `--port <n>` — override `console.port` (default `7420`); `--port 0` picks a free port; `--open` / `--no-open` — open (default when a terminal is present) or do not open the default browser.

### `jrsctl docs [<name>] [--json]`

Offline documentation. Without an argument it lists the documents embedded in the jar at build time (name, title, size); with a name it prints that document, as Markdown, to standard output so it can be read in the terminal or redirected to a file. The embedded documents are `operator-guide` (this guide), `hotfix-authoring` (bundle format, `hotfix build`, signing, testing a bundle), `security` (threat model, key management, console token, hardening) and `readme`.

- **Mutates:** nothing; read-only and independent of the jrsctl home, configuration and server.
- **Rollback:** not applicable.
- **Exit codes:** 0; **1** when the name is not one of the embedded documents (the message lists the valid names).
- **Flags:** `<name>` — the document to print; `--json` — the listing as a JSON array of `{"name", "title", "bytes"}` (ignored when a name is given).

### `jrsctl help [<command>]`

Prints the usage synopsis of the tool (no argument) or of one top-level command, the same text as `jrsctl <command> --help`. For nested commands use `jrsctl <command> <subcommand> --help`, and for the long-form explanation `jrsctl <command> [<subcommand>] --explain`.

- **Mutates:** nothing; read-only and independent of the jrsctl home.
- **Rollback:** not applicable.
- **Exit codes:** 0; **1** when the command name is unknown.
- **Flags:** `<command>` — a top-level command name such as `runs` or `hotfix`.

## Exit codes

| Code | Meaning | Typical cause |
|---|---|---|
| 0 | success | override flags such as `--allow-unsupported` or `--allow-unsigned` do not change the code; they are audited |
| 1 | usage error | unknown command or flag, missing argument, unparseable secret reference, unknown `docs` name; also `customizations diff` when the file differs |
| 2 | precheck / doctor / fingerprint failure, nothing mutated | configuration or secret problem, unreachable server, failed doctor item, plan inputs changed since planning, confirmation needed but not interactive, planning failed |
| 3 | run failed, rolled back cleanly | a step failed and every succeeded step of the phase (or plan) was compensated; also the code of `runs recover --rollback` |
| 4 | run failed, rollback incomplete — manual action required | a compensation failed, or a fatal step failure after mutation; the outcome block lists backups and the next action |
| 5 | cancelled | Ctrl-C or console cancel; the in-flight step was completed or compensated |
| 6 | unsupported server / configuration | compatibility matrix rejects the combination, or the upgrade path |
| 7 | signature / verification failure | untrusted or missing bundle signature, hash mismatch, or a `verify` that is not fully ok |
| 8 | pending recovery required | a previous run has no terminal state; run `jrsctl runs recover <id> --resume|--rollback` |
| 9 | run lock held | another jrsctl process owns `runs.lock`; the message names its run id and pid |

## Error classes and what to do

| You see | It means | Do this |
|---|---|---|
| `error: ... config.yaml ...` / schema violation (exit 2) | the configuration is missing, malformed or fails the schema | run `jrsctl init`, or fix the key named in the message; `jrsctl config show` prints the effective view |
| `environment variable X is not set (referenced by env:X)` (exit 2) | a secret reference points at an unset variable | export the variable, or switch the reference to `enc:NAME` after `jrsctl secrets set NAME` |
| `secret file ... is readable by other users` (exit 2) | a `file:` secret is not owner-only | `chmod 600` on Linux; on Windows remove ACL entries other than the owner, SYSTEM and Administrators |
| `passphrase does not unlock secrets.enc ...` / `no passphrase available` (exit 2) | wrong passphrase, store copied from another host, or nothing supplied non-interactively | set `JRSCTL_PASSPHRASE` or pass `--passphrase-file`; recreate the store if the host was renamed |
| `server` FAIL in doctor, `connection refused` (exit 2) | the server is down or `server.baseUrl` is wrong | start the server or fix `server.baseUrl`; check proxies and `network.mode` |
| `compat` FAIL (exit 6) | the server version/edition is outside the compatibility matrix | use a supported version, or `--allow-unsupported` for diagnostics only |
| `bundle signature is missing or not made by a trusted key` (exit 7) | the bundle is unsigned or signed by a key you have not added | `jrsctl keys add <name> <publicKeyFile>` for the signer's key; `--allow-unsigned` only for bundles you built yourself |
| `bundle rejected, file hashes do not match the manifest` (exit 7) | the ZIP was altered or corrupted after signing | obtain the bundle again from its publisher |
| `files not listed in the manifest: ...` from `hotfix build` (exit 2) | the bundle directory holds a file the manifest does not declare | list it under `files`, `sql` or `checks`, or delete it; see `jrsctl docs hotfix-authoring` |
| `restart 'none' is not allowed for files under WEB-INF/lib or WEB-INF/classes` (exit 2) | the manifest claims no restart for a file that needs the service stopped | set `"restart": "required"` |
| `keystore fingerprint mismatch: archive was exported with keystore ...` (exit 2) | the archive comes from a server with a different `.jrsks`; its encrypted passwords cannot be decrypted here | copy the source server's `.jrsks` and `.jrsksp` here and import again with `--source-keystore <path> --source-keystore-password-ref <ref>`, or export again from a server that shares this keystore |
| `server reported import ... failed` followed by `rolled back to phase import` (exit 3) | the server rejected the archive; the pre-import snapshot was re-imported | check the jasperserver log; the snapshot restores overwritten resources only, so remove anything the failed import created |
| `restore of the pre-import snapshot failed` (exit 4) | the import failed and the snapshot could not be re-imported | run `jrsctl import <snapshot> --update` with the path from the outcome block, then verify the repository |
| `samedb upgrade needs --db-backup-confirmed` (exit 2) | the in-place migration cannot be undone by jrsctl | back up the repository database with your database tools, then pass `--db-backup-confirmed` |
| `applicability` FAIL in `hotfix verify` (exit 7) | the bundle targets another version, edition or tenancy | check `jrsctl doctor` and the manifest's `applies` section |
| `N runs need recovery before anything else can run` (exit 8) | a previous run was interrupted | `jrsctl runs show <id>` to see where it stopped, then `jrsctl runs recover <id> --resume` or `--rollback` |
| `run lock is held by run <id> (pid <n>)` (exit 9) | another jrsctl process is mutating this home | wait for it to finish (`jrsctl runs list`); if the pid is dead the OS has released the lock and a retry succeeds |
| `stopped at a precheck; nothing changed` (exit 2) | a step's precheck failed before mutation (disk space, write access, locked file, service state, restart consistency) | follow the `next` line of the outcome block; typical: stop Tomcat, free space, close the program holding the file |
| `refused; inputs changed since planning` (exit 2) | files, server identity or configuration changed between plan and run | run the command again to plan against the current state |
| `failed; rolled back to phase <p>` (exit 3) | a step failed and was compensated | read the `cause` and `next` lines, fix the condition, run again |
| `failed; rollback incomplete, manual action required` (exit 4) | a compensation failed | restore the listed `backup` paths by hand (snapshots keep the original permissions), then `jrsctl doctor`; the run stays in the journal for support |
| `cancelled and rolled back` (exit 5) | Ctrl-C | nothing to do; run again when ready |
| `rollback of <id> refused: blocked by ...` (exit 2) | a later hotfix owns one of the files | roll the later hotfix back first, or pass `--cascade` |
| `no explanation is embedded for 'jrsctl ...'` (exit 1) | the jar was built without this guide, or the command has no section here | `jrsctl docs` lists what is embedded; `jrsctl <command> --help` always works |

For a support request attach the support bundle from the console (`GET /api/runs/<id>/support-bundle`, or the button on the run page) or, without the console, `logs/jrsctl.log` and the output of `jrsctl runs show <id> --json`. Both are redacted; review host names and paths before sharing.
