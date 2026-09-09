# jrsctl

JasperReports Server lifecycle tool from Actian Jaspersoft. Applies and rolls back hotfixes, exports and imports repository content, and orchestrates upgrades, on the server host, with no JDK or scripting runtime to install.

## Install

Unpack the portable archive for your platform (Windows `jrsctl-<version>-windows-x64.zip`, Linux `jrsctl-<version>-linux-x64.tar.gz`) anywhere on the JasperReports Server host. It bundles its own Java runtime.

## Quick start

```
jrsctl init            # detect the installation and write config
jrsctl doctor          # check the environment before any change
jrsctl hotfix apply C:\hotfixes\JRS-8.2.0-HF-0004.zip --plan
jrsctl hotfix apply C:\hotfixes\JRS-8.2.0-HF-0004.zip
jrsctl console         # open the local web console
```

## Five most common commands

| Command | What it does |
|---|---|
| `jrsctl doctor` | Twenty checks with remediation text; run before every change |
| `jrsctl hotfix apply <bundle>` | Plan, confirm, snapshot, apply, verify, record |
| `jrsctl hotfix rollback <id>` | Restore the exact files a hotfix replaced |
| `jrsctl export --uri /organizations/acme --out acme.zip` | Repository export, REST or vendor tools |
| `jrsctl upgrade --to 9.0.0 --package <dir>` | Backed-up, verified upgrade with rollback points |

Every mutating command accepts `--plan` (show only), `--yes` (non-interactive) and `--json`.

## Building from source

Requires JDK 21 and Maven 3.9. `scripts\mvn.cmd verify` on Windows or `scripts/mvn.sh verify` elsewhere. See `CLAUDE.md` and `docs/spec.md`.
