# jrsctl

JasperReports Server lifecycle tool from Actian Jaspersoft. Applies and rolls back hotfixes, exports and imports repository content, and orchestrates upgrades, on the server host, with no JDK or scripting runtime to install.

## Install

Unpack the portable archive for your platform (Windows `jrsctl-<version>-windows-x64.zip`, Linux `jrsctl-<version>-linux-x64.tar.gz`) anywhere on the JasperReports Server host. It bundles its own Java runtime. Verify the `.sha256` sidecar first; see `docs/operator-guide.md` ("Installing").

## Quick start

```
jrsctl selfcheck       # the tool checks itself: runtime, resources, key ring, state schema
jrsctl init            # detect the installation and write config
jrsctl doctor          # check the environment before any change
jrsctl hotfix apply C:\hotfixes\JRS-8.2.0-HF-0004.zip --plan
jrsctl hotfix apply C:\hotfixes\JRS-8.2.0-HF-0004.zip
jrsctl console         # open the local web console
```

Every command explains itself offline: `jrsctl <command> --explain` prints what it does, what it mutates, how it rolls back, its exit codes and flags, and exits without running anything; `jrsctl help <command>` prints the usage synopsis; `jrsctl docs` lists the documentation embedded in the jar (operator guide, hotfix authoring guide, security notes, this file) and `jrsctl docs operator-guide` prints it.

## Five most common commands

| Command | What it does |
|---|---|
| `jrsctl doctor` | Twenty checks with remediation text; run before every change |
| `jrsctl hotfix apply <bundle>` | Plan, confirm, snapshot, apply, verify, record |
| `jrsctl hotfix rollback <id>` | Restore the exact files a hotfix replaced |
| `jrsctl export --uri /organizations/acme --out acme.zip` | Repository export, REST or vendor tools |
| `jrsctl upgrade --to 9.0.0 --package <dir>` | Backed-up, verified upgrade with rollback points |

Every mutating command accepts `--plan` (show only), `--yes` (non-interactive) and `--json`.

## All commands

`selfcheck`, `init`, `doctor`, `smoke`, `config show`, `hotfix build|verify|apply|rollback|list`, `export`, `import`, `upgrade`, `upgrade rollback`, `customizations register|unregister|list|diff`, `runs list|show|recover|prune`, `keys list|add|remove|generate`, `secrets init|set|remove|list`, `console`, `docs`, `help`. Each has a section in `docs/operator-guide.md`; the same text is what `--explain` prints.

## Building from source

Requires JDK 21 and Maven 3.9. `scripts\mvn.cmd verify` on Windows or `scripts/mvn.sh verify` elsewhere. See `CLAUDE.md` and `docs/spec.md`.
