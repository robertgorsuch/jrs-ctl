# jrsctl operator guide

`jrsctl` is the JasperReports Server lifecycle tool from Actian Jaspersoft. This guide covers every command: tool self-checks, detection and diagnostics, configuration, signed hotfixes, repository export and import, vendor upgrades with rollback and registered customizations, run history, recovery and snapshot retention, trusted keys, the encrypted secret store, the local web console and the documentation embedded in the tool itself.

This guide is embedded in the tool: `jrsctl docs operator-guide` prints it, and `jrsctl <command> --explain` prints the section of this guide that describes that command. No network access is needed for either.

Everything the tool stores lives under one directory, the **jrsctl home** (`--home`, else `$JRSCTL_HOME`, else `%ProgramData%\jrsctl` on Windows or `/var/lib/jrsctl` on Linux, falling back to `~/.jrsctl` when that is not writable):

| Path | Purpose |
|---|---|
| `config.yaml` | the configuration written by `init` and edited by you; or `jrsctl.properties` with the same dotted keys (ADR-0022), never both |
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
   | `bin\jrsctl.ps1` | the Windows launcher for schedulers and scripts: `powershell -NoProfile -ExecutionPolicy Bypass -File bin\jrsctl.ps1 <command>`. It is not a batch file, so Ctrl-C never blocks on `cmd.exe`'s "Terminate batch job (Y/N)?" and the caller always gets jrsctl's exit code (#33) |
   | `lib/jrsctl.jar` | the application, with this documentation embedded (`jrsctl docs`) |
   | `runtime/` | the bundled Java runtime, used only by jrsctl (it is not a JDK and has no `javac`) |
   | `README.txt`, `LICENSE-THIRD-PARTY.txt` | quick start and the licences of the bundled components |
   | `MANIFEST.sha256` | SHA-256 of every file in the directory; `sha256sum -c MANIFEST.sha256` (Linux) verifies the unpacked tree |

3. **Run it**: `bin\jrsctl.cmd --version`, then `bin\jrsctl.cmd selfcheck`, then `bin\jrsctl.cmd init --install-dir <JRS install dir>` and `bin\jrsctl.cmd doctor`. Run as a user that may stop and start the server service (see "Least privilege" in `docs/security.md`). Adding `bin\` to `PATH` is optional; the launcher locates its own runtime and jar relative to itself and works through symlinks on Linux.
4. **Choose the jrsctl home.** Everything jrsctl stores (configuration, run journal, snapshots, keys, secrets, logs) lives under the home directory described above: `--home <dir>`, else `$JRSCTL_HOME` / `%JRSCTL_HOME%`, else the platform default. Set `JRSCTL_HOME` system-wide (or in the service account's profile) when several operators share one installation, so they share one journal and one run lock. The unpack directory itself is never written to; you can place it on a read-only share.
5. **Upgrading jrsctl**: unpack the new version next to the old one and point at it; the home directory (and its `state.db`) is version-independent, and `selfcheck` reports the state schema version. Remove the old directory once the new one passes `selfcheck` and `doctor`.

`JRSCTL_JAVA_OPTS` passes extra options to the bundled JVM when needed (proxy settings such as `-Dhttps.proxyHost=...`, an extra truststore with `-Djavax.net.ssl.trustStore=...`, or a heap limit). The runtime has no `jdk.localedata`, so output uses English formatting whatever the OS locale.

## Guided mode

Type `jrsctl` with nothing after it, in a terminal, and a menu opens (#71): set up or change settings, check server health, back up content, restore or copy content, apply or remove a hotfix, upgrade the server, and recent jobs with recovery. Each choice asks only for what it needs, prints the command line it is about to run (`Running: jrsctl export --out /backups/repository.zip`), and runs that ordinary command, with the same plan, confirmation and exit code as typing it. An interrupted job that needs recovery is shown before the menu. A hotfix is verified before it is applied, and the upgrade choice will not start until you confirm that the repository database is backed up. Global options given with `jrsctl` (`--home`, `--set`, `--passphrase-file`, `--ascii`, `--no-color`) are passed on to every command. Without a terminal, or with `--json` or `--non-interactive`, `jrsctl` alone still prints its usage and exits 1, so scripts see no change.

## Getting help offline

- `jrsctl --help`, `jrsctl <command> --help`, `jrsctl help <command>` — the usage synopsis: flags, parameters, one line each.
- `jrsctl <command> --explain` — the long form, printed as plain text in a terminal and as Markdown when piped: what the command does, what it mutates (or that it is read-only), how it rolls back, its exit codes and every flag. It prints the command's section of this guide and exits 0 **without running anything**, so it is safe to add to any command line you are about to run, even one with required flags missing: `jrsctl hotfix apply --explain`. On a group (`jrsctl hotfix --explain`) it prints every subcommand's section; `jrsctl --explain` prints the whole command reference.
- `jrsctl docs` lists the embedded documents; `jrsctl docs operator-guide` prints this guide, `jrsctl docs hotfix-authoring` the bundle authoring guide, `jrsctl docs security` the security notes.

Neither `--explain` nor `docs` reads the configuration, the secret store or the server; they print text that was fixed when the jar was built. `jrsctl docs recovery-runbook` prints the recovery runbook: what to do after every non-zero exit code.

## Air-gapped operation

jrsctl is built to run on a server that has no route to the internet.

- **Nothing is downloaded at run time.** The portable archive carries its own Java runtime, every library, the console's static files, this documentation and the compatibility matrix. There is no update check, no telemetry and no CDN reference in the console (the assets are checked for that at build time).
- **`network.mode: isolated`** (the default written by `init`) makes the HTTP client refuse any request to a host other than the one in `server.baseUrl`. A refused request is logged as `FAIL` and audited, so a misconfigured proxy or a redirect to another host cannot leak anything. `network.mode: public` lifts the allowlist and honours `network.proxy` and `network.trustStore`. `network.proxy.noProxy` lists hosts that bypass the proxy (a bare name matches exactly, a `.suffix` matches every host under it); loopback always bypasses. An authenticated proxy works for `https://` base URLs as well, since jrsctl enables Basic authentication on CONNECT tunnels, which the Java runtime disables by default. A `network.trustStore` adds its certificates to the runtime's own CA set instead of replacing it, so a corporate CA and a public certificate both verify. Client certificates are not supported.
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
| `--passphrase-file <file>` | file holding the passphrase that unlocks `secrets.enc` (alternative to `JRSCTL_PASSPHRASE`); refused unless it is readable by its owner only, like a `file:` secret |
| `--yes` | answer yes to every confirmation without asking; implies `--non-interactive` |
| `--non-interactive` | never prompt; exit 2 where a confirmation or a passphrase would be needed. It confirms nothing: unattended runs need `--yes` as well. Without either flag a confirmation is asked on the terminal, or on stdin when stdout is piped (`... \| tee run.log` still asks; end of input means no) |
| `--color <when>` | `auto` (default), `always` or `never`. `auto` colours only where the terminal interprets ANSI escapes, which on Windows means Windows Terminal, ConEmu, ANSICON or a shell that sets `TERM`; plain cmd.exe gets no escapes |
| `--no-color` | the same as `--color=never` |
| `--ascii` | never use the tick and arrow glyphs; status icons become ASCII words (`OK`, `FAIL`, `RETRY`, `UNDO`, `SKIP`). The glyphs are also dropped automatically when the output code page cannot carry them, so a cp850 console shows the words |
| `--json` | emit the result as JSON instead of text (mutating commands: the plan document, then one JSON object per event, then a final `{"outcome": ...}` line) |
| `--explain` | print the long-form explanation of the command and exit 0 without running it |
| `-h`, `--help` / `-V`, `--version` | usage / version banner |

`selfcheck`, `docs` and `help` need no configuration and therefore accept only `--json` (where it applies), `--explain` and `--help`.

Colour is used only when the terminal interprets ANSI escapes, `NO_COLOR` is unset and neither `--no-color` nor `--color=never` was given; glyphs and colour are separate decisions, so a console that cannot print `✔` still gets colour and a terminal without colour still gets glyphs. Every output line passes the redaction filter, so configured secrets never appear in text, JSON or the log.

On Windows, the output of `sc.exe`, `reg.exe` and the other console tools jrsctl reads is decoded with the console (OEM) code page, which `chcp` reports; set `-Djrsctl.console.encoding=IBM850` through `JRSCTL_JAVA_OPTS` if your host uses a code page the probe cannot read.

Environment variables: `JRSCTL_HOME` (home directory), `JRSCTL_PASSPHRASE` (passphrase for `secrets.enc`), `NO_COLOR`, `JRSCTL_JAVA_OPTS` (JVM options for the bundled runtime), plus whatever `env:NAME` references your configuration uses (for example `JRS_PASSWORD`). The vendor tools (`js-export`, `js-import`, `js-ant`) do not receive any `JRSCTL_*` variable or any variable an `env:` reference in the configuration names; a secret held in some other environment variable still reaches them, so prefer `enc:` references (ADR-0019).

Token authentication (`server.auth.mode: token`) sends the pre-authentication token as the `pp` URL parameter by default (`server.auth.tokenLocation: query`), where proxies and Tomcat's access log record it. Set `server.auth.tokenLocation: header` to send it as the `pp` request header instead; JasperReports Server reads the header when its pre-authentication filter's `tokenInRequestParam` is `false` or not set, and refuses the login (exit **2**, nothing changed) when it is `true` (ADR-0018).

## How a mutating command runs

`hotfix apply`, `hotfix rollback`, `export`, `import`, `upgrade`, `upgrade rollback`, `smoke --mutating` and `runs recover` change the server or write archives. They all follow the same path (spec §6):

1. **Pending runs block everything.** If a previous run did not reach a terminal state (crash, kill, power loss), every mutating command exits **8** and prints the exact `jrsctl runs recover <id> --resume|--rollback` command to run first.
2. **The plan is shown first.** You see the operation and target, a summary (files touched, service restart or not, strategy, backups, rollback points, warnings marked `!`), the steps grouped by phase and numbered, the plan fingerprint, and the line `nothing has changed`. The plan is stored for 30 minutes. `--plan` stops here with exit 0.
3. **Confirmation.** `Run this plan? [y/N]` unless `--yes`. Without a terminal and without `--yes` the command exits **2**.
4. **Execution under the run lock.** Steps run in order; each transition is journaled to `state.db` before it is reported. One line per finished step: `✔  03  Snapshot files   0.4s` (`OK` without colour), `✖` on failure with the cause indented, `↻` for retries, `↩` when a step was rolled back, `-` when skipped. Phase headers appear as each phase starts.
5. **Failure handling.** A recoverable failure first undoes the failing step's own partial work, then compensates every succeeded step back to the start of the failing phase (or the whole plan with `--rollback-all`) and exits **3**; a compensation that itself fails exits **4** with the backup locations and the next manual action; a fatal failure exits **4** if anything was mutated, else **2**. If the run journal (`state.db`) itself cannot be written, the run stops at once with exit **4** (**2** when nothing had been mutated yet), says so in the outcome block, and names the `runs recover` command; nothing further is attempted because nothing further could be recorded. A service command the platform refuses (for example `sc.exe stop` answering "Access is denied", or `systemctl start` needing root) fails the step immediately with the command, its exit code and what rights to run with, instead of waiting out the service timeout.
6. **Ctrl-C** cancels through the run's cancellation token: the step in flight finishes or is compensated (never abandoned), then every succeeded step is compensated; exit **5**. The process waits for that work and for the outcome block to be written before it ends, so the exit code is the run's, never the shell's 130 or 143, and the last line is never cut. A retry backoff or a wait for the service to stop or start ends within a fraction of a second of the cancellation rather than running to its own timeout. The process waits up to 30 s for the compensation to complete.
7. **The outcome block** at the end names the step, cause, affected paths, backup location and next action.
8. **Retention pruning** runs automatically after every successful mutating run (best effort; it never changes the run's exit code): per-step snapshots older than `backups.retentionDays` (default 30, `0` disables) or beyond `backups.maxSnapshots` (default 20) are deleted, except those an installed hotfix, a registered customization, the most recent successful upgrade or a run pending recovery still needs. The run directories under `runs/` go with their runs, under the same protection. `jrsctl runs prune --dry-run` shows what the next pruning would remove.

Two rollbacks are weaker than the rest and the plan summary says so in plain text: **`import` rollback is best effort** (it re-imports the pre-import snapshot, which restores overwritten resources but cannot delete resources the failed import created), and **`upgrade --mode samedb` rollback restores files only** (the migrated database must be restored from your own database backup).

## Commands

Every section below has the same shape so that `jrsctl <command> --explain` answers the same four questions for every command: what it **mutates** (or that it is read-only), how it **rolls back**, which **exit codes** it returns, and what its **flags** do. The global flags above apply everywhere and are not repeated.

### `jrsctl selfcheck [--json]`

Verifies the tool itself, with no configuration and no server: the Java runtime it is running on, the bundled resources (schemas, compatibility matrix, publisher key, embedded documentation), the key ring and the state schema version, the host operating system and architecture, and the directory the SQLite native library is unpacked into. Run it after unpacking a new version and before opening a support ticket.

- **Mutates:** nothing; read-only and independent of the jrsctl home.
- **Rollback:** not applicable.
- **Exit codes:** 0 when every item passes; **2** when any item fails (the line names the resource or runtime property); **6** when the failure is the `platform` item, that is, the host is not Windows or Linux on x86-64 (ADR-0002). Every other command refuses such a host with the same exit code before it opens anything.
- **Flags:** `--json` — the report as `{"items": [...], "ok": bool}`.

### `jrsctl init [--install-dir <dir>] [--buildomatic-dir <dir>] [--remote <url>] [--format yaml|properties] [--force]`

Detects the JasperReports Server installation (Tomcat layout, the Windows service or systemd unit, `server.xml` port, the buildomatic directory and its `default_master.properties`, database settings) and writes `config.yaml` into the jrsctl home. Every detected value is shown with its source first and you are asked before the file is written. The repository database type, URL and user are shown but not copied (#73): every command reads them from the installed buildomatic's `default_master.properties`, so the two cannot drift apart, and a value you set in `config.yaml` (or with `jrsctl config set`) overrides the file; `doctor`'s `database` item says when such a value disagrees with `default_master.properties`, and `config keys` shows `default_master.properties` as the source of a value read from it. The proposed server user is `superuser` for the commercial edition (`jasperserver-pro`), where `jasperadmin` administers a single organisation and full-server operations need `superuser`, and `jasperadmin` for the community edition (#59). Secrets are never copied from the installation. Interactively (#63) `init` then asks three questions: **change any of these values?** (Enter keeps them; `y` steps through the server URL, admin user, installation, Tomcat and buildomatic directories, service type and name, database URL and user, and the Java for buildomatic, where Enter keeps the value in brackets and a new value is validated like `--set`, with directories required to exist); **store the passwords encrypted on this machine?** (Enter is yes: the admin and database passwords are read without echo, and on first use a passphrase for `secrets.enc` is chosen and typed twice, unless `--passphrase-file` or `JRSCTL_PASSPHRASE` supplies it); and **write config?** Only after that last yes are the passwords stored in `secrets.enc` and `config.yaml` written with `enc:JRS_PASSWORD` / `enc:JRS_DB_PASSWORD`. A password left empty, or a refused store, keeps its `env:` placeholder, and `init` names the variables still to set. With `--yes`, `--non-interactive` or `--json` nothing is asked and both passwords are written as `env:` placeholders.

- **Mutates:** only `config.yaml` in the jrsctl home, and `secrets.enc` when you chose to store the passwords, after confirmation (skipped with `--yes`). Nothing on the server.
- **Rollback:** an existing `config.yaml` is kept unless `--force`; with `--force` the previous file is overwritten, so copy it first if you may want it back. Stored passwords are removed with `jrsctl secrets remove JRS_PASSWORD` and `jrsctl secrets remove JRS_DB_PASSWORD`.
- **Exit codes:** 0 when the file was written or shown; **2** when no installation is found at the given directory, when the directory is not a JasperReports Server tree, or when confirmation is needed but `--non-interactive` forbids the prompt (pass `--yes`; with `--json` the refusal is an error document). `--json` alone prints the detection report without writing and exits 0.
- **Flags:** `--install-dir <dir>` — root of the installation when auto-detection does not find it (the directory holding `apache-tomcat/` or the Tomcat tree itself); `--buildomatic-dir <dir>` — the installed buildomatic directory when it is not under the installation root (another disk, a mount point or a network share); without it `init` looks under and beside the installation and Tomcat and writes what it finds as `server.buildomaticDir`, with its source; a directory that cannot be reached is reported and not written; `--remote <url>` — write a server-only configuration for a machine that is not the server (#68): the address, the webapp and admin user its path implies (`superuser` for `jasperserver-pro`) and the password placeholder, with no installation, service or buildomatic; such a jrsctl runs REST `export` and `import`, and `doctor` skips its local checks; cannot be combined with `--install-dir` or `--buildomatic-dir`; `--format yaml|properties` — write `config.yaml` (default) or `jrsctl.properties` (#74): `key=value` lines with the keys `jrsctl config keys` lists, values taken literally so a Windows path needs no doubled backslashes, lists comma-separated; `init` refuses to write one format while the other exists, even with `--force`; `--force` — overwrite an existing configuration file; `--json` — print the detection report and the proposed configuration as JSON without writing anything.

### `jrsctl doctor [--allow-unsupported] [--json]`

Read-only health report, the check to run before every change: tool, configuration, secrets, server reachability and identity, authentication, compatibility matrix, service controller, service manager, installation layout, keystore, database, vendor Java. Items are sorted FAIL, WARN, PASS, SKIP and every non-PASS item carries a remediation (`-> ...`). The problems come first, a `----` rule separates them from the items that passed or were skipped, and the closing line names them: `1 fail (server), 0 warn, 21 pass, 2 skip`, in red when anything failed, yellow when only warnings remain, green otherwise. So the last line of a long report on a short terminal still says what to fix.

When the configuration names no installation (`jrsctl init --remote`, #68), the `layout`, `service`, `service-manager`, `permissions`, `keystore`, `vendor` and `vendor-java` items are SKIP with *no local installation configured*, instead of failing.

**No password needed.** `doctor` runs without the admin password and never asks for one or for the store's passphrase. The server is still asked whether it is up and what version it runs, and every local item runs; only `auth`, `capabilities` and `keystore`, the items that log in, are SKIP with *no admin password available* and a line saying what to set (`JRS_PASSWORD`, or the passphrase for `secrets.enc` through `--passphrase-file` or `JRSCTL_PASSPHRASE`). The `secrets` item reports a reference that is merely not supplied yet as a WARN, not a FAIL, and `database` is a SKIP until `database.passwordRef` is supplied; a reference that is present but wrong stays a FAIL. So a plain `jrsctl doctor` on a fresh host exits 0 with a warning and a few skips until the passwords are stored, and the login and database checks run once they are.

The `service-manager` item reports what supervises services on the host. On Linux it reads process 1: systemd passes; supervisord, OpenRC or SysV warns, because a `ctlscript.sh` or `catalina.sh` stop bypasses the supervisor, which may restart the server in the middle of a run; a `service.kind` of `systemd` on a host that is demonstrably not running systemd fails.

The `vendor` item checks `js-export`, `js-import` and `js-ant` in the installed buildomatic directory, which need not be under the installation (ADR-0013): `server.buildomaticDir` when set (any drive, mount point or UNC path; if it cannot be reached the item fails and no other tree is used), else `<server.installDir>/buildomatic`, else an installed tree (this platform's `js-ant` and a `default_master.properties`) beside `server.tomcatDir` or the installation, or inside a `jasperreports-server*` directory under or beside it. The detail names the rule that found it. Two matching trees fail as ambiguous. On Windows a UNC path warns, because `cmd.exe` cannot use it as the working directory of the vendor batch wrappers: map the share to a drive letter, or link it with `mklink /D` (which needs Administrator or Developer Mode), and point `server.buildomaticDir` there. An unreachable share makes `doctor` wait for the network to give up: about 15 seconds for a host that does not resolve or a share that does not exist, up to about a minute for an address that never answers. `JRSCTL_SERVER_BUILDOMATIC_DIR` and `--set server.buildomaticDir=...` override the key like any other. A buildomatic moved away from the installation needs the distribution's `apache-ant` folder next to it, or Ant on `PATH`; the item warns otherwise, because the vendor setup script looks for its bundled Ant only there. jrsctl runs the vendor tools with `vendor.javaHome\bin` first on `PATH`, because the export/import wrappers otherwise start with the machine's default `java`. On Windows with UAC turned on, run commands that use the vendor tools (`export`/`import` with the vendor strategy, `upgrade`) from an elevated window: buildomatic's `bin\date.bat` and `bin\time.bat` start Registry Editor, which asks an administrator account for elevation, and without it they wait for the prompt to time out and then fail.

- **Mutates:** nothing. Logs in and logs out of the server with the configured credentials when the password is at hand, otherwise skips the two login items; reads files under the installation.
- **Rollback:** not applicable.
- **Exit codes:** 0 all good; **2** at least one item failed; **6** the only failure is the compatibility check (the server/configuration combination is outside the matrix).
- **Flags:** `--allow-unsupported` — downgrade the compatibility failure to WARN so diagnostics can continue on an unsupported server; the override is written to the audit table and never changes any other result; `--json` — the report as `{"items": [...], "counts": {...}}`.

### `jrsctl smoke [--mutating] [--json]`

Exercises the server end to end: login, repository listing, a sample report run to PDF (`smoke.reportUri`, a missing sample report is a WARN not a FAIL), a scheduler query and an export round trip. The same checks form the `verify` phase at the end of an upgrade.

- **Mutates:** nothing by default. With `--mutating` it additionally creates, runs and deletes a temporary report under `/temp` as a journaled plan with rollback, like any other mutating command (plan, confirmation, run lock).
- **Rollback:** `--mutating` deletes the temporary resource in its compensation; a failed deletion is reported with the resource uri to remove by hand.
- **Exit codes:** 0 every check passed; **2** a check failed (read-only), **3**/**4** only with `--mutating` when the temporary report could not be removed cleanly.
- **Flags:** `--mutating` — include the create/run/delete round trip; `--json` — the report as JSON.

### `jrsctl config show [--format yaml|properties] [--json]`

Prints the effective configuration after precedence is applied (flag `--set` > environment > `config.yaml` > built-in default) as YAML, followed by one comment line per value an environment variable or `--set` overrides (`# console.port: overridden by JRSCTL_CONSOLE_PORT`). `jrsctl config keys` gives the source of every value. Every secret appears as its reference (`env:NAME`, `file:/path`, `enc:NAME`), never as a value, so the output is safe to paste into a ticket.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when `config.yaml` is malformed or violates the schema (the message names the key).
- **Flags:** `--format yaml|properties` — print YAML (default) or `jrsctl.properties` lines (#74); `--json` — the configuration as JSON, without the override comments.

### `jrsctl config set <key> [<value>]`

Changes one setting in `config.yaml` without editing the file (#70). The new value is checked the way `--set` values are, and the whole file must still pass the schema before it is written. The previous file is kept as `config.yaml.bak`. The command prints `key: old -> new`, and adds a note when an environment variable or `--set` still overrides the key. Without a value it asks for one, showing the current value in brackets (Enter changes nothing).

A password key (`server.auth.passwordRef`, `database.passwordRef`, `network.proxy.passwordRef`, `network.trustStore.passwordRef`, `console.auth.passwordRef`) never takes a password on the command line, where shell history and the process list would keep it. Give a reference (`env:NAME`, `file:/path`, `enc:NAME`), or give no value: the password is then typed without echo and stored in `secrets.enc` under the existing `enc:` name or a default (`JRS_PASSWORD`, `JRS_DB_PASSWORD`, `JRS_PROXY_PASSWORD`, `JRS_TRUSTSTORE_PASSWORD`, `JRS_CONSOLE_PASSWORD`), and the key is set to `enc:NAME`. The store's passphrase comes from `--passphrase-file` or `JRSCTL_PASSPHRASE`, or is asked for (twice for a new store).

A list value (`network.proxy.noProxy`) is given comma-separated. The file is rewritten in its own format, `config.yaml` or `jrsctl.properties`.

- **Mutates:** `config.yaml` (and `config.yaml.bak`) in the jrsctl home; `secrets.enc` when a password is typed. Audited as `config.set` (and `secrets.set`). Nothing on the server.
- **Rollback:** copy `config.yaml.bak` back, or `jrsctl config set <key> <old value>`.
- **Exit codes:** 0 (also when nothing was entered for an ordinary key); **2** for an unknown key (see `jrsctl config keys`), a value the schema refuses, a password given on the command line, or no password or passphrase entered; nothing is written in those cases.
- **Flags:** `<key>` — the dotted setting name; `<value>` — the new value, or a reference for a password key; `--json` — `{key, old, new, file, backup}`.

### `jrsctl config unset <key>`

Removes one setting from `config.yaml`, so its default applies, or nothing when it has none. Prints `key: old -> new` and keeps the previous file as `config.yaml.bak`.

- **Mutates:** `config.yaml` and `config.yaml.bak` in the jrsctl home. Audited as `config.unset`.
- **Rollback:** copy `config.yaml.bak` back, or `jrsctl config set <key> <old value>`.
- **Exit codes:** 0; **2** for an unknown key or when the file without the key fails the schema.
- **Flags:** `<key>` — the dotted setting name; `--json` — `{key, old, new, file, backup}`.

### `jrsctl config keys [--json]`

Every setting the configuration accepts, in schema order, with its current value, where the value comes from (`--set`, the `JRSCTL_*` variable, `config.yaml` or `default`) and a one-line description. Secrets appear as references.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when `config.yaml` is malformed.
- **Flags:** `--json` — an array of `{key, value, source, description}`.

### Applying a hotfix from Jaspersoft support

This is the whole procedure for a cumulative hotfix as support publishes it (`hotfix_JRSPro<version>_cumulative_<date>_<time>.zip`):

1. Download the package and note the checksum shown on the support portal.
2. `jrsctl hotfix apply <package.zip>`. jrsctl reads the package as downloaded, prints its SHA-256 and asks **Does this match the checksum on the support portal?** Answer yes only after comparing them.
3. Read the plan it shows: which files are replaced, added and deleted, whether the server is stopped for the swap, and the readme's manual steps (SQL for particular databases, optional properties) printed as warnings. Confirm to run it.
4. `jrsctl hotfix list` shows it as `JRSHF-<version>-<date>-<time>`; `jrsctl hotfix rollback <id>` puts every file back as it was.

Unattended (`--yes`, `--non-interactive`, `--json`) there is no question, so pass `--allow-unsigned` after checking the checksum yourself. `jrsctl hotfix verify <package.zip>` reports on the package without changing anything. Nothing else is needed: no keys, no manifest, no bundle. The sections below describe each command; "Writing your own hotfix bundles" at the end is for authors.

### `jrsctl hotfix verify <bundle> [--json]`

Checks a bundle without touching the server: the signature over `manifest.json` against the trusted key ring, the SHA-256 of every listed file (and that no unlisted file is present), and applicability (`applies.versions`, `editions`, `tenancy`) to the configured server. Prints a report with the items `signature`, `hashes`, `applicability` and `manifest`, then `bundle <id> ok` or `bundle <id> rejected`.

- **Mutates:** nothing; read-only. Reads the server's `serverInfo` for applicability.
- **Rollback:** not applicable.
- **Exit codes:** 0 when signature, hashes and applicability all pass; **7** when any of them fails; **2** when the bundle cannot be read or the server cannot be reached for applicability.
- **Flags:** `<bundle>` — the ZIP, a jrsctl bundle or an official Jaspersoft hotfix package; `--json` — the report as JSON (`signatureValid`, `signedBy`, `hashesValid`, `hashProblems`, `applicable`, `applicabilityProblems`, `manifestId`, `title`, `ok`).

### `jrsctl hotfix apply <bundle> [--plan] [--yes] [--allow-unsigned] [--rollback-all] [--json]`

Verifies the bundle, builds the plan, shows it and runs it after confirmation (see "How a mutating command runs"). The phases are `verify` (signature, manifest validation against the detected server and the state store, `requires`/`conflicts`, file overlap with installed hotfixes, preflight, the manifest's prechecks), `backup` (snapshot of every file to be replaced or deleted, with permissions), `apply` (stop the service when the manifest says `restart: required`, stage the payload, atomic swap per file, SQL scripts for the configured database, start the service and wait for it, the manifest's postchecks) and `record` (state store and audit).

- **Mutates:** the files the manifest lists under the Tomcat or installation directory, the repository database when the bundle carries SQL, and the service state while the swap happens. **Any change under `WEB-INF/lib` or `WEB-INF/classes` stops the service first, on both operating systems**; the manifest must declare `restart: required` for such files and there is no replace-on-restart. Bundles whose files stay outside `WEB-INF/lib` and `WEB-INF/classes` may declare `restart: none` and are applied with the service running.
- **Rollback:** complete for files: a failure compensates every step back to the start of the failing phase (the whole plan with `--rollback-all`), restoring each file from its snapshot and verifying its hash; SQL is undone by the bundle's `rollbackFile` scripts, or not at all when the manifest declares `"rollback": "irreversible"` (the plan summary shows the author's `rollbackNote` and you must confirm it). After success, `hotfix rollback <id>` undoes the hotfix at any later time.
- **Exit codes:** 0 applied; **2** planning or a precheck failed, nothing mutated (also: confirmation needed without a terminal); **3** a step failed and the plan was rolled back cleanly; **4** rollback incomplete, manual action required (the outcome block lists the snapshot paths); **5** cancelled; **6** the server is unsupported; **7** the bundle's signature is missing or untrusted, or a hash does not match (refused before planning); **8** a previous run needs recovery; **9** another jrsctl process holds the run lock.
- **Flags:** `<bundle>` — the ZIP; `--plan` — show the plan and exit 0 without running; `--yes` — skip the confirmation; `--allow-unsigned` — apply a bundle that carries no signature; the override is written to the audit table (use it only for bundles you built yourself). A signature that is present but verifies against no trusted key is never waived: the bundle names no signer, so an unknown key cannot be told from a bundle altered after signing — add the signer's key with `keys add` or the bundle is refused (exit 7); `--rollback-all` — on failure compensate every step of the plan, not just the failing phase; `--json` — plan document, one JSON object per event, then the outcome.

#### Official Jaspersoft hotfix packages

`hotfix verify` and `hotfix apply` also take an official cumulative hotfix as support publishes it (`hotfix_JRSPro<version>_cumulative_<date>_<time>.zip`), so nothing has to be repackaged first (#66, ADR-0024). jrsctl reads the package and derives a bundle from it, under the jrsctl home and named after the package's own SHA-256, so planning and then applying converts it once:

- `jasperserver-pro.zip` (or `jasperserver.zip`) lands under `webapps/<webappName>/`, `js-install.zip` under the installation directory, which is where the vendor readme says to extract them.
- A file that exists here now is a `replace`, one that does not is an `add`; the readme's "Deleted files" list and the glob deletions in its "Important" section (the libraries an earlier hotfix left) become `delete` entries, resolved against this installation and never covering a file the package itself lays down.
- Anything under `WEB-INF/lib` or `WEB-INF/classes` means the service is stopped for the swap, as for any hotfix.
- The whole package is snapshotted and reversible: `jrsctl hotfix rollback <id>` puts the previous files back, rather than the readme's "copy your backup of the webapp over the top".
- The derived id is `JRSHF-<version>-<date>-<time>`, for example `JRSHF-10.0.0-20260730-0457`, and `hotfix list` shows it like any other.

Two things stay with the operator. An official package carries no jrsctl signature, so `hotfix apply` prints its SHA-256 and asks **Does this match the checksum on the support portal?**; answer yes only after comparing it, since that answer is what stands in for a signature (it is audited as `hotfix.apply.official-confirmed`, ADR-0027). Unattended (`--yes`, `--non-interactive`, `--json`) pass `--allow-unsigned` after checking the checksum yourself. The package's file names are matched loosely (any case, one directory of prefix, a version in the inner archive's name, or the webapp unpacked), and a ZIP that is neither a package nor a jrsctl bundle is refused naming both shapes. And the readme's manual steps — SQL for particular databases, optional properties, settings to apply again in files the package overwrites — are printed as warnings and never run.

### `jrsctl hotfix rollback <id> [--cascade] [--plan] [--yes] [--json]`

Undoes an installed hotfix: restores every file it replaced or deleted from the snapshots taken at apply time (verifying hashes before and after), removes the files it added, runs the bundle's SQL rollback scripts when it has them, stops and restarts the service when the manifest said `restart: required` or the files lie under `WEB-INF/lib` or `WEB-INF/classes` (the same rule apply followed), and marks the hotfix `ROLLED_BACK`. The plan is built from the manifest kept under `runs/<installRunId>/bundle/`; if that copy is gone the command refuses (exit **2**) rather than skip the SQL rollback or the restart it cannot know about — restore the directory from a backup of the jrsctl home, or roll back by hand from `snapshots/<installRunId>/`. Rollback is last-in-first-out per file and per requirement: if a later hotfix owns one of the same files, or its manifest `requires` this one, the rollback is refused and the blocking ids are listed.

- **Mutates:** the files the hotfix touched, the database when rollback SQL exists, the service state when the manifest requires a restart or files under `WEB-INF/lib` or `WEB-INF/classes` are swapped (the service is stopped first, as on apply).
- **Rollback:** this command is itself a rollback; if one of its steps fails, the files already restored are put back to their hotfixed state from the snapshot taken by this run (exit 3), and a failed compensation exits 4 with the paths to restore by hand. A hotfix declared `"rollback": "irreversible"` has no SQL rollback; its files are still restored and the plan says which database changes remain.
- **Exit codes:** 0; **2** the id is unknown, not `INSTALLED`, blocked by a later hotfix (without `--cascade`), or its installing run's bundle copy is gone; **3**, **4**, **5**, **8**, **9** as for every mutating command.
- **Flags:** `<id>` — the hotfix id from `hotfix list`; `--cascade` — roll back the later hotfixes that block this one first, newest to oldest, in one plan; `--plan`, `--yes`, `--json` — as for every mutating command.

### `jrsctl hotfix list [--json]`

Every hotfix recorded in the state store: id, title, installed timestamp, number of files, state (`INSTALLED`, `ROLLED_BACK`, `SUPERSEDED` after an upgrade that did not re-apply it) and the run that installed it.

- **Mutates:** nothing; read-only.
- **Rollback:** not applicable.
- **Exit codes:** 0; **2** when the state store cannot be opened.
- **Flags:** `--json` — the rows as a JSON array.

### Writing your own hotfix bundles (authors only)

Everything above applies a hotfix. Writing one is a different job, done by Jaspersoft support and engineering or by a customer packaging its own fix: a `manifest.json`, a `payload/` directory, a signing key, and `jrsctl hotfix build`. `jrsctl docs hotfix-authoring` is the guide; the one command it uses is below.

### `jrsctl hotfix build <dir> --key <secretRef> --out <bundle>`

Builds a signed bundle from a directory holding `manifest.json`, `payload/` and optional `sql/` and `checks/` (see `jrsctl docs hotfix-authoring`): fills in the SHA-256 of every listed file, validates the completed manifest against the schema and the semantic rules (`action`, the `restart`/WEB-INF rule, the SQL rollback rule), refuses any file in the directory that the manifest does not list, signs `manifest.json` with the Ed25519 private key behind `<secretRef>` and writes the ZIP (`manifest.json` first, `SIGNATURE` second, then the files).

**Before you start:** this command is for the **author** of a hotfix bundle, not for an operator applying one, so `jrsctl hotfix --help` does not list it (#62); `jrsctl hotfix build --help` and `--explain` still work; an operator only needs `hotfix verify` and `hotfix apply`, and bundles from Actian Jaspersoft are already signed with the publisher key built into jrsctl. The author writes `manifest.json` (start from the template in `jrsctl docs hotfix-authoring`, section "Starting from the template") and creates a signing key once with `jrsctl keys generate <name> --private-out <file>`: `--key file:<file>` points at that private key, and every server that will apply the bundle trusts it after `jrsctl keys add <name> <name>.pub` with the public key from `<home>/keys/trusted/`.

- **Mutates:** only the output file. The source directory is never modified; the private key is held in memory as `char[]` and wiped after signing.
- **Rollback:** not applicable; delete the output file if you do not want it.
- **Exit codes:** 0; **1** when `--key` is not a parseable secret reference; **2** when the manifest is invalid (every problem is listed), a listed file is missing, an unlisted file is present, or the key cannot be read or is not an Ed25519 PKCS#8 key.
- **Flags:** `<dir>` — the bundle directory; `--key <secretRef>` (required) — `file:/path` to the one-line base64 PKCS#8 key written by `keys generate`, or `env:NAME` / `enc:NAME` holding the same text; `--out <bundle>` (required) — the ZIP to write (an existing file is replaced).

### `jrsctl export [--uri <uri>]... [--users-roles] [--access-events] [--audit-events] [--monitoring] [--settings] [--full-server] [--stop-service] [--strategy rest|vendor] --out <file> [--plan] [--yes] [--json]`

Exports repository content to a ZIP archive and writes a sidecar `<file>.jrsctl.json` next to it (when it was taken, from which server and version, the request flags, the SHA-256 of the archive and the server keystore fingerprint an import must match). Runs as a plan with one phase, `export`.

**From another machine** (#68). The REST strategy needs only `server.baseUrl` and the credentials, so `export` and `import` run from any machine that reaches the server: write its configuration with `jrsctl init --remote <url>`. A plan that would need the vendor tools there (`--full-server`, `--strategy vendor`, `--source-keystore`, or a server whose REST export or import probe fails) is refused while planning, with exit **2** and the reason REST was not used, because those tools need the installation on the machine they run on. An import from another machine cannot read the server's keystore, so it warns and continues, as for a server whose keystore cannot be found. Hotfixes and upgrades change files and control the service, so they stay on the server itself.

**Strategy rules** (spec §9.2). The plan summary's `strategy` line names the strategy and why it was chosen:

- `rest` — the server stays up; used when the server answers the async export/import probe (`EXPORT_ASYNC` / `IMPORT_ASYNC` capability) and the request is not `--full-server`.
- `vendor` — `js-export` / `js-import` from the installed buildomatic directory (`server.buildomaticDir`, else found as the `doctor` `vendor` item describes) with `vendor.javaHome` as its JDK; for an import the service is stopped first and started (and waited for, up to 10 minutes) afterwards, and for an export it keeps running unless `--stop-service` is given (ADR-0021); used for `--full-server`, for an import that brings `--source-keystore` (the keystore swap needs the server down, whatever `--strategy` says), when the probe fails or the server is unreachable, or when `--strategy vendor` is given. A probe the server refuses with HTTP 401 or 403 is not a failed probe: the command stops with exit **2** naming `server.auth`, because falling back to the vendor tools would stop the service over a wrong password. `server.installDir`, `vendor.javaHome` and a working `service` block are prechecked before the service is touched. Paths holding a space are refused on Linux before the service is stopped, because the vendor shell wrappers pass their arguments unquoted; on Windows, values that `cmd` would split or treat as operators (a comma-joined `--uris` list, an ampersand in a path) are quoted for the batch wrappers; a percent sign cannot be protected from batch expansion, so use the REST strategy for such a URI or path.

- **Mutates:** the output archive and its sidecar only; the repository is read, never changed. The service keeps running; with `--stop-service` and the `vendor` strategy it is stopped for the duration of the export and started again.
- **Rollback:** on failure the partial archive and sidecar are deleted and the service, if it was stopped, is started again.
- **Exit codes:** 0; **2** planning failed (unreachable server, bad configuration, vendor tools not found); **3** a step failed and was compensated; **4**, **5**, **8**, **9** as for every mutating command.
- **Flags:** `--uri <uri>` — repository folder or resource to export, repeatable; without it the whole repository (`/`); `--users-roles` — include users and roles; `--access-events`, `--audit-events`, `--monitoring` — include the corresponding events; `--settings` — include server settings; `--full-server` — export everything (repository, users, roles, settings) with the vendor `js-export` tool, with the server running; `--stop-service` — with the vendor strategy, stop the service while `js-export` runs and start it again afterwards, for an export taken with nothing changing (#67); `--strategy rest|vendor` — force a strategy instead of letting the rules choose; `--out <file>` (required) — the archive to write, an existing file is replaced; `--plan`, `--yes`, `--json` — as for every mutating command.

### `jrsctl import <archive> [--update] [--skip-user-update] [--access-events] [--audit-events] [--monitoring] [--settings] [--skip-themes] [--broken-dependencies fail|skip|include] [--source-keystore <path>] [--source-keystore-password-ref <ref>] [--strategy rest|vendor] [--plan] [--yes] [--json]`

Imports an export archive. The plan has three phases:

1. `precheck` — the archive's sidecar is read and its keystore fingerprint compared with this server's (see below); with the vendor strategy the tools are located. A failure here exits **2** and nothing has been touched.
2. `backup` — **pre-import snapshot**: the affected subtree (the uris recorded in the sidecar, else the whole repository; a full-server export when `--update` targets the root) is exported with the *same strategy as the import* to `<home>/snapshots/pre-import/pre-import-<archive>-<hash>.zip`, sidecar included. The path is listed under `backups` in the plan summary.
3. `import` — the import itself (`POST /rest_v2/import` and polling, or `js-import` with the service stopped and restarted).

**Keystore mismatch.** Repository passwords inside an archive are encrypted with the keystore (`~/.jrsks`) of the server that exported it (JasperReports Server 7.5 and later). The sidecar records that keystore's fingerprint; `import` compares it with this server's keystore, found at the location `<installDir>/buildomatic/keystore.init.properties` names (`ks`, `ksp`) when `server.installDir` is set, else in the home directory of `server.runAsUser` (the Windows service accounts `SYSTEM`, `LocalService` and `NetworkService` resolve to their profile directories under the Windows directory), else in the current user's home. A mismatch is refused with exit **2** before anything is snapshotted or imported:

> `keystore fingerprint mismatch: archive was exported with keystore <a> but this server uses <b>`

Remediation: copy the source server's `.jrsks` and `.jrsksp` to this host and run the import again with `--source-keystore <path> --source-keystore-password-ref <ref>` (the plan backs up the current keystore files, passes the source keystore and its password to `js-import` as its `--keystore` and `--storepass` options, and restores the backup on rollback), or export the data again from a server that shares this keystore. No sidecar, a sidecar without a fingerprint, or a server whose keystore cannot be found produce a warning and the import continues.

- **Mutates:** the repository (resources, and users, roles, events or settings when the corresponding flags are given), the server keystore when `--source-keystore` is used, and the service state with the vendor strategy.
- **Rollback:** **best effort.** If the import phase fails, jrsctl re-imports the pre-import snapshot with `update`. The plan summary states this in so many words: *Rollback re-imports the pre-import snapshot; it restores overwritten resources but cannot delete resources the failed import created.* Check the repository after a rolled-back import (exit **3**) and remove any resources the failed archive added; the snapshot stays under `snapshots/pre-import/` and can be re-imported by hand with `jrsctl import <snapshot> --update`. If the restore itself fails the run exits **4** and the outcome block names the snapshot to re-import. When none of the resources the archive targets existed before the import, the snapshot holds no `index.xml` and the rollback re-imports nothing (the log says `nothing to restore`). With the vendor strategy a run counts as imported only when the import command printed `Done`. An import or restore that threw is a failure even if `js-import` exited 0. When the poll gives up while the server still reports the task running (two hours), or the server answers a definitive error about the task, the run stops with exit **4** and **nothing is re-imported**, because a second import would race the one still in flight: wait for the task to finish, verify the repository, and re-import the snapshot by hand if needed. The same applies when starting the REST import gets an answer that does not say whether the server accepted it (HTTP 502 or 504 from a proxy, or the connection lost): the run stops with exit **4**, the archive is not uploaded a second time and nothing is re-imported; check the server log and the repository for an import started at that time before importing again (ADR-0017). A 408, 429 or 503 is a refusal and the start is retried. A keystore imported with `--source-keystore` is restored from its backup on rollback.
- **Exit codes:** 0; **2** precheck failed (keystore mismatch, unreadable archive, unreachable server, vendor tools missing), nothing touched; **3** the import failed and the snapshot was re-imported (best effort, see above); **4** the snapshot could not be re-imported; **5**, **8**, **9** as for every mutating command.
- **Flags:** `<archive>` — the export ZIP; `--update` — overwrite resources that already exist (otherwise existing resources are kept); `--skip-user-update` — with `--update`, do not overwrite users that already exist; `--access-events`, `--audit-events`, `--monitoring` — import the corresponding events; `--settings` — import server settings; `--skip-themes` — do not import themes; `--broken-dependencies fail|skip|include` — what to do with a resource whose dependency (a data source, a query, an input control) is neither in the archive nor on the server: `fail` (the server default) imports nothing and the run stops as described below, `skip` leaves those resources out, `include` imports them with the dependency missing; `--source-keystore <path>` — the source server's `.jrsks`, passed to `js-import` as `--keystore` together with `--storepass` (vendor strategy only, needs buildomatic); the current keystore files are backed up first; `--source-keystore-password-ref <ref>` — `env:NAME`, `file:/path` or `enc:NAME` holding that keystore's password, never given in clear to jrsctl; `js-import` accepts it only as `--storepass`, so other local accounts can see it in the process list while the import runs (ADR-0020); `--strategy rest|vendor` — force a strategy (same rules as `export`); `--plan`, `--yes`, `--json` — as for every mutating command.

`runs recover <id>` rebuilds export and import plans from their stored arguments (the archive must still be where it was; the snapshot path is derived from the archive's hash, so a resumed import finds the same snapshot).

### `jrsctl upgrade --to <version> --package <dir> [--mode newdb|samedb] [--db-backup-confirmed] [--reapply-hotfixes] [--tomcat-dir <dir>] [--plan] [--yes] [--rollback-all] [--json]`

Upgrades the server with the vendor's own scripts from an unpacked target distribution (`--package` must hold `buildomatic/` with `js-ant` for this operating system and the `jasperserver[-pro]` webapp or a `.war`). The plan has the five phases of spec §10.2; every phase boundary is a rollback point:

| Phase | Steps | Rollback point |
|---|---|---|
| `preflight` | `doctor`, `verify-target-package`, `confirm-db-backup` | nothing mutated |
| `backup` | samedb: `full-export-stop-service`, `full-export` (`js-export --everything`), `full-export-start-service`, `full-export-wait-for-server`, then `backup-keystore`, `backup-webapp`, `backup-config`; newdb: the three `backup-*` steps only | **B**: `snapshots/<runId>/` holds the webapp and buildomatic archives (`zip` on Windows, `tar.gz` on Linux), the keystore and the configuration files, and (samedb) the export |
| `vendor-upgrade` | `write-master-properties`, `stage-keystore-init`, `stop-service`, newdb: `full-export` (`js-export --everything`, taken with the service stopped and kept under `snapshots/<runId>/`), with `--tomcat-dir`: `copy-webapp-to-tomcat`, then `run-vendor-upgrade`, `clear-tomcat-caches`, `clear-repository-cache`, `start-service`, `wait-for-server` | **C** = restore B. In newdb mode the service is not restarted between the export and the vendor run, because the vendor script rebuilds the database from that export and any change made after it would be lost (ADR-0025); a failure here restarts the service through the stop step's compensation |
| `reconcile` | `plan-hotfix-reapply`, `plan-customization-reapply` (plus the apply steps of each re-applied hotfix with `--reapply-hotfixes`; plus `plan-customization-reapply-stop-service`, `-start-service` and `-wait-for-server` around the reapply when a registered customization lives under `WEB-INF/lib` or `WEB-INF/classes`) | restore B |
| `verify` | `smoke`, `record-upgrade`, `point-config-at-target` | a smoke failure offers `jrsctl upgrade rollback <runId> --to-point B` |

- `doctor` must pass (its new `tomcat` item judges the running Tomcat against the running server; the upgrade judges the host Tomcat against the target). Two of its findings are judged against the *target* instead: `compat` and `vendor-java` (a release line may accept a JDK the next one does not, so the vendor JDK can satisfy only one of them). `verify-target-package` checks the package, that the compat matrix lists the path from the running version to `--to` **in the chosen `--mode`**, and that `vendor.javaHome` is one of the Java majors the target's release line accepts; an unlisted path, or a path the vendor offers only in the other mode, exits **6** before anything is planned with the offered mode named. It also reads the target package's own `buildomatic/default_master.properties`, if you prepared one, against the installed file: `installType` must stay what it is (no key means compact), it must not add `audit.*` keys the installed file lacks (exit **6**: the vendor never turns a Compact installation into a Split one in an upgrade, that is the separate `js-migrate-to-split-*` procedure), and with `dbType=oracle` and a target of 10.1 or later `dbVersion` must be set in either file (precheck failure, exit 2, naming the file to edit).
- **What the matrix lists** (from the vendor platform-support sheets and upgrade guides; `core/src/main/resources/compat/matrix.yaml`):

| Release line | JDK for buildomatic | Tomcat | Reached from |
|---|---|---|---|
| 7.1 – 7.9 | 8 | 8.5, 9.0 | 7.x (both modes) |
| 8.x | 8, 11 | 8.5, 9.0 | 7.1 – 7.9 newdb; 8.x both modes |
| 9.x | 8, 11, 17 | 8.5, 9.0 | 8.2 both modes; 8.0 – 8.1 newdb; 9.x both modes |
| 10.0 | 17 | 10.1.24+, 11.0.11+ | 9.0 both modes; 8.x newdb; 10.x both modes |
| 10.1 | 17, 21 | 10.1.24+, 11.0.11+ | 10.0 both modes; 9.0 newdb only |

  10.0 moved to Jakarta EE, so a 9-to-10 upgrade also needs a new Tomcat (the upgrade checks the Tomcat version against this table). Compact and Split installations never cross in one upgrade (`installType`, `audit.*` in `default_master.properties` are carried over as they are, and `verify-target-package` refuses a target file that would change them). Oracle repositories need `dbVersion` (for example `19c`, `21c`, `23ai`) in `default_master.properties` from 10.1 on; the same step checks it.
- `write-master-properties` copies the installed `buildomatic/default_master.properties` into the target package's buildomatic directory **without any password key** (spec §7.4) and adds `appServerType=tomcat` and `appServerDir`; a file already there is kept and restored on rollback. Add `dbPassword` (and any other password the vendor scripts need) to that file yourself before running.
- `stage-keystore-init` writes `keystore.init.properties` into the target package's buildomatic directory, which is how the vendor scripts find the server's keystore when they run under an account other than the one that installed the server (the security guide's `ks`/`ksp` file). It copies the installation's own `buildomatic/keystore.init.properties` when there is one, else names the directories where `.jrsks` and `.jrsksp` were found (`doctor`'s keystore item). Without this file `js-upgrade-*` creates a **new** keystore, silently when run non-interactively, and every password stored in the repository becomes undecryptable; the step refuses to plan when no keystore location is known. A file already in the package is kept under the run directory and put back on rollback.
- `run-vendor-upgrade` runs `js-upgrade-newdb <point-B full export>` or `js-upgrade-samedb` from the target buildomatic when the package ships that script, else what the script itself runs (`js-ant upgrade-minimal-<ce|pro>` with `-Dstrategy=standard -DimportFile=<export>` or `-Dstrategy=inDatabase`), with `JAVA_HOME=vendor.javaHome`, output streamed and redacted, two-hour timeout. With `newdb` the step refuses when the point-B full export is missing. Its compensation is the point-B restore of the files; the database is not restored (see **Mutates**).
- `clear-tomcat-caches` empties `<tomcatDir>/work` and `<tomcatDir>/temp` while the server is still down, the vendor's post-upgrade task (compiled JSPs and temporary files; Tomcat regenerates both). `clear-repository-cache` runs the vendor's two statements, `update JIRepositoryCache set item_reference = null` and `delete from JIRepositoryCache`, through the configured database; stale entries otherwise show up as `local class incompatible` after the first login. Both are marked irreversible in the plan because there is nothing to put back. The cache step is best effort: with no `database` section, or when the database refuses, it logs a warning with the two statements to run by hand and the upgrade continues.
- `plan-hotfix-reapply` lists every installed hotfix as `REAPPLICABLE` (its `applies` matches the new server and every `replaces` target exists in the new webapp: at plan time that is the target package's webapp, unpacked or inside its war, since apply already removed those files from the running one; after the vendor upgrade it is the installation itself) or `SUPERSEDED`. Nothing is re-applied unless `--reapply-hotfixes` was given, in which case the normal apply plan of each re-applicable hotfix (re-packed from `runs/<installRunId>/bundle/`; "bundle no longer available, re-apply manually" otherwise) runs inside the reconcile phase under the run id `<runId>-hf-<hotfix>`. `record-upgrade` marks every hotfix not re-applied `SUPERSEDED`.
- `plan-customization-reapply` compares, per registered customization, the original hash, the registered copy and the upgraded file: when the upgraded file still equals the original the registered copy is put back (the upgraded file is snapshotted first); otherwise a `CONFLICT` with a unified diff is logged and the file is left alone. Nothing is ever blind-copied. When any registered customization is under `WEB-INF/lib` or `WEB-INF/classes` the service is stopped before this step and started and probed again after it, the same rule a hotfix follows; the plan summary warns that this second stop will happen.
- `record-upgrade` writes `snapshots/<runId>/upgrade.json`, registers the snapshot set with `referenced_by = upgrade` so retention never prunes the most recent successful upgrade, and audits `upgrade.completed`.
- `point-config-at-target` (#69) sets `server.buildomaticDir` in jrsctl's `config.yaml` to the target package's buildomatic, which now holds the upgraded server's `default_master.properties`, so later exports, imports and upgrades use the new version's tools without editing the configuration. `config.yaml` as it was before is kept once as `snapshots/<runId>/jrsctl-config.yaml` and put back by the step's compensation and by `upgrade rollback`. `vendor.javaHome` is not changed: preflight already requires it to be the Java the target needs. A `JRSCTL_SERVER_BUILDOMATIC_DIR` variable still overrides the file. Keep the unpacked target package where it is after the upgrade, since jrsctl now uses its buildomatic.

- **Mutates:** the webapp, the installed `buildomatic/` (wherever it resolves, see `doctor`), the configuration files and the keystore; the service state; and the repository database, in both modes: **`--mode newdb` (the default)** makes the vendor script **drop the repository database named in `default_master.properties`, recreate it with the new schema and import the point-B full export into it** (the vendor's `upgrade-newdb.help` lists those steps; ADR-0012); **`--mode samedb`** migrates the existing database's schema in place. jrsctl can undo neither.
- **Rollback:** a failure compensates back to the start of the failing phase; `--rollback-all` goes back to point B in the same run; `upgrade rollback <runId> --to-point B` does the same later. **In both modes the rollback restores files only** — webapp, buildomatic, configuration and keystore — **and jrsctl cannot undo what the vendor script did to the database.** The command refuses to plan (exit **2**) unless `--db-backup-confirmed` states that you hold your own database backup; the confirmation is audited with the mode and the plan summary says in plain text: *Rollback restores files only. Restore the database from your own backup before running rollback.* Taking that database backup, and restoring it, is the operator's responsibility. After a `newdb` run the point-B `full-export.zip` under `snapshots/<runId>/` is a repository-level backup you can re-import with the restored buildomatic's `js-import`; jrsctl does not do that for you.
- **Exit codes:** 0 upgraded and smoke-tested; **2** preflight failed (doctor, package, either mode without `--db-backup-confirmed`, confirmation without a terminal), nothing mutated; **3** a step failed and the run was compensated to the phase start (or to point B with `--rollback-all`); **4** a compensation failed, the outcome block lists `snapshots/<runId>/` and the next action; **5** cancelled; **6** the path from the running version to `--to` is not in the compatibility matrix, or `vendor.javaHome` is the wrong major; **8**, **9** as for every mutating command.
- **Flags:** `--to <version>` (required) — the target version, must be a path the matrix lists; `--package <dir>` (required) — the unpacked target distribution; `--mode newdb|samedb` — `newdb` (default) drops and recreates the repository database from the full export, `samedb` migrates its schema in place; `--db-backup-confirmed` — required in both modes: confirm that the repository database has been backed up (audited); `--reapply-hotfixes` — re-apply every installed hotfix classified `REAPPLICABLE` after the vendor upgrade; `--plan` — show the plan and exit; `--yes` — skip confirmations; `--rollback-all` — on failure compensate back to point B instead of the failing phase; `--json` — plan, events and outcome as JSON.
- **`--tomcat-dir <dir>`** — a new Apache Tomcat for the upgraded server (ADR-0026). JasperReports Server 10 runs only on Tomcat 10.1.24+ or 11.0.11+, so a 9-to-10 upgrade needs one: `verify-target-package` refuses a host Tomcat the compat matrix does not certify for the target, naming the ranges, and warns when the version cannot be read (no `lib/catalina.jar`, no `RELEASE-NOTES`). With the flag the plan copies `webapps/<name>` into the new Tomcat after the stop (`copy-webapp-to-tomcat`; rollback removes the copy, the old Tomcat is untouched), writes `appServerDir` for it, empties its `work` and `temp`, and at the end points `server.tomcatDir` at it. **Needs `service.kind: manual`**: a Windows service, systemd unit or `ctlscript.sh` starts the Tomcat it was registered for, so with any other kind the plan is refused (exit 2) and the message says so. What stays with you: the new Tomcat must listen on the port `server.baseUrl` names, carry the `JAVA_OPTS` the vendor requires for it (`--add-opens` on Tomcat 11 with JDK 17 or 21), and be registered as the service afterwards.

### `jrsctl upgrade rollback <runId> --to-point B|C [--plan] [--yes] [--json]`

Restores the point-B backups of an earlier upgrade run: `stop-service`, `restore-webapp`, `restore-buildomatic`, `restore-config`, `restore-keystore`, `restore-jrsctl-config` (jrsctl's own `config.yaml` as it was before the upgrade, when the upgrade saved one), `start-service`, `wait-for-server`, `record-rollback`. Point C restores the same artefacts as point B (spec §10.2: "rollback point C = restore B"); the letter only records where the upgrade got to. Archives are hash-verified before extraction.

- **Mutates:** the webapp and `buildomatic/` trees (replaced from the archives), the configuration files and the keystore (overwritten from the backups), the service state. The database is never touched. Each archive is unpacked first into a staging directory beside the tree it replaces, and beside `webapps/` for the webapp (`<tomcatDir>/.jrsctl-restore-<webapp>`), so a Tomcat started after a crash never deploys it.
- **Rollback:** the replaced webapp and buildomatic trees are moved to `runs/<rollbackRunId>/aside/` and put back if the rollback itself has to be compensated; the configuration and keystore files being overwritten are snapshotted under the rollback run first. **Files only**: restore the database from your own backup before running this, whichever mode the upgrade used — `samedb` migrated its schema, `newdb` dropped and recreated it (ADR-0012). After a `newdb` upgrade the plan names the point-B `full-export.zip` you can re-import with the restored buildomatic's `js-import`.
- **Exit codes:** 0; **2** the run id is not a completed upgrade run or its snapshot set is missing; **3**, **4**, **5**, **8**, **9** as for every mutating command.
- **Flags:** `<runId>` — the upgrade run from `runs list`; `--to-point B|C` (required) — the rollback point, both restore the point-B artefacts; `--plan`, `--yes`, `--json` — as for every mutating command.

### `jrsctl customizations register <path> [--original <file>] [--json]`

Registers an operator-customised file under `server.installDir` or `server.tomcatDir` so an upgrade can reconcile it (spec §10.3): the file is snapshotted under `snapshots/cust-<hash>/file` (retention-protected) and its current hash recorded as the "original". Pass `--original <file>` pointing at the vendor's unmodified copy so the upgrade can tell "the vendor did not change this file" (re-apply automatically) from "the vendor changed it" (report a conflict).

- **Mutates:** the state store row and the snapshot under the jrsctl home. Nothing under the installation.
- **Rollback:** `customizations unregister <path>` removes the row and the snapshot.
- **Exit codes:** 0; **2** when the file is outside the installation, does not exist, or is already registered.
- **Flags:** `<path>` — the customised file; `--original <file>` — the vendor's unmodified copy whose hash becomes the original; `--json` — the registration as JSON.

### `jrsctl customizations scan --vendor <path> [--register] [--json]`

Finds the files the site changed without registering them one by one (#72). The installed webapp (`server.tomcatDir/webapps/<webappName>`) is compared with the vendor's untouched copy of the same version. `--vendor` is the unpacked distribution, its `jasperserver-pro` (or `jasperserver`) directory, or the `.war`, which is read as a stream and never unpacked. Every file that differs is listed:

- `CHANGED` — in both, different: a customization.
- `ADDED` — only in the installation: a customization.
- `INSTALLER` — a file the installer writes with site values, whether it differs from the vendor's copy or the vendor has none: `META-INF/context.xml`, `WEB-INF/js.quartz.properties`, `WEB-INF/classes/keystore.init.properties`, and, depending on the repository database, `WEB-INF/hibernate.properties` and `WEB-INF/js.jdbc.properties`. Listed, never registered.
- `REMOVED` — only in the vendor's copy. Listed, nothing to register.

Logs, caches and work files are ignored, and so are backup copies left beside an edited file (`*.bak`, `*.bak-<date>`, `*.orig`, `*.old`, `*~`). With `--register`, `--yes`, or a yes at the prompt, every changed and added file not yet registered is registered: a changed file with the vendor file's hash as its original, so an upgrade re-applies it automatically where the vendor left the file alone, and an added file with its own hash.

- **Mutates:** nothing without registration; with it, as `customizations register` for each file.
- **Rollback:** `customizations unregister <path>` for a file registered by mistake.
- **Exit codes:** 0; **2** when `--vendor` holds no webapp or the installed webapp cannot be found.
- **Flags:** `--vendor <path>` (required) — the vendor's copy; `--register` — register without asking; `--json` — `{installedWebapp, vendorWebapp, entries: [{path, change, registered}], registered: [...]}`.

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

Applies the snapshot retention policy by hand: deletes per-step snapshots that are older than `backups.retentionDays` (default 30; `0` disables age pruning) or beyond `backups.maxSnapshots` (default 20, newest kept), **never** deleting a protected snapshot. Protected are the snapshots of every `INSTALLED` hotfix (its rollback needs them), of every registered customization, of the **most recent** `SUCCEEDED` upgrade only (older upgrades' sets become prunable), and of every run pending recovery (`runs recover --rollback` needs them). `maxSnapshots` counts protected snapshots too, so the total on disk can stay above the cap when enough are protected. Run directories (`runs/<runId>/`: bundle copies, staging, markers) are removed with the same protection: only for a run that has ended, started more than `backups.retentionDays` ago and is not protected, nor a `<runId>-hf-<hotfix>` re-application inside a protected upgrade, so an installed hotfix always keeps the bundle copy its rollback needs; they are listed as `runs/<runId>` rows. The text output is a `SNAPSHOT / RUN / STEP / PATH` table and a summary line, or `nothing to prune`.

The same pruning runs automatically after every successful mutating run (best effort: the finished run's own snapshots are protected, a failure is logged as a warning and never changes the run's exit code), so this command is for reclaiming space between runs or for previewing what the next automatic pruning would remove. Known gap: the upgrade set directories (`snapshots/<runId>/upgrade/` with the webapp archive and the full export) are not per-step snapshots and are not pruned; only the per-step snapshots of that run are.

- **Mutates:** snapshot files and directories under `<home>/snapshots/` and their rows in the state store. Nothing on the server. Not a journaled run (no entry in `runs list`), but it takes the run lock while it deletes and writes one audit row with action `runs.prune`.
- **Rollback:** none; a pruned snapshot is gone. Protected snapshots are never candidates, and `--dry-run` shows the exact list before anything is removed.
- **Exit codes:** 0 (also when nothing qualifies); **2** when the configuration or state store cannot be read; **9** when another jrsctl process holds the run lock (the message names its run id and pid).
- **Flags:** `--dry-run` — list what would be removed and remove nothing (read-only, no lock); `--json` — exactly `{"dryRun": bool, "removed": [{"id", "runId", "stepId", "path"}], "kept": n, "protected": n}` where `kept` is the number of snapshots remaining on disk after the pass and `protected` how many of those belong to a protected run; removed run directories appear in `removed` with `stepId` `*` and do not change either count.

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

Stops trusting a customer key: deletes `keys/trusted/<name>.pub`. Bundles signed with it are refused from then on (exit 7 on `hotfix apply`; `--allow-unsigned` does not waive a signature that fails to verify, only a missing one). Hotfixes already installed are unaffected. Audited.

- **Mutates:** `keys/trusted/<name>.pub` in the jrsctl home.
- **Rollback:** `keys add <name> <publicKeyFile>` with the saved public key.
- **Exit codes:** 0; **2** when the name is unknown or is the bundled publisher key.
- **Flags:** `<name>` — the key name.

### `jrsctl keys generate <name> --private-out <file>`

Generates an Ed25519 key pair for signing your own bundles. Like `hotfix build` it is for bundle authors and is not listed in `jrsctl keys --help` (#62): the public key is trusted under `<name>` as if added with `keys add`, and the private key is written **once** to `<file>` as one base64 PKCS#8 line with owner-only permissions (`0600` on Linux; owner + SYSTEM/Administrators only on Windows). The reference to use is printed: `jrsctl hotfix build <dir> --key file:<file>`. Keep the private key out of the jrsctl home and out of version control; `secrets set <name> --from-file <file>` stores it as `enc:<name>` if you prefer. Audited.

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

Serves the local web console until Ctrl-C, or until you type `stop` and Enter. On start it prints one line:

```
Console: http://127.0.0.1:7420/#token=<token>
```

Open that URL: the token in the fragment is the per-launch key to the API and is never shown again (it is redacted from every log, response and support bundle). When a terminal is present the default browser is opened for you with a single-use launch code rather than the token, because a browser command line is readable by every local account; the code is worth ten seconds and is exchanged for the token by the page itself. On a home other accounts can reach, no browser is opened and the reason is printed; pass `--open` to override that, or `--no-open` to skip the browser anywhere, for example from a service or a script.

**From a server without a desktop** (#64): run `jrsctl console --no-open` on the server, then on your own computer `ssh -L 7420:127.0.0.1:7420 <user>@<server>` (the port of the `Console:` line) and open the printed URL in a local browser. The console stays bound to the server's loopback address, so neither TLS nor `console.auth.mode: local` is needed, and the token still never crosses the network outside the SSH connection. On Linux, when neither `DISPLAY` nor `WAYLAND_DISPLAY` is set and the console is bound to loopback, `jrsctl console` opens no browser and prints this `ssh` command with the actual port, `$USER` and `$HOSTNAME` instead. A home jrsctl creates is owner-only on both operating systems (on Windows one inheritable entry for its owner, with the permissions of `%ProgramData%` no longer inherited); an existing one is left as you set it up. To make an existing Windows home private, run `icacls "%ProgramData%\jrsctl" /inheritance:r /grant:r "%USERNAME%":(OI)(CI)F` as the account that runs jrsctl.

The web console provides a lightweight, zero-build, air-gapped operational station featuring:
- **Dashboard (`#/dashboard`)**: Server state, health summary, active locks, pending runs requiring recovery, installed hotfixes, and quick links to operational stations.
- **Smoke Testing Station (`#/smoke`)**: Single-click synthetic validation probing login authentication, repository root listings, sample PDF report execution, Quartz scheduler state, catalog export round-trip, and mutating deployment tests (`--mutating`), with JSON report export.
- **Customizations Registry (`#/customizations`)**: Manage registered customized files under `installDir` and `tomcatDir`, check SHA-256 integrity against baseline vendor snapshots, and view side-by-side or unified line-by-line diffs in-browser.
- **Snapshots & Storage Lifecycle (`#/snapshots`)**: Monitor disk consumption across historical pre-mutation rollback snapshots, inspect file manifests, check retention protection tags (`UPGRADE_CHECKPOINT`, `CUSTOMIZATION_PROTECTED`), and trigger retention pruning on demand.
- **Server Configuration & SelfCheck (`#/config`)**: Read-only inspection of target URLs, auth settings, platform runtime environment, SQLite schema status, trusted Ed25519 signing keys, and redacted `config.yaml`.
- **Interactive Repository Picker**: Visual folder tree browser inside Export and Import operation forms for folder URI selection.
- **Live Run Monitor & Comparison (`#/runs/<id>`)**: Real-time SSE execution telemetry with text search, severity filters (INFO, WARN, ERROR), step duration timeline indicators, and side-by-side historical run comparison.
- **Keyboard Shortcuts**: Press `?` for cheat sheet, `/` to focus search/log filter, `r` to refresh view, `d` for dashboard, `n` for new operation, and `Escape` to dismiss active dialogs.

Runs started from the console go through the same plan, confirmation, fingerprint, run lock and journal as the CLI; they are non-interactive, so a step that would need a terminal prompt fails instead of waiting. `GET /api/runs/<id>/support-bundle` (the "Support bundle" button on a run) downloads a redacted zip of the plan, journal, events, server identity, doctor report, effective configuration and the last 2000 lines of the log this process is writing, to attach to a ticket. The doctor report is produced before the download starts, so a server that cannot be reached gives you an error rather than a zip that is missing its tail. A run that needs recovery blocks new runs in the console exactly as it does on the CLI; its run page offers Resume and Roll back.

- **Mutates:** by itself only `<home>/console.token` (owner-only, deleted on stop). Operations started from the console mutate exactly what the corresponding CLI command mutates, under the same rules, with audit actor `console`.
- **Rollback:** per operation, as on the CLI; the run page offers cancel and rollback through the same code as `runs recover`.
- **Exit codes:** 0 after a clean stop; **2** when the bind address or TLS material is refused or the port is busy.
- **Flags:** `--bind <addr>` — override `console.bind` (default `127.0.0.1`); a non-loopback bind is refused with exit 2 unless `console.tls.enabled: true` (`certPath` PEM chain, `keyPath` unencrypted PKCS#8 PEM) and `console.auth.mode: local` (`passwordRef` for the operator password) are both configured, see `docs/security.md`; `--port <n>` — override `console.port` (default `7420`); `--port 0` picks a free port; `--open` / `--no-open` — open (default when a terminal is present) or do not open the default browser.

### `jrsctl docs [<name>] [--format auto|text|markdown] [--json]`

Offline documentation. Without an argument it lists the documents embedded in the jar at build time (name, title, size); with a name it prints that document to standard output: as plain text in a terminal (headings underlined, tables aligned or listed row by row, no Markdown markup, wrapped to `COLUMNS` or 100 columns), and as its Markdown source when redirected to a file or a pipe (#60). The embedded documents are `operator-guide` (this guide), `hotfix-authoring` (bundle format, `hotfix build`, signing, testing a bundle), `security` (threat model, key management, console token, hardening) and `readme`.

- **Mutates:** nothing; read-only and independent of the jrsctl home, configuration and server.
- **Rollback:** not applicable.
- **Exit codes:** 0; **1** when the name is not one of the embedded documents (the message lists the valid names).
- **Flags:** `<name>` — the document to print; `--format auto|text|markdown` — `auto` (default) chooses by whether standard output is a terminal, `text` forces plain text (for example `jrsctl docs operator-guide --format text | less`), `markdown` forces the source; `--json` — the listing as a JSON array of `{"name", "title", "bytes"}` (ignored when a name is given).

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

**Ctrl-C on Windows.** `bin\jrsctl.cmd` is a batch file, so after Ctrl-C `cmd.exe` asks `Terminate batch job (Y/N)?` when jrsctl has already cancelled and exited. Measured on Windows 11 with a real console Ctrl-C: the question waits for an answer, so an unattended caller hangs on it and never sees the exit code (#33). The run itself is finished either way, and the answer changes nothing in it.

A scheduler or script should therefore use the PowerShell launcher, which is not a batch file, never asks that question and passes jrsctl's own exit code back:

```
powershell -NoProfile -ExecutionPolicy Bypass -File <install>\bin\jrsctl.ps1 <command> [options]
```

It reads `JRSCTL_JAVA_OPTS` like `bin\jrsctl.cmd`. Starting the bundled runtime directly works as well, but then `JRSCTL_JAVA_OPTS` is not read, so any JVM options go on the command line:

```
<install>\runtime\bin\java.exe -jar <install>\lib\jrsctl.jar <command> [options]
```

## Error classes and what to do

| You see | It means | Do this |
|---|---|---|
| `error: ... config.yaml ...` / schema violation (exit 2) | the configuration is missing, malformed or fails the schema | run `jrsctl init`, or fix the key named in the message; `jrsctl config show` prints the effective view |
| `environment variable X is not set (referenced by env:X)` (exit 2) | a secret reference points at an unset variable | export the variable, or switch the reference to `enc:NAME` after `jrsctl secrets set NAME` |
| `vendor` FAIL `server.buildomaticDir ... is not a directory this account can reach` | the configured buildomatic directory is on a volume or share that is not mounted, or this account cannot read it | mount or reconnect it, or correct `server.buildomaticDir`; jrsctl never falls back to another tree |
| `service did not stop within Ns (state RUNNING, catalina script ...)` (exit 3) although Tomcat's log shows it stopping | JasperReports Server leaves non-daemon threads behind, so the JVM outlives `catalina stop` (issue #42); `windows-service` and `systemd` are not affected | set `service.forceStopAfterSeconds` (for example `60`, below `service.stopTimeoutSeconds`) so jrsctl ends this Tomcat's JVM after that grace period and logs it (ADR-0016), or point `service.scriptPath` at your own stop script that runs `catalina stop` and then ends only this Tomcat's JVM |
| `service state cannot be determined` (exit 2) on Windows with `service.kind` `catalina`, `ctlscript` or `manual` | jrsctl could not list processes, or a Java process of another account whose command line cannot be read without elevation listens on one of this Tomcat's `server.xml` ports, so it might be this Tomcat (ADR-0014) | run jrsctl as the account that runs Tomcat, or elevated, or use `service.kind: windows-service` |
| `js-export` or `js-import` output `'ant' is not recognized` | buildomatic was moved away from the installation without the `apache-ant` folder that sits next to it | copy `apache-ant` next to the buildomatic directory or put Ant on `PATH`; `doctor` warns about this |
| `js-export` output `Invalid profile [null]: must contain text` and no archive (Windows, jrsctl 1.2.0 and earlier) | the vendor `js-export.bat` never passes `js.cache.provider` to the export tool, unlike `js-import.bat` and the Linux scripts | upgrade jrsctl: since 1.3.0 it adds `-Djs.cache.provider` to `JAVA_OPTS`, read from buildomatic's `cache.properties` (default `infinispan`), unless your own `JAVA_OPTS` already sets it; with an older jrsctl set `JAVA_OPTS=-Djs.cache.provider=infinispan` before running it |
| `js-export` or `js-import` waits minutes, then `Access is denied` and `=1-4* was unexpected at this time` (Windows) | the vendor date and time helpers start Registry Editor, which needs elevation for an administrator account under UAC | run the command from an elevated window |
| `vendor` FAIL `more than one installed buildomatic directory found` | discovery matched two installed trees | set `server.buildomaticDir` to the one this server was installed from |
| `secret file ... is readable by other users` (exit 2) | a `file:` secret is not owner-only | `chmod 600` on Linux; on Windows remove ACL entries other than the owner, SYSTEM and Administrators |
| `passphrase does not unlock secrets.enc ...` / `no passphrase available` (exit 2) | wrong passphrase, store copied from another host, or nothing supplied non-interactively | set `JRSCTL_PASSPHRASE` or pass `--passphrase-file`; recreate the store if the host was renamed |
| `server` FAIL in doctor, `connection refused` (exit 2) | the server is down or `server.baseUrl` is wrong | start the server or fix `server.baseUrl`; check proxies and `network.mode` |
| `compat` FAIL (exit 6) | the server version/edition is outside the compatibility matrix | use a supported version, or `--allow-unsupported` for diagnostics only |
| `the bundle carries no SIGNATURE` (exit 7) | the bundle is unsigned | have it signed, or `--allow-unsigned` for bundles you built yourself |
| `the bundle signature verifies against no trusted key` (exit 7) | signed by a key you have not added, or altered after signing; the two cannot be told apart | `jrsctl keys add <name> <publicKeyFile>` for the signer's key; `--allow-unsigned` does not waive this |
| `bundle rejected, file hashes do not match the manifest` (exit 7) | the ZIP was altered or corrupted after signing | obtain the bundle again from its publisher |
| `files not listed in the manifest: ...` from `hotfix build` (exit 2) | the bundle directory holds a file the manifest does not declare | list it under `files`, `sql` or `checks`, or delete it; see `jrsctl docs hotfix-authoring` |
| `restart 'none' is not allowed for files under WEB-INF/lib or WEB-INF/classes` (exit 2) | the manifest claims no restart for a file that needs the service stopped | set `"restart": "required"` |
| `keystore fingerprint mismatch: archive was exported with keystore ...` (exit 2) | the archive comes from a server with a different `.jrsks`; its encrypted passwords cannot be decrypted here | copy the source server's `.jrsks` and `.jrsksp` here and import again with `--source-keystore <path> --source-keystore-password-ref <ref>`, or export again from a server that shares this keystore |
| `server holds import ... pending (import.broken.dependencies: /public/...); nothing was imported` (exit 3) | the archive references resources that are neither in it nor on the server, and the server stopped before importing anything; jrsctl cancelled the task | run again with `--broken-dependencies skip` (leave those resources out) or `--broken-dependencies include` (import them with the dependency missing), or export the archive again with its dependencies; the snapshot re-import of the rollback changed nothing |
| `server holds import ... pending (import.organizations.not.match ...)` (exit 3) | the archive was exported from an organisation other than the one it is being imported into | import it with the vendor tools' `--organization` or `--merge-organization` options, or export it again from the matching organisation |
| `server reported import ... failed` followed by `rolled back to phase import` (exit 3) | the server rejected the archive; the pre-import snapshot was re-imported | check the jasperserver log; the snapshot restores overwritten resources only, so remove anything the failed import created |
| `vendor upgrade created a new keystore because it found none to reuse` followed by `rolled back` (exit 3) | the vendor script did not find the server's keystore and made a new one, even though it exited 0; the rollback put the saved `.jrsks` and `.jrsksp` back | check that `stage-keystore-init` wrote `keystore.init.properties` into the target buildomatic with the right `ks`/`ksp`, that the account running jrsctl can read those files, and run the upgrade again |
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
