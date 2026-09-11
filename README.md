# jrsctl

`jrsctl` is a dedicated lifecycle management and operations tool for **Actian JasperReports Server (JRS)**. It delivers safe, automated, and auditable server administration—including environmental diagnostics, cryptographic hotfix management, repository content migrations, version upgrades, and credential security.

Designed for operational safety in enterprise and air-gapped environments, `jrsctl` ships as a self-contained portable package with its own bundled Java runtime, requiring no pre-installed JDK, Python, or external scripting dependencies on the host server.

---

## Key Capabilities

### 1. System Diagnostics & Pre-flight Validation
* **Self-Verification (`jrsctl selfcheck`)**: Validates internal binary integrity, bundled runtime health, SQLite journal schema version, and cryptographic key rings without requiring a target server or configuration file.
* **Auto-Discovery & Initialization (`jrsctl init`)**: Inspects the host to detect JasperReports Server installations (Tomcat layout, Windows services / systemd units, ports, database configurations, and `default_master.properties`), generating a tailored `config.yaml`.
* **Diagnostic Engine (`jrsctl doctor`)**: Executes over 20 pre-flight environmental checks—including port availability, directory permissions, database connectivity, keystore consistency, disk space, and compatibility matrix validation—accompanied by actionable remediation guidance.
* **Functional Smoke Testing (`jrsctl smoke`)**: Runs non-destructive end-to-end probes against running JRS instances via REST APIs (login, server info, repository listing, PDF report execution, scheduler) to verify operational readiness.

### 2. Cryptographic Hotfix Lifecycle
* **Authoring & Verification (`jrsctl hotfix build`, `jrsctl hotfix verify`)**: Packages and cryptographically verifies hotfix bundles using manifest validation, per-file SHA-256 hashes, and Ed25519 digital signatures against trusted public keys.
* **Plan-Driven Application (`jrsctl hotfix apply`)**:
  * Evaluates prerequisites, compatibility, and file collisions against installed hotfixes.
  * Generates an execution plan preview (`--plan`) before applying changes.
  * Takes automated file-level snapshots prior to replacing or modifying files.
  * Orchestrates service lifecycle (e.g., stopping Tomcat before updating `WEB-INF/lib` or `WEB-INF/classes`).
  * Applies idempotent database SQL patches and performs post-apply validation.
* **Deterministic Rollback (`jrsctl hotfix rollback`)**: Restores original files from versioned snapshots and executes reverse SQL rollback scripts in last-in-first-out (LIFO) order.
* **Inventory (`jrsctl hotfix list`)**: Lists installed, superseded, and rolled-back hotfixes with timestamps and status.

### 3. Repository Content Import & Export
* **Resource Migration (`jrsctl export`, `jrsctl import`)**: Exports and imports repository resources, users, roles, organizations, access events, and server configuration catalogs.
* **Dual-Engine Execution**: Intelligently switches between live REST v2 async APIs (for online exports) and vendor CLI tools (`js-export`/`js-import` / `buildomatic`) with automated service control for full-server operations.
* **Keystore Fingerprinting**: Tracks cryptographic keystore fingerprints across export catalogs and validates them prior to import, guarding against secret decryption failures.
* **Pre-Import Snapshots**: Captures affected repository subtrees before import to enable rollback restoration.

### 4. Server Upgrades & Customization Tracking
* **Upgrade Orchestration (`jrsctl upgrade`, `jrsctl upgrade rollback`)**: Automates JasperReports Server version upgrades using vendor `buildomatic` scripts, enforcing pre-upgrade backups (export catalog, keystore, webapp, configurations).
* **Safe Database Modes**: Defaults to isolated new-database mode for full file and connection rollback, and requires explicit confirmation (`--db-backup-confirmed`) for same-database upgrades.
* **Customization Registry (`jrsctl customizations register|unregister|list|diff`)**: Tracks site-specific themes, plugins, and configuration files across upgrades, performing three-way diffs to re-apply changes or surface conflicts.
* **Post-Upgrade Hotfix Reconciliation**: Classifies previously installed hotfixes as superseded or re-applicable against the target release.

