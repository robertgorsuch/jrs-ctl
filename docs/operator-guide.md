# jrsctl operator guide

`jrsctl` is the JasperReports Server lifecycle tool from Actian Jaspersoft. This guide covers every command shipped so far (Phases 0–5): tool self-checks, detection and diagnostics, configuration, signed hotfixes, vendor upgrades with rollback and registered customizations, run history and recovery, trusted keys and the encrypted secret store. Export/import commands and the web console arrive in their own phases.

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
| `logs/jrsctl.log` | JSON log of every invocation |

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
| `-h`, `--help` / `-V`, `--version` | usage / version banner |

Colour is used only when stdout is a terminal, `NO_COLOR` is unset and `--no-color` was not given. Every output line passes the redaction filter, so configured secrets never appear in text, JSON or the log.

Environment variables: `JRSCTL_HOME` (home directory), `JRSCTL_PASSPHRASE` (passphrase for `secrets.enc`), `NO_COLOR`, plus whatever `env:NAME` references your configuration uses (for example `JRS_PASSWORD`).

## How a mutating command runs

`hotfix apply`, `hotfix rollback`, `upgrade`, `upgrade rollback` and `runs recover` change the server. They all follow the same path (spec §6):

1. **Pending runs block everything.** If a previous run did not reach a terminal state (crash, kill, power loss), every mutating command exits **8** and prints the exact `jrsctl runs recover <id> --resume|--rollback` command to run first.
2. **The plan is shown first.** You see the operation and target, a summary (files touched, service restart or not, strategy, backups, rollback points, warnings marked `!`), the steps grouped by phase and numbered, the plan fingerprint, and the line `nothing has changed`. The plan is stored for 30 minutes. `--plan` stops here with exit 0.
3. **Confirmation.** `Run this plan? [y/N]` unless `--yes`. Without a terminal and without `--yes` the command exits **2**.
4. **Execution under the run lock.** Steps run in order; each transition is journaled to `state.db` before it is reported. One line per finished step: `✔  03  Snapshot files   0.4s` (`OK` without colour), `✖` on failure with the cause indented, `↻` for retries, `↩` when a step was rolled back, `-` when skipped. Phase headers appear as each phase starts.
5. **Failure handling.** A recoverable failure compensates every succeeded step back to the start of the failing phase (or the whole plan with `--rollback-all`) and exits **3**; a compensation that itself fails exits **4** with the backup locations and the next manual action; a fatal failure exits **4** if anything was mutated, else **2**.
6. **Ctrl-C** cancels through the run's cancellation token: the step in flight finishes or is compensated (never abandoned), then every succeeded step is compensated; exit **5**. The process waits up to 30 s for that to complete.
7. **The outcome block** at the end names the step, cause, affected paths, backup location and next action.

## Commands

### `jrsctl selfcheck [--json]`

Verifies the tool itself: Java runtime, bundled resources, key ring, state schema. Read-only. Exit 0 when every item passes, else 2.

### `jrsctl init [--install-dir <dir>] [--force]`

Detects the JasperReports Server installation (Tomcat layout, `server.xml` port, `buildomatic/default_master.properties`) and writes `config.yaml`. Shows every detected value with its source first and asks before writing (skip with `--yes`). An existing file is kept unless `--force`. Secrets are never copied: the database and server passwords are written as `env:` placeholders. `--json` prints the report and the proposed configuration without writing.

### `jrsctl doctor [--allow-unsupported] [--json]`

Read-only health report: tool, configuration, secrets, server reachability and identity, authentication, compatibility matrix, service controller, installation layout, keystore, database. Items are sorted FAIL, WARN, PASS, SKIP and every non-PASS item carries a remediation (`-> ...`). Exit 0 all good, **2** something failed, **6** only the compatibility check failed (server/config combination unsupported). `--allow-unsupported` downgrades that check to WARN and is audited; it never changes any other result.

### `jrsctl smoke [--mutating] [--json]`

Exercises the server: login, repository listing, a sample report to PDF, scheduler query, an export round trip. Non-mutating by default; `--mutating` additionally creates, runs and deletes a temporary report under `/temp` as a journaled plan with rollback. A missing sample report is a WARN, not a FAIL. Exit 0 or 2.

### `jrsctl config show [--json]`

Prints the effective configuration (flag > env > file > default) as YAML (or JSON). Every secret appears as its reference (`env:NAME`, `file:/path`, `enc:NAME`), never as a value.

### `jrsctl hotfix build <dir> --key <secretRef> --out <bundle>`

