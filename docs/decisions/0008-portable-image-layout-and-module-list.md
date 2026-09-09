# ADR-0008: Portable image layout and jlink module list

Status: accepted · Date: 2026-09-09 · Spec: §1.2, §12.3, §14 Phase 7, §16; builds on ADR-0001..0003

## Context

Phase 7 ships jrsctl as a portable archive with a bundled jlink runtime, so an operator needs no JDK on the JasperReports Server host. The spec fixes the deliverables (image, ZIP/tar.gz for x86_64, SBOM, SHA-256, CI-only signing) but not the directory layout, the set of JDK modules, the integrity format that `selfcheck` (§12.3) will verify in Phase 8, or which build plugins may do the work. The shaded jar is the unit every other phase tests; the image must wrap it unchanged.

## Decision

### Layout (`dist/target/image/<platform>/`, archived under one directory `jrsctl-<version>/`)

```
bin/jrsctl.cmd            "%~dp0..\runtime\bin\java.exe" %JRSCTL_JAVA_OPTS% -jar "%~dp0..\lib\jrsctl.jar" %*  (exit code propagated)
bin/jrsctl                POSIX sh, resolves symlinks, exec "$home/runtime/bin/java" ... "$@"   (0755 in the tar)
lib/jrsctl.jar            the shaded jar from app/target, byte-identical
runtime/                  jlink output: --strip-debug --no-header-files --no-man-pages --compress zip-6
README.txt                what it is, layout, quick start, checksum verification, "Actian Jaspersoft"
LICENSE-THIRD-PARTY.txt   the §13.3 runtime dependencies with versions from the root pom, plus OpenJDK GPLv2+CE
MANIFEST.sha256           one line per file except itself: <sha256 hex>  <slashed relative path>, sorted, LF
```

`<platform>` is `windows-x64` or `linux-x64` (ADR-0002), chosen by an OS-activated profile so one pom builds each platform on its own runner. The archive is `jrsctl-<version>-<platform>.zip` (Windows) or `.tar.gz` (Linux, exec bits on `bin/jrsctl`, `runtime/bin/*`, `runtime/lib/jspawnhelper`), with `<archive>.sha256` in `sha256sum -c` format next to it, and `jrsctl-<version>-sbom.json` (CycloneDX 1.5). The manifest format is specified in `dist/README.md`; Phase 8's `selfcheck` verifies it (hash mismatch, missing file, and unlisted file are all findings, mirroring the hotfix bundle rule).

### Module list

`RuntimeModules` runs `jdeps --multi-release 21 --ignore-missing-deps --print-module-deps` on a copy of the shaded jar with every `module-info.class` removed (the jar carries a stray `META-INF/versions/9/module-info.class` from a library; with it, jdeps treats the jar as that module and fails to resolve its `requires`), unions the result with a fixed list, drops the exclusions and writes a jlink argument file (`dist/target/jlink-modules.txt`).

- jdeps reports: `java.base, java.desktop, java.instrument, java.management, java.naming, java.net.http, java.security.jgss, java.sql, jdk.unsupported`.
- Fixed list (things jdeps cannot see: reflection, JDBC/SASL/charset service loading, `jar:` file systems, TLS curves): `java.base, java.sql, java.net.http, java.naming, java.logging, java.xml, java.management, java.security.sasl, java.security.jgss, java.instrument, jdk.crypto.ec, jdk.unsupported, jdk.zipfs, jdk.charsets`.
- Excluded: `java.desktop`. jdeps lists it only because of optional, guarded references (Jackson `Java7Support` → `java.beans`, SnakeYAML's bean introspector, commons-compress pack200, Jetty's AWT leak preventer, commons-lang3 concurrent), none on jrsctl's code paths. A smoke run of a runtime built without it, from `cmd.exe` with `JAVA_HOME` empty and `PATH=%SystemRoot%\System32;%SystemRoot%`, passed `--version`, `selfcheck`, `selfcheck --json`, `config show` (YAML parsed), `keys list`, `hotfix list`, `secrets list`, `runs list --json` (SQLite native library loaded), `doctor` and `smoke` (java.net.http, Jackson JSON), `hotfix verify` and `export --plan`. Dropping it saves 12 MB (runtime 49.5 MB → 37.6 MB on Windows).

