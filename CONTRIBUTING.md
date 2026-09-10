**Proposed `CONTRIBUTING.md` for `jrs-ctl`**

Below is a complete, production-ready `CONTRIBUTING.md` tailored specifically to the `jrs-ctl` repository architecture, toolchain, testing strategy, and security standards.

```markdown
### Contributing to `jrs-ctl`

Thank you for your interest in contributing to `jrs-ctl`!

`jrs-ctl` is a dedicated lifecycle management and operations CLI for Actian JasperReports Server (JRS). Because `jrs-ctl` performs mutating server operations (such as hotfixes, upgrades, and migrations), the project places the highest priority on **execution safety**, **step idempotency**, **deterministic rollbacks**, and **credential security**.

This guide outlines our development workflow, coding standards, test requirements, and pull request process.

---

### Table of Contents
- [Code of Conduct](#code-of-conduct)
- [Architecture Overview](#architecture-overview)
- [Prerequisites & Development Setup](#prerequisites--development-setup)
- [Essential Build & Test Commands](#essential-build--test-commands)
- [Engineering Guidelines & Safety Model](#engineering-guidelines--safety-model)
- [Testing Strategy](#testing-strategy)
- [Submitting Pull Requests](#submitting-pull-requests)
- [Reporting Issues & Security Vulnerabilities](#reporting-issues--security-vulnerabilities)

---

### Code of Conduct

We are committed to providing a welcoming, inclusive, and harassment-free environment for all contributors. Please maintain respectful, constructive, and professional communication in all issues, pull requests, and discussions.

---

### Architecture Overview

The codebase is organized as a multi-module Maven reactor:

* `core`: The foundation runtime containing the atomic step execution engine (`Step`, `Runner`), SQLite state journaling (`StateStore`), process execution, cryptographic secrets store, redacting logger, and configuration model.
* `jrs`: JasperReports Server integration layer (REST v2 client, `buildomatic` adapters, XML/JSON parsers, and offline WireMock matrix fixtures).
* `ops`: Higher-level server operations and workflows (diagnostic `doctor`, `hotfix`, `upgrade`, `export`/`import`, `customizations`, and `smoke` tests).
* `app`: CLI entry point, Picocli command tree, console web server (Javalin), and interactive terminal UI.
* `dist`: Self-contained binary distribution packager creating portable `jlink` Java 21 runtimes and launcher scripts.
* `acceptance`: End-to-end integration and acceptance test suites (phases 0 through 8, crash-recovery injection, and packaging verification).

---

### Prerequisites & Development Setup

#### Requirements
* **Java**: OpenJDK 21 or higher (`JAVA_HOME` pointing to a valid JDK 21+).
* **Maven**: Maven 3.9+ (or use the provided `./mvnw` / `mvnw.cmd` wrapper).
* **Git**: Git 2.20+ with support for `core.hooksPath`.

#### Initial Repository Setup
1. Fork and clone the repository:
   ```bash
   git clone https://github.com/<your-username>/jrs-ctl.git
   cd jrs-ctl
   ```

2. Enable the repository pre-commit hooks to automate code formatting validation:
   ```bash
   git config core.hooksPath .githooks
   ```

3. Verify your environment by compiling and running the test suite:
   ```bash
   ./mvnw test
   ```

---

**Essential Build & Test Commands**

We recommend using the official Maven Wrapper (`./mvnw` on Unix/macOS or `mvnw.cmd` on Windows).

**1. Formatting & Code Style**
The project strictly enforces **Google Java Format** via the Spotless Maven plugin.
```bash
# Check formatting across all modules
./mvnw spotless:check

# Automatically format code according to standards
./mvnw spotless:apply
```

**2. Running Unit & Integration Tests**
```bash
# Run unit tests across all reactor modules (in parallel)
./mvnw test

# Run tests for a specific module
./mvnw test -pl core
./mvnw test -pl ops