Builds a signed bundle from a directory holding `manifest.json`, `payload/`, optional `sql/` and `checks/`: validates the manifest against the schema, computes the SHA-256 of every file, signs the manifest with the Ed25519 private key behind `<secretRef>` (`file:/path` to a one-line base64 PKCS#8 key as written by `keys generate`, or `env:NAME` / `enc:NAME`) and writes the ZIP. Read-only apart from the output file. Exit 0, **1** for an unparseable reference, **2** when the manifest or key is unusable.

### `jrsctl hotfix verify <bundle> [--json]`

Checks the signature against the trusted key ring, every listed hash, and applicability (`applies.versions`, `editions`, `tenancy`) to the configured server. Prints a report (`signature`, `hashes`, `applicability`, `manifest`) and `bundle <id> ok` or `bundle <id> rejected`. Exit 0 when all three pass, **7** otherwise. Nothing is changed.

### `jrsctl hotfix apply <bundle> [--plan] [--yes] [--allow-unsigned] [--rollback-all]`

Verifies the bundle, plans, shows the plan and runs it after confirmation (see above). Phases: `verify` (signature, manifest validation against the detected server and the state store, preflight, prechecks), `backup` (snapshot of every file to be replaced or deleted), `apply` (stop the service when `restart: required`, stage, atomic swap, SQL, start and wait, postchecks), `record` (state store and audit).

- A bundle whose hashes do not match, or whose signature is missing or not from a trusted key, is refused with exit **7** before planning. `--allow-unsigned` applies an unsigned bundle anyway; the override is written to the audit table.
- `--rollback-all` compensates every step of the plan on failure instead of only the failing phase.
- Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first; the manifest's `restart` must say so.

### `jrsctl hotfix rollback <id> [--cascade] [--plan] [--yes]`

Restores the files an installed hotfix replaced (verifying hashes before and after), runs SQL rollback scripts if present, restarts the service when needed and marks the hotfix `ROLLED_BACK`. Rollback is last-in-first-out per file: if a later hotfix owns one of the same files the rollback is refused with the blocking ids; `--cascade` rolls those back first, newest to oldest, in one plan.

### `jrsctl hotfix list [--json]`

Every hotfix recorded in the state store: id, title, installed timestamp, number of files, state (`INSTALLED`, `ROLLED_BACK`, `SUPERSEDED`).

### `jrsctl upgrade --to <version> --package <dir> [--mode newdb|samedb] [--db-backup-confirmed] [--reapply-hotfixes] [--plan] [--yes] [--rollback-all] [--json]`

Upgrades the server with the vendor's own scripts from an unpacked target distribution (`--package` must hold `buildomatic/` with `js-ant` for this operating system and the `jasperserver[-pro]` webapp or a `.war`). The plan has the five phases of spec §10.2; every phase boundary is a rollback point:

| Phase | Steps | Rollback point |
|---|---|---|
| `preflight` | `doctor`, `verify-target-package`, `confirm-db-backup` (samedb only) | nothing mutated |
| `backup` | `full-export-stop-service`, `full-export` (`js-export --everything`), `full-export-start-service`, `full-export-wait-for-server`, `backup-keystore`, `backup-webapp`, `backup-config` | **B**: `snapshots/<runId>/` holds the export, the webapp and buildomatic archives (`zip` on Windows, `tar.gz` on Linux), the keystore and the configuration files |
| `vendor-upgrade` | `write-master-properties`, `stop-service`, `run-vendor-upgrade`, `start-service`, `wait-for-server` | **C** = restore B |
| `reconcile` | `plan-hotfix-reapply`, `plan-customization-reapply` (plus the apply steps of each re-applied hotfix with `--reapply-hotfixes`) | restore B |
| `verify` | `smoke`, `record-upgrade` | a smoke failure offers `jrsctl upgrade rollback <runId> --to-point B` |

- `doctor` must pass. Two of its findings are judged against the *target* instead: `compat` and `vendor-java` (an 8.x server needs Java 11 for its own buildomatic, a 9.x target needs Java 17, so the vendor JDK matches only one of them). `verify-target-package` checks the package, that the compat matrix lists the path from the running version to `--to`, and that `vendor.javaHome` is exactly the Java major the target needs; an unlisted path exits **6** before anything is planned.
- **`--mode newdb` is the default.** The vendor script creates a new repository database and the old one is never touched, so rollback to point B is a complete file and connection restore: webapp, keystore, configuration and the connection back to the old database.
- **`--mode samedb` migrates the existing database in place and jrsctl cannot undo that.** The command refuses to plan (exit **2**) unless `--db-backup-confirmed` says you have your own database backup; the confirmation is audited and the plan states in plain text: *Rollback restores files only. Restore the database from your own backup before running rollback.*
- `write-master-properties` copies the installed `buildomatic/default_master.properties` into the target package's buildomatic directory **without any password key** (spec §7.4) and adds `appServerType=tomcat` and `appServerDir`; a file already there is kept and restored on rollback. Add `dbPassword` (and any other password the vendor scripts need) to that file yourself before running.
- `run-vendor-upgrade` runs `js-upgrade-newdb`/`js-upgrade-samedb` from the target buildomatic when the package ships that script, else `js-ant upgrade-newdb`/`upgrade-samedb`, with `JAVA_HOME=vendor.javaHome`, output streamed and redacted, two-hour timeout. Its compensation is the point-B restore.
- `plan-hotfix-reapply` lists every installed hotfix as `REAPPLICABLE` (its `applies` matches the new server and every `replaces` target exists in the new webapp) or `SUPERSEDED`. Nothing is re-applied unless `--reapply-hotfixes` was given, in which case the normal apply plan of each re-applicable hotfix (re-packed from `runs/<installRunId>/bundle/`; "bundle no longer available, re-apply manually" otherwise) runs inside the reconcile phase under the run id `<runId>-hf-<hotfix>`. `record-upgrade` marks every hotfix not re-applied `SUPERSEDED`.
- `plan-customization-reapply` compares, per registered customization, the original hash, the registered copy and the upgraded file: when the upgraded file still equals the original the registered copy is put back (the upgraded file is snapshotted first); otherwise a `CONFLICT` with a unified diff is logged and the file is left alone. Nothing is ever blind-copied.
- `record-upgrade` writes `snapshots/<runId>/upgrade.json`, registers the snapshot set with `referenced_by = upgrade` so retention never prunes it, and audits `upgrade.completed`.
- A failure compensates back to the start of the failing phase; pass `--rollback-all` to go back to point B in the same run, or run `upgrade rollback` afterwards.

### `jrsctl upgrade rollback <runId> --to-point B|C [--plan] [--yes]`

Restores the point-B backups of an earlier upgrade run: `stop-service`, `restore-webapp`, `restore-buildomatic`, `restore-config`, `restore-keystore`, `start-service`, `wait-for-server`, `record-rollback`. Point C restores the same artefacts as point B (spec §10.2: "rollback point C = restore B"); the letter only records where the upgrade got to. Archives are hash-verified before extraction; the replaced webapp and buildomatic trees are moved to `runs/<rollbackRunId>/aside/` and put back if the rollback itself has to be compensated; the configuration and keystore files being overwritten are snapshotted under the rollback run first. Files only: after a `samedb` upgrade restore the database from your own backup before running this.

### `jrsctl customizations register <path> [--original <file>] | unregister <path> | list | diff <path> [--json]`

Registers operator-customised files under `server.installDir` or `server.tomcatDir` so an upgrade can reconcile them (spec §10.3). `register` snapshots the file under `snapshots/cust-<hash>/file` (retention-protected) and records its current hash as the "original"; pass `--original <file>` pointing at the vendor's unmodified copy so the upgrade can tell "the vendor did not change this file" (re-apply automatically) from "the vendor changed it" (report a conflict). `diff` prints a unified diff between the registered copy and the file on disk and exits 0 when they match, 1 when they differ. `unregister` removes the row and the snapshot. Files outside the installation are refused with exit 2.

### `jrsctl runs list [--json] [--limit <n>]`

Runs, most recent first: run id, operation, started, duration, outcome (terminal state and exit code, or `PENDING`).

### `jrsctl runs show <id> [--json]`

One run: the plan summary it was started from (operation, target, files, service, strategy, fingerprint, warnings), every step transition from the journal (`PENDING -> RUNNING -> SUCCEEDED ...` with details such as retry reasons), and the snapshots it took.

### `jrsctl runs recover <id> --resume | --rollback`

For a run that never reached a terminal state. The plan is rebuilt from the stored arguments (the bundle must still be where it was), then:

- `--resume` re-runs the precheck of the interrupted step and re-executes it (steps are idempotent), then continues to the end. If that precheck fails only rollback is offered (exit 2, nothing touched). A run that already began rolling back cannot be resumed.
- `--rollback` compensates every succeeded step in reverse and ends the run as `ROLLED_BACK` (exit 3).

Both take the run lock, journal every transition and print progress like any other run. The recovery request is audited.

### `jrsctl keys list|add|remove|generate`

The trusted key ring for bundle signatures (`keys/trusted/`). The Jaspersoft publisher key is bundled and cannot be replaced or removed.

- `keys list [--json]` — name, fingerprint (first 16 hex of the key's SHA-256), origin.
- `keys add <name> <publicKeyFile>` — trust a public key (one base64 line, X.509 SubjectPublicKeyInfo). Exit 2 if the file is not an Ed25519 key.
- `keys remove <name>` — stop trusting a customer key. Exit 2 if unknown.
- `keys generate <name> --private-out <file>` — generate an Ed25519 pair: the public key is trusted under `<name>`, the private key is written **once** to `<file>` with owner-only permissions (`0600` on Linux; owner + SYSTEM/Administrators only on Windows) and the reference to use is printed: `jrsctl hotfix build <dir> --key file:<file>`. An existing file is never overwritten (exit 2). Keep the private key out of the jrsctl home and out of version control.

Every change is audited.

### `jrsctl secrets init|set|remove|list`

The encrypted store behind `enc:NAME` references (`secrets.enc`, AES-256-GCM, key derived with PBKDF2-HMAC-SHA256 from a machine-bound salt and your passphrase). The passphrase comes from `--passphrase-file`, then `JRSCTL_PASSPHRASE`, then a console prompt when interactive; without one, `init` and `set` exit 2 with the remediation.

- `secrets init` — create an empty store (refuses to overwrite).
- `secrets set <name> [--from-env VAR | --from-file <path>]` — store a value read from an environment variable, a file (trailing newlines ignored) or, when neither is given, from the console (hidden) or stdin. The value is never echoed.
- `secrets remove <name>` — delete an entry (no passphrase needed).
- `secrets list [--json]` — entry names only.

The store is bound to the host name: a copied `secrets.enc` cannot be unlocked on another machine even with the passphrase; recreate it after a host rename.

## Exit codes

| Code | Meaning | Typical cause |
|---|---|---|
| 0 | success | override flags such as `--allow-unsupported` or `--allow-unsigned` do not change the code; they are audited |
| 1 | usage error | unknown command or flag, missing argument, unparseable secret reference |
| 2 | precheck / doctor / fingerprint failure, nothing mutated | configuration or secret problem, unreachable server, failed doctor item, plan inputs changed since planning, confirmation needed but not interactive, planning failed |
| 3 | run failed, rolled back cleanly | a step failed and every succeeded step of the phase (or plan) was compensated |
| 4 | run failed, rollback incomplete — manual action required | a compensation failed, or a fatal step failure after mutation; the outcome block lists backups and the next action |
| 5 | cancelled | Ctrl-C or console cancel; the in-flight step was completed or compensated |
| 6 | unsupported server / configuration | compatibility matrix rejects the combination |
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
| `applicability` FAIL in `hotfix verify` (exit 7) | the bundle targets another version, edition or tenancy | check `jrsctl doctor` and the manifest's `applies` section |
| `N runs need recovery before anything else can run` (exit 8) | a previous run was interrupted | `jrsctl runs show <id>` to see where it stopped, then `jrsctl runs recover <id> --resume` or `--rollback` |
| `run lock is held by run <id> (pid <n>)` (exit 9) | another jrsctl process is mutating this home | wait for it to finish (`jrsctl runs list`); if the pid is dead the OS has released the lock and a retry succeeds |
| `stopped at a precheck; nothing changed` (exit 2) | a step's precheck failed before mutation (disk space, write access, locked file, service state, restart consistency) | follow the `next` line of the outcome block; typical: stop Tomcat, free space, close the program holding the file |
| `refused; inputs changed since planning` (exit 2) | files, server identity or configuration changed between plan and run | run the command again to plan against the current state |
| `failed; rolled back to phase <p>` (exit 3) | a step failed and was compensated | read the `cause` and `next` lines, fix the condition, run again |
| `failed; rollback incomplete, manual action required` (exit 4) | a compensation failed | restore the listed `backup` paths by hand (snapshots keep the original permissions), then `jrsctl doctor`; the run stays in the journal for support |
| `cancelled and rolled back` (exit 5) | Ctrl-C | nothing to do; run again when ready |
| `rollback of <id> refused: blocked by ...` | a later hotfix owns one of the files | roll the later hotfix back first, or pass `--cascade` |

Support bundles (`runs/<id>/support-bundle.zip` with plan, transitions, logs and a redacted state excerpt) arrive with the console in Phase 6; until then attach `logs/jrsctl.log` and the output of `jrsctl runs show <id> --json` to a support request.