**Final list (14 modules):** `java.base, java.instrument, java.logging, java.management, java.naming, java.net.http, java.security.jgss, java.security.sasl, java.sql, java.xml, jdk.charsets, jdk.crypto.ec, jdk.unsupported, jdk.zipfs` (plus whatever a future jdeps run adds; a new exclusion needs the same smoke evidence recorded here). Sizes on Windows (JDK 21.0.9, Microsoft build): runtime 37.6 MB, image 64.5 MB (154 files), ZIP 50.7 MB. `jdk.localedata` is deliberately absent (English-only formatting; JRS hosts are administered in English and the tool's output is machine-parsed); `jdk.crypto.cryptoki` and `java.rmi` are absent (no PKCS#11, no JMX remoting).

### Build plugins (added to `dist/pom.xml` only, no runtime dependencies)

| Plugin | Version | Why |
|---|---|---|
| `maven-compiler-plugin` (execution bound in the `dist` profile) | managed (3.13.0) | compiles the three helpers `RuntimeModules`, `ImageLayout`, `ImageManifest` under Error Prone / `-Werror` like product code |
| `maven-resources-plugin` | managed (3.3.1) | copies `src/image/**` with version/platform filtering and the shaded jar into the image |
| `exec-maven-plugin` | managed (3.3.0) | runs the helpers and `jlink` from `${java.home}`; runs the sign script in the `sign` profile |
| `maven-assembly-plugin` | 3.7.1 | zip / tar.gz with POSIX file modes (`src/assembly/portable.xml`) |
| `cyclonedx-maven-plugin` | 2.8.0 | `makeBom`, JSON, CycloneDX 1.5 |

Rejected: antrun (XML-scripted logic is harder to test than 150 lines of Java); doing jlink through a jlink Maven plugin (they assume modular jars; the shaded jar is class-path code by design, ADR-0001); computing the module union in Maven properties (no string operations without yet another plugin).

### Signing

`scripts/sign-artifacts.cmd|.sh` run `scripts/SignArtifacts.java` (JDK single-file launch). With `JRSCTL_SIGNING_KEY` (Base64 PKCS#8 Ed25519 private key, the `jrsctl keys generate` format) they write `<artifact>.sig` = Base64 Ed25519 detached signature, the encoding hotfix bundles already use (§11.1, JDK provider, no BouncyCastle), for every archive, `.sha256` and the SBOM. Without the key they print `signing skipped: no key (CI only)` and exit 0. Only the `release` CI job holds the secret; local artifacts are unsigned (spec §0 rule 11).

## Consequences

- Operators unpack one archive and run `bin\jrsctl.cmd` / `bin/jrsctl`; nothing else to install, no `JAVA_HOME`, no PATH changes. `JRSCTL_JAVA_OPTS` is the only knob into the bundled JVM.
- The image is verifiable offline in three layers: archive checksum (`.sha256`), per-file manifest (`MANIFEST.sha256`, later `selfcheck`), and signatures on release artifacts.
- The module list is derived at build time, so a new dependency that needs another JDK module is picked up by jdeps automatically; exclusions are explicit and must be re-justified with a smoke run.
- Absent `jdk.localedata`, non-English locale formatting falls back to the root locale; revisit if a customer needs localized number/date output.
- The default build is unchanged (`dist` does nothing without `-Pdist`), so every other phase's turnaround is unaffected; CI builds the image in a separate `package` job per OS after `build`, and the acceptance suite skips itself when the image is absent.
- The `jrsctl.jar` inside the image is the same artifact acceptance tests exercise; the image adds no code paths of its own beyond the launcher scripts.