### 5. Operational Resilience & Recovery
* **Atomic Step Engine**: Breaks down all mutating operations into structured, idempotent steps with defined compensation actions for clean rollbacks upon failure.
* **Append-Only SQLite Journal (`state.db`)**: Records all operations, step transitions, plan fingerprints, snapshots, and audit events.
* **Concurrency Locking (`runs.lock`)**: Enforces single-process execution per JRS host to prevent concurrent conflicting operations.
* **Crash Recovery (`jrsctl runs list|show|recover|prune`)**: Automatically detects interrupted or abandoned runs and safely resumes or rolls back lingering state.
* **Snapshot Retention Pruning**: Automatically prunes historical snapshots based on retention policies while preserving any snapshot required by active hotfixes, customizations, or pending recoveries.

### 6. Security & Secret Management
* **Encrypted Secret Store (`jrsctl secrets init|set|remove|list`)**: Protects passwords and tokens in an AES-256-GCM encrypted store (`secrets.enc`) with stable machine/passphrase salt derivation, allowing safe configuration referencing (`enc:SECRET_NAME`).
* **Trusted Key Ring (`jrsctl keys list|add|remove|generate`)**: Manages trusted public keys used for signature verification of update packages, pinned with the official Jaspersoft publisher key.
* **Automatic Output Redaction**: Sanitizes console output, JSON streams, log files, and SQLite journal transition records to prevent credential and key leakage.
* **Owner-Only Permissions**: Automatically restricts access permissions (`rw-------`) on secret stores, keys, and tokens.

### 7. Modern Operator Interfaces
* **Self-Explaining CLI (`--explain`, `jrsctl docs`)**: Provides offline documentation embedded in the binary without requiring external internet access (`jrsctl docs operator-guide`, `jrsctl docs security`, `jrsctl docs hotfix-authoring`).
* **Interactive & Scriptable CLI**: Supports interactive prompts, non-interactive CI automation (`--yes`, `--non-interactive`), and machine-readable JSON output (`--json`).
* **Local Web Console (`jrsctl console`)**: Features an embedded, lightweight web interface (powered by Javalin and Server-Sent Events) for monitoring runs, inspecting plans, and viewing diagnostics in real-time, protected by single-use launch tokens.

---

## Platform & Environment Support

| Component | Supported Range / Technologies |
| :--- | :--- |
| **JRS Releases** | JasperReports Server 7.1 through 10.x (Community & Commercial / Enterprise) |
| **Tenancy Models** | Single-tenant and Multi-tenant (Organization-scoped) |
| **Operating Systems** | Windows x86_64, Linux x86_64 |
| **Application Servers** | Apache Tomcat (Windows Service, systemd, ctlscript, catalina script, manual) |
| **Repository Databases** | PostgreSQL, MySQL, Oracle, Microsoft SQL Server, IBM DB2 |
| **Host Dependencies** | None (Bundles custom Java 21 runtime via `jlink`) |

---

## Directory Layout & Storage

All persistent configuration and runtime state is contained within the **jrsctl home** directory (resolved via `--home <dir>`, `$JRSCTL_HOME` / `%JRSCTL_HOME%`, `%ProgramData%\jrsctl` on Windows, or `/var/lib/jrsctl` / `~/.jrsctl` on Linux):

```
<JRSCTL_HOME>/
├── config.yaml              # Installation paths, endpoints, and credentials
├── state.db                 # SQLite WAL journal (runs, steps, hotfixes, snapshots, audit)
├── runs.lock                # Host-wide execution concurrency lock
├── secrets.enc              # AES-256-GCM encrypted credential vault
├── keys/
│   └── trusted/             # Trusted Ed25519 public keys (*.pub)
├── snapshots/               # Verified pre-modification file snapshots
├── runs/<runId>/            # Temporary execution staging files
└── logs/
    └── jrsctl.log           # Redacted JSON audit and operational log
```

---

## Installation

Download the portable archive for your platform from the releases page. Verify the checksum sidecar before unpacking:

### Windows (x86_64)
```powershell
# Verify SHA-256 checksum
certutil -hashfile jrsctl-<version>-windows-x64.zip SHA256

# Extract package
tar -xf jrsctl-<version>-windows-x64.zip -C C:\Jaspersoft\
```

### Linux (x86_64)
```bash
# Verify SHA-256 checksum
sha256sum -c jrsctl-<version>-linux-x64.tar.gz.sha256

# Extract package
tar -xzf jrsctl-<version>-linux-x64.tar.gz -C /opt/
```

The unpacked archive contains:
* `bin/jrsctl` (Linux) / `bin\jrsctl.cmd` (Windows) — Launcher scripts.
* `lib/jrsctl.jar` — Core application binary with embedded offline documentation.
* `runtime/` — Bundled, isolated Java 21 runtime.
* `MANIFEST.sha256` — Integrity manifest for all packaged files.

---

## Quick Start Guide

### 1. Self-Verification & Environment Detection
```bash
# Verify internal binary and runtime integrity
jrsctl selfcheck

# Auto-detect JRS installation and create config.yaml
jrsctl init --install-dir /opt/jasperreports-server-pro

# Run pre-flight health and environmental checks
jrsctl doctor
```

### 2. Applying a Hotfix
```bash
# Preview the execution plan without making changes
jrsctl hotfix apply /path/to/JRS-8.2.0-HF-0004.zip --plan

# Apply the hotfix with automatic snapshots and service management
jrsctl hotfix apply /path/to/JRS-8.2.0-HF-0004.zip

# List installed hotfixes
jrsctl hotfix list
```

### 3. Rolling Back a Hotfix
```bash
# Restore exact snapshot files and execute rollback SQL
jrsctl hotfix rollback JRS-8.2.0-HF-0004
```

### 4. Repository Export & Import
```bash
# Export specific repository organization to a zip archive
jrsctl export --uri /organizations/acme --out acme-export.zip

# Import content archive with update rules
jrsctl import --file acme-export.zip --update
```

### 5. Launching the Web Console
```bash
# Start local embedded console and open browser with single-use launch token
jrsctl console
```

---

## Command Reference

| Command | Purpose | Mutates Server |
| :--- | :--- | :---: |
| `jrsctl selfcheck` | Verifies runtime, embedded resources, key ring, and state schema | No |
| `jrsctl init` | Detects installation parameters and initializes `config.yaml` | No |
| `jrsctl doctor` | Runs ~20 environmental, compatibility, and permission checks | No |
| `jrsctl smoke` | Functional end-to-end REST probes against running instance | No* |
| `jrsctl hotfix build` | Packages and signs a hotfix bundle from a directory | No |
| `jrsctl hotfix verify` | Validates bundle signature, hashes, and version applicability | No |
| `jrsctl hotfix apply` | Applies hotfix bundle with snapshots, service stop/start, and SQL | **Yes** |
| `jrsctl hotfix rollback` | Reverts installed hotfix files and executes rollback SQL | **Yes** |
| `jrsctl hotfix list` | Displays inventory of installed, superseded, and rolled-back hotfixes | No |
| `jrsctl export` | Exports repository catalogs, organizations, users, or full server (`--strategy rest\|vendor`, `--full-server`) | No |
| `jrsctl import` | Imports repository archive with pre-import subtree snapshot (`--strategy`, `--source-keystore`, `--update`) | **Yes** |
| `jrsctl upgrade` | Orchestrates version upgrade with backup and validation (`--mode newdb\|samedb`, `--reapply-hotfixes`, `--rollback-all`) | **Yes** |
| `jrsctl upgrade rollback` | Restores pre-upgrade file snapshots and configuration (`--to-point B\|C`) | **Yes** |
| `jrsctl customizations` | Registers, unregisters, lists, and diffs local file customizations | No |
| `jrsctl runs list` | Displays execution history and run states | No |
| `jrsctl runs show` | Inspects step-by-step execution details of a specific run | No |
| `jrsctl runs recover` | Resumes or rolls back an interrupted or crashed run (`--resume` or `--rollback`) | **Yes** |
| `jrsctl runs prune` | Cleans up historical snapshots based on retention policies | No** |
| `jrsctl secrets` | Manages encrypted credentials in `secrets.enc` (`init`, `set`, `remove`, `list`) | No |
| `jrsctl keys` | Manages trusted signing public keys (`list`, `add`, `remove`, `generate`) | No |
| `jrsctl console` | Launches local web monitoring dashboard and API | No |
| `jrsctl docs` | Displays the embedded operator guide, recovery runbook, security notes and authoring guide | No |
| `jrsctl help` | Displays synopsis and usage help for any command | No |