# Run a single test class
./mvnw test -pl core -Dtest=EncryptedSecretStoreTest
```

**3. Full Verification & Coverage (JaCoCo)**
```bash
# Compile, test, run acceptance tests, and generate JaCoCo coverage reports
./mvnw verify
```
HTML test coverage reports are generated at `<module>/target/site/jacoco/index.html`.

**4. Vulnerability Audit**
```bash
# Run on-demand OWASP dependency vulnerability check
./mvnw verify -Pdependency-check
```

**5. Building the Distribution Archive**
```bash
# Build the portable runtime bundle and standalone distribution zip
./mvnw package -Pdist -pl dist -am -DskipTests
```

---

**Engineering Guidelines & Safety Model**

All contributions must adhere to the following design constraints:

**1. Atomic Step Model & Idempotency**
* Every mutating server action must be modeled as a discrete `Step` implementing both forward `execute(ExecutionContext)` and reverse `compensate(ExecutionContext)` actions.
* Steps **must be idempotent**: executing a step twice in succession must yield the exact same end state without duplicating files, database rows, or operations.
* Steps must never assume an unmanaged side-effect succeeds without registering a compensation step.

**2. Credential Security & Redaction**
* **Zero Plaintext Secrets**: Passwords, tokens, private keys, and authorization headers must never be logged to stdout/stderr or written unredacted to SQLite `state.db`.
* Always pass output messages and diagnostic details through `Redactor.global().redact(message)`.
* Authentication tokens must never appear as CLI arguments or plaintext URL query parameters in host process lists.

**3. Strict Compilation & Static Analysis**
* The build compiles with `-Werror` (warnings treated as errors) and Google ErrorProne static analysis.
* Do not suppress compiler warnings with `@SuppressWarnings` unless strictly necessary and accompanied by an explanatory code comment.

**4. Cross-Platform Compatibility**
* Code must run reliably on Linux, macOS, and Windows.
* Avoid OS-specific shell invocations; use Java's `java.nio.file.Path`, `ProcessBuilder`, or `ProcessRunner` abstractions.
* File permissions on POSIX systems must be managed safely using `OwnerOnlyFiles` without exposing transient insecure file creation windows.

---

**Testing Strategy**

Pull requests must include automated tests corresponding to the scope of changes:

* **Unit Tests**: Place unit tests in the respective module's `src/test/java` directory. Use JUnit 5 and AssertJ assertions.
* **Idempotency Tests**: Any new mutating step added to `ops` or `core` must be covered by a step idempotency test (verifying double execution and clean rollback).
* **REST & JRS Matrix Tests**: Changes interacting with JasperReports Server APIs should use WireMock fixtures under `jrs/src/test/resources/fixtures/` to validate compatibility across all supported JRS versions (`7.1.0` through `10.0.0`) without requiring live Docker instances.
* **Acceptance Gates**: Acceptance tests in `acceptance/` validate end-to-end CLI behavior, recovery mechanics, and docs synchronization. Ensure `./mvnw verify` passes before submitting.

---

**Submitting Pull Requests**

**Pre-flight Checklist**
Before opening a pull request, please verify:
- [ ] Code compiles cleanly with zero warnings: `./mvnw clean test-compile`
- [ ] Code formatting complies with Spotless: `./mvnw spotless:check` (or run `./mvnw spotless:apply`)
- [ ] All unit and integration tests pass: `./mvnw test`
- [ ] Full packaging and acceptance suite passes: `./mvnw verify`
- [ ] New functionality includes corresponding unit/acceptance tests.
- [ ] Public documentation (`README.md`, CLI help texts, or command docs) is updated if CLI syntax or options changed.

**PR Guidelines**
1. **Branch Naming**: Use descriptive branch names (e.g., `feat/upgrade-snapshot-filter`, `fix/redact-db-passwords`, `docs/update-matrix`).
2. **Commit Messages**: Follow conventional, clear commit messages:
   * `feat: add AES-GCM key rotation command`
   * `fix: prevent process table token leakage during console launch`
   * `docs: update compatibility matrix for JRS 10.0`
3. **Draft PRs**: Feel free to open a Draft PR early if you would like feedback on design or architecture.

---

**Reporting Issues & Security Vulnerabilities**

**Bug Reports & Feature Requests**
* Search existing GitHub issues to avoid duplicate reports.
* When filing a bug report, please include:
  * Operating system and Java version (`java -version`).
  * `jrsctl` version (`jrsctl --version`).
  * Target JasperReports Server version and edition.
  * Relevant redacting logs (`~/.jrsctl/logs/` or console output).
  * Clear reproduction steps.

**Security Vulnerability Disclosures**
If you discover a security vulnerability or credential exposure risk, please **do not open a public issue**. Instead, submit a private advisory through GitHub Security Advisories or contact the maintainers directly.
```
