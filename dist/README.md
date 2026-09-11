# dist — portable distribution (Phase 7, ADR-0003, ADR-0008)

Builds the self-contained jrsctl image for the current platform and packs it as a portable archive.
Nothing here runs in the default build; activate it with `-Pdist`:

```
scripts\build-dist.cmd            (= scripts\mvn.cmd -Pdist -DskipTests package)
scripts/mvn.sh -pl dist -am -Pdist package
scripts/mvn.sh verify -Dphase=7 -Pdist     acceptance (Phase7DistributionTest)
```

## Outputs (`dist/target/`)

| Path | What |
|---|---|
| `image/<platform>/` | the unpacked image: `bin/jrsctl.cmd`, `bin/jrsctl`, `lib/jrsctl.jar`, `runtime/` (jlink), `README.txt`, `LICENSE`, `LICENSE-THIRD-PARTY.txt`, `MANIFEST.sha256` |
| `jrsctl-<version>-windows-x64.zip` / `jrsctl-<version>-linux-x64.tar.gz` | the image under one top-level directory `jrsctl-<version>/`; exec bits set in the tar |
| `<archive>.sha256` | `sha256sum -c` compatible line: `<hex>  <archive file name>` |
| `jrsctl-<version>-sbom.json` | CycloneDX 1.5 SBOM of the runtime dependency tree of `jrsctl-app` |
| `jlink-modules.txt` | the jlink `--add-modules` argument file actually used (jdeps result ∪ fixed list − exclusions) |
| `*.sig` | Ed25519 detached signatures, only when `-Psign` ran with `JRSCTL_SIGNING_KEY` (CI) |

`<platform>` is `windows-x64` when Maven runs on Windows and `linux-x64` otherwise (ADR-0002); the
same pom builds both, one per runner.

## Pipeline (`dist/pom.xml`, profile `dist`)

1. `compile` — the three helpers in `src/main/java` (`RuntimeModules`, `ImageLayout`, `ImageManifest`)
   compile under the same Error Prone / `-Werror` settings as product code. They are build tools and
   never ship.
2. `prepare-package` — `maven-resources-plugin` copies `src/image/**` (filtered: version, platform,
   dependency versions) and `app/target/jrsctl.jar` into the image dir; `RuntimeModules` runs `jdeps`
   on a copy of the shaded jar with every `module-info.class` removed (the shaded jar carries a stray
   one from a library, which makes jdeps treat it as a module and fail), unions the result with the
   fixed list, drops the exclusions, writes `target/jlink-modules.txt` and removes a stale `runtime/`;
   `jlink` builds `runtime/` with `--strip-debug --no-header-files --no-man-pages --compress zip-6`;
   `ImageLayout` checks the required files, normalises line endings of the launchers and sets exec
   bits on POSIX filesystems; `ImageManifest manifest` writes `MANIFEST.sha256`.
3. `package` — `maven-assembly-plugin` (`src/assembly/portable.xml`) builds the archive;
   `cyclonedx-maven-plugin` writes the SBOM; `ImageManifest checksum` writes `<archive>.sha256`;
   with `-Psign`, `scripts/sign-artifacts.cmd|.sh` runs last.

## `MANIFEST.sha256` format (for `selfcheck`, spec §12.3, Phase 8)

Written by `ImageManifest`; verified by `Phase7DistributionTest` today and by `jrsctl selfcheck`
from Phase 8 on.

- One line per regular file in the image directory tree, **excluding `MANIFEST.sha256` itself**.
- Line = `<sha256 lower-case hex, 64 chars>` + two ASCII spaces + `<path>` + `\n` (LF only, UTF-8, no BOM).
- `<path>` is relative to the image root (the directory holding `README.txt`), uses `/` as separator
  on every platform and has no leading `./`.
- Lines are sorted by `<path>` (plain `String` order of the slashed path).
- Hashes are computed over the raw bytes; no line-ending normalisation. The launchers are written
  with fixed endings (`jrsctl` LF, `jrsctl.cmd` CRLF) before hashing, so the hashes are stable.
- A verifier must (a) hash every listed file and compare, (b) report listed-but-missing files, and
  (c) report files present in the tree but not listed (an unlisted file is a tampering signal, exactly
  like the unlisted-file rule for hotfix bundles). `sha256sum -c MANIFEST.sha256` covers (a) and (b).
- The archive sidecar `<archive>.sha256` uses the same line format with the bare archive file name.

## Signing (spec §0 rule 11, §11.1)

`scripts/sign-artifacts.*` run `scripts/SignArtifacts.java` (JDK single-file launch, no build). With
`JRSCTL_SIGNING_KEY` set to the Base64 PKCS#8 Ed25519 private key it writes `<artifact>.sig` (Base64
detached signature over the file bytes, the encoding hotfix bundles use) for every archive, checksum
and the SBOM; without the variable it prints `signing skipped: no key (CI only)` and exits 0. Only the
`release` CI job has the secret; local artifacts are unsigned.