*\* `jrsctl smoke --mutating` uploads and executes a transient test report.*\
*\*\* Prunes local `snapshots/` directory; does not mutate JasperReports Server files.*

### Global Flags

Every command supports the following global options:

* `--plan`: Previews the complete execution step tree without applying any mutations.
* `--yes` / `--non-interactive`: Bypasses confirmation prompts (ideal for CI/CD and scripts).
* `--json`: Emits machine-readable JSON output for integrations.
* `--explain`: Prints detailed offline help explaining mutations, rollback behavior, flags, and exit codes.
* `--home <dir>`: Overrides the default `JRSCTL_HOME` path.
* `--set <key>=<value>`: Overrides specific configuration settings dynamically.
* `--passphrase-file <path>`: Specifies file holding passphrase for `secrets.enc`.
* `--no-color`: Disables ANSI terminal colors.

### Standard Exit Codes

| Exit Code | Name / Meaning |
| :---: | :--- |
| `0` | **Success**: Command completed successfully. |
| `1` | **Usage Error**: Invalid arguments or flags. |
| `2` | **Precheck Failure**: Environment check, validation, or doctor failed; no changes made. |
| `3` | **Failure (Rolled Back)**: Operation failed and was safely compensated/rolled back. |
| `4` | **Fatal Failure**: Operation failed and rollback was incomplete; manual intervention required. |
| `5` | **Cancelled**: Operation was cancelled by operator (e.g., Ctrl+C) and compensated. |
| `6` | **Unsupported Server**: Detected JRS version/edition is incompatible with target operation. |
| `7` | **Signature Failure**: Hotfix package signature is invalid or key is untrusted. |
| `8` | **Pending Recovery**: Previous run crashed or was interrupted; requires `jrsctl runs recover`. |
| `9` | **Lock Held**: Another `jrsctl` process is currently executing against this home. |

---

## Security Architecture

* **Signature Verification**: Hotfix bundles must be cryptographically signed with Ed25519 keys. Unsigned or mismatched packages fail closed.
* **Secret Redaction**: Configured passwords, tokens, private keys, and authorization headers are scrubbed before reaching stdout, stderr, log files, JSON output, or `state.db`.
* **Zero Host Token Exposure**: The web console exchanges ephemeral, single-use launch codes (`#launch=...`) over local APIs to prevent token leakage in host process lists.
* **Strict File Permissions**: Key rings, tokens, and secret vaults are created with POSIX `0600` / Windows owner-only ACL permissions.

---

## Building from Source

### Prerequisites
* **Java**: OpenJDK 21 or higher (`JAVA_HOME` targeting JDK 21+).
* **Maven**: Maven 3.9+ (or use the included Maven Wrapper `./mvnw` / `mvnw.cmd` / `scripts/mvn.*`).

### Build Commands
```bash
# Configure Git pre-commit hook (enforces Google Java Format on commit)
git config core.hooksPath .githooks

# Run compilation, unit tests, and code formatting checks
./mvnw test                # or scripts/mvn.sh test / scripts\mvn.cmd test

# Apply Google Java Format styling
./mvnw spotless:apply

# Package shaded executable JAR, verify acceptance tests, and generate JaCoCo reports
./mvnw verify

# Run optional OWASP dependency vulnerability audit
./mvnw verify -Pdependency-check
```

JaCoCo HTML coverage reports are generated during `verify` in `<module>/target/site/jacoco/index.html`.

---

## License

Copyright (c) 2026 Actian Corporation. All rights reserved.
Licensed under the Apache License, Version 2.0.
