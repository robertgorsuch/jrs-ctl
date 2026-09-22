# ADR-0037: JLine 3 for `Prompter.path`, scoped to line editing and path completion

Status: accepted · Date: 2026-09-22 · Spec: §13.3 · Issue #102 · Builds on ADR-0023 (Option B)

## Context

Field test 2, findings G2 and G4 (`docs/reviews/2026-09-18-field-test-2-review.md`): typing a path in the
guided menu offers no tab completion, and the cursor keys do not move within the line. `Prompter.line`
reads through `System.console().readLine` (or a plain `BufferedReader` over stdin when there is no
console), so editing is whatever the terminal's own canonical-mode driver provides — nothing on a
`cmd.exe` console, and no completion anywhere. Issue #102 is a 1.6.0 tester asking again, which
ADR-0023 named as the trigger for the option B spike: prove a JLine 3 console provider that works
inside the jlink image on Windows without JNA (spec §13.3 does not approve JNA), then write this ADR.
This decision covers only that spike and its narrow scope (`Prompter.path`, i.e. the free-text path
prompts a `GuidedMode` session asks); it does not reopen ADR-0023's Option A recommendation for a
full-screen dashboard, which stays with issue #75.

## Spike findings

Built a throwaway Maven project (`jline-terminal` + `jline-terminal-jni` + `jline-reader` 3.30.4) and
ran it against this machine's JDK 21 (Microsoft build), the same one `scripts\mvn.cmd` selects.

- **No JNA.** `jline-terminal-jni` pulls in `jline-native`, which bundles precompiled native libraries
  for Windows (`x86`, `x86_64`, `arm64`) and Linux (`x86`, `x86_64`, `arm`, `arm64`, `ppc64`) plus
  FreeBSD, loaded with plain `System.load` after extraction to a temp directory. No JNA jar is pulled
  in transitively; `TerminalBuilder.builder().provider("jni")` finds the provider through a classpath
  scan of `META-INF/services/org/jline/terminal/provider/jni`, confirmed present in a shaded jar built
  the same way `app/pom.xml`'s `maven-shade-plugin` execution builds `jrsctl.jar` (default merge, no
  extra shade configuration needed; `ServicesResourceTransformer` is already configured for the
  standard flat service files and does not interfere with this nested one).
- **Licence.** All four artifacts (`jline-terminal`, `jline-terminal-jni`, `jline-native`,
  `jline-reader`) declare `The BSD License` / BSD-3-Clause in their POMs, confirmed compatible with
  GPL-3.0-only (ADR-0010): permissive, no added restriction. (One text resource inside the jar —
  `META-INF/services/org/jline/terminal/provider/jni` — carries a stale Apache-2.0 header comment left
  over from the file's own history; the project's own POM licence, which governs, is BSD-3-Clause.)
- **jlink module list (ADR-0008).** `jdeps --multi-release 21 --print-module-deps` against a shaded
  jar containing only `jline-terminal`, `jline-terminal-jni`, `jline-native` and `jline-reader` reports
  `java.base, java.logging` — both already in ADR-0008's final 14-module list. ADR-0008's module list is
  derived from `jdeps` at build time, so no manual amendment to that ADR or to `dist/pom.xml` is needed;
  a real `RuntimeModules` run against `jrsctl.jar` with the new dependency will pick this up the same
  way it picks up every other dependency.
- **Native-access warnings.** JDK 21 does not gate `System.load`/`System.loadLibrary` behind
  `--enable-native-access` (that restriction is a Foreign Function & Memory API and later-JDK JNI
  concern, JEP 442/472); no warning appeared on stderr in the spike. This is a forward-compatibility
  item, not a blocker: if jrsctl moves off JDK 21 to a version that warns or refuses plain JNI native
  loading, `JRSCTL_JAVA_OPTS`/the launcher scripts will need `--enable-native-access=ALL-UNNAMED` (the
  shaded jar has no module name of its own). Recorded here so the next JDK bump checks it.
- **Interactive proof — not completed by this ADR.** `System.console()` is `null` in every automation
  environment available while writing this ADR (the CI runners and this agent's own tool sandbox all
  run with redirected/piped stdio, so `TerminalBuilder.builder().system(true)` cannot exercise the real
  Windows/Linux console path at all — the same reason `Prompter`'s existing `console()` gate is already
  untested in automation and relies on `Prompter.override` in every test). ADR-0023's checklist item
  "prove it in cmd.exe, PowerShell and Windows Terminal, and over SSH on Linux" therefore still needs a
  human at a real terminal, once, before this is called done end to end; this is a permanent property of
  the check, not a gap to close later with more automation. Tracked as a manual verification step in the
  release checklist rather than a test.

