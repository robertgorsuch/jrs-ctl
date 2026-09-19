# ADR-0035: startup cost: what was measured, what was fixed, what was left alone

Date: 2026-09-19. Status: accepted. Follows the CI review of the same day (acceptance is the largest
share of each build leg).

## Context

The acceptance suite starts the shaded jar as a child process for nearly every assertion, and the
working assumption was that JVM startup (about 850 ms a launch on the maintainer's machine)
dominated it, so a class-data-sharing (CDS) archive and C1-only JIT flags would speed it up. Before
building anything the assumption was measured.

## Measurements (2026-09-19, Windows 11, JDK 21.0.9, 16 cores, nothing else running)

- **Launch cost.** `jrsctl --version` 847 ms; with a dynamic CDS archive 518 ms; with the archive and
  `-XX:TieredStopAtLevel=1` 415 ms. `--help`, `selfcheck` and `config keys` behave alike (about -40%
  with the archive). Serial GC adds nothing.
- **How many launches.** The whole acceptance suite makes **141** (Phase8CrashRecovery 42, Phase5 21,
  Phase8JsonSchema 20, Phase8Retention 18, Phase3 16, Phase8ExplainDocs 12, Phase4 5, Phase2 3,
  Phase0 3, Phase6 1). At 850 ms that is two minutes of startup across four forks; a 40% cut is 10 to
  20 seconds of a 2.5 to 5 minute module.
- **The archive in the suite.** Four local runs with four forks: 2:47 without the archive, 2:58 and
  2:51 with it, 2:27 with it plus C1-only. Nothing measurable except the C1 run, which is not
  shippable (below). The archive machinery was written, verified race-free with a file lock, and then
  **not adopted**: about 130 lines of test infrastructure for a saving below the noise.
- **Where the time is.** A sequential run timing every launch (`Cli.run`, 135 calls, 240 s of a 314 s
  module) shows command latency, not startup: `export --strategy vendor --stop-service` 16.5 s,
  `upgrade` 14.7 s and 5.9 s, `upgrade rollback --restore-database` 14.4 s, `runs recover` 8.8 s and
  8.0 s, `doctor --json` 6.5 s, 4.5 s, 6.3 s and 3.9 s, `hotfix apply` about 2 s each, `import` and
  `export` about 2 s.

## Decision

1. **Fixed: `doctor`'s Windows elevation probe.** Thread samples of a running `doctor` showed four of
   its 5.5 seconds inside `LocalChecks.windowsElevated`, waiting on `whoami /groups`, which resolves
   every group of the user against the domain: 2.7 to 4.2 s on a domain-joined machine (the usual
   setting of a customer's Windows server) against 80 ms for `fltmc`, which answers exit code 0 to an
   elevated caller and "access denied" otherwise. The probe is now `fltmc`; a probe that cannot run,
   times out or fails counts as not elevated, as before. `doctor` takes 1.4 to 2.0 s (was 5.5 s) with
   the same `elevated` answer for a non-elevated shell. The elevated answer was not observed on this
   machine (no elevated shell); it relies on the documented `fltmc` behaviour and the unit test.
2. **Not adopted: a CDS archive in the shipped launchers.** The gain is about 300 ms a launch, and the
   cost is real: the archive is validated against the jar's size and modification time, which a
   re-extracted ZIP or a replaced jar changes, and a stale or truncated archive makes the JVM print
   `[warning][cds]` lines on **standard output** (reproduced: they precede the tool's output and would
   break `--json`). Writing it means a file outside the jrsctl home in a location that the user
   running jrsctl can write and a privileged run then loads. `-Xlog:disable` hides the warnings but
   that is the launcher's job to get right on three platforms; the saving does not pay for it.
3. **Forbidden: `-XX:TieredStopAtLevel=1` for jrsctl.** Measured on this JDK, SHA-256 of 600 MB took
   4.5 s with it and 0.55 s without (deflate is unaffected: 5.0 s against 4.8 s for 150 MB). The tool
   hashes whole archives, so C1-only would make large exports and imports slower by minutes. The
   operator guide says so next to `JRSCTL_JAVA_OPTS`.
4. **Left for a later change, with the evidence above:** the waits inside `upgrade`, `rollback`,
   `runs recover` and the vendor-strategy export (service stop and start polling at one second, the
   fixtures' fake scripts, health polling); the suite's CPU-bound sum on a 4 vCPU runner is dominated
   by these, not by launches.

## Consequences

- No new test infrastructure and no change to a launcher.
- Windows `doctor` on a domain-joined machine is about 3.5 seconds faster; the acceptance suite's four
  to five `doctor` calls gain the same on such a machine and little on a CI runner that is not in a
  domain.
- Anyone tempted to speed jrsctl up with JVM flags has the numbers and the reasons.