## Decision

Adopt JLine 3 (`jline-terminal`, `jline-terminal-jni`, `jline-native`, `jline-reader`, version managed
at `${jline.version}` in the root POM) for `Prompter.path` only:

- A new `Prompter.path(PrintWriter, String)` builds a JLine `Terminal`/`LineReader` (provider forced to
  `jni`, `jna(false)`, `jansi(false)`, `ffm(false)`) with a `PathCompleter` (filesystem prefix
  completion) the first time it is called from a real interactive session (no `Prompter.override`, a
  present `System.console()`); the terminal is built once per process and closed on a JVM shutdown hook
  so raw mode is always restored. Any failure to build it (`Throwable`, since a missing native library
  surfaces as `UnsatisfiedLinkError`) permanently falls back to today's `Prompter.line` behaviour for
  the rest of the process — the feature degrades to exactly what 1.6.0 shipped, never fails a prompt.
- `Prompter.line`, `Prompter.secret` and every other prompt are unchanged; only the path-taking prompts
  in `GuidedMode` (`existingFile`, `existingDirectory`, `optionalExistingFile`,
  `optionalExistingDirectory`, the installation-directory prompt, and the export "backup file to write"
  prompt) move to `Prompter.path`. No full-screen UI, no change to any other command's prompts.
- `--ascii` and the redaction rules are unaffected: `Prompter.path` never echoes anything the caller did
  not already pass as the prompt string, and `PathCompleter` only lists filesystem entries, never
  secrets.
- `Prompter.override`/`reset` (the test seam) is checked first and unconditionally short-circuits to the
  existing buffered-reader behaviour, so no existing test changes and no new dependency is exercised
  under `scripts\fast.cmd`.
- The documented fallback from ADR-0023/issue #102 (`rlwrap jrsctl` on Linux; running the guided menu in
  Windows Terminal or PowerShell, whose own line input already handles cursor keys) is kept in the
  operator guide as what an operator gets today, unconditionally, with no jrsctl-side dependency.

## Consequences

- A new runtime dependency ships in `jrsctl.jar` and the portable image; spec §13.3 amended.
- `docs/BUILD_STATUS.md` records this as the fix for field test 2 G2/G4 (issue #102) and notes the
  human-verification requirement above, so a release does not claim more than automation has proven.
- Issue #75 (full-screen terminal UI) and ADR-0023's Option A recommendation are unchanged; this ADR
  narrows and closes only the line-editing/path-completion half of the option B question.
- Before the next release ships this, a maintainer runs the guided menu's install-directory or hotfix
  package prompt in `cmd.exe`, PowerShell, Windows Terminal, and over SSH on the Linux laptop
  (`docs/BUILD_STATUS.md`'s Linux laptop test gate), confirming tab completion offers real filesystem
  entries and the arrow keys move within the line, and records the result here or in BUILD_STATUS.

## Amendment (2026-09-22): CVE-2026-56740/56741

`${jline.version}` moved from `3.30.4` (the spike version, above) to `3.30.17`, the latest patch on
the same minor line, before this PR's CI ran the dependency audit. The OWASP gate flagged
`jline-native-3.30.4.jar` against `cpe:2.3:a:jline:jline:3.30.4` for both CVEs (CVSS 7.5 each): an
unauthenticated heap-exhaustion and CPU-exhaustion pair in JLine's **Telnet server**
(`jline-remote-telnet`'s `NEW-ENVIRON`/`NAWS` option handling), fixed upstream in 3.30.14. jrsctl
depends on `jline-terminal`, `jline-terminal-jni`, `jline-native` and `jline-reader` only — the
telnet server module is never on the classpath, so the CPE match is a false positive by name
(NVD's CPE dictionary has no separate entry for `jline-native`, only the umbrella `jline:jline`
product, so dependency-check's version-range match catches every JLine artifact at 3.30.4
regardless of module). The clean fix is still the version bump rather than a suppression: it is a
same-minor-line patch release with no expected breaking change, and it removes the flag entirely
instead of asserting the finding is safe to ignore.
