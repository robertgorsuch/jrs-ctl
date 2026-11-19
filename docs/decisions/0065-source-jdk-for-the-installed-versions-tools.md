# ADR-0065: `vendor.sourceJavaHome` runs the tools of the version an upgrade starts from

Status: accepted · Date: 2026-11-18 · Spec: §5.1, §7.4, §10.1 · Issue #338

## Context

One key, `vendor.javaHome`, has been the JDK for every buildomatic run. Preflight requires it to be a JDK the **target** supports (`verify-target-package`). An upgrade also runs the **installed** version's tools, though:

| Step | Tools it runs |
|---|---|
| `full-export` (point B, newdb and samedb) | the installed buildomatic's `js-export` |
| `upgrade rollback --restore-database`: `rebuild-database`, `reimport-full-export` | the restored (old) buildomatic's `js-ant` and `js-import` |

On 8.x → 10.x no single JDK serves both: 8.x supports Java 8 or 11, and 10.x supports Java 17 (and 21 from 10.1). The live 8.0.4 → 10.0.0 run of 2026-11-18 failed at `full-export`. The 8.0.4 `js-export` on Java 17 threw `Unsupported class file major version 61` from its bundled Groovy, and still exited 0. jrsctl caught it and rolled back.

doctor had already shown the conflict, as a `vendor-java` FAIL ("Java 17; JRS 8.0.4 needs Java 8 or 11") that every 8.x → 10 operator sees and has to ignore.

The workaround (an export taken by hand with Java 11, passed with `--export`) worked. It moves a step out of jrsctl's journal and its point-B guarantees, though.

## Decision

- **New optional key `vendor.sourceJavaHome`:** the JDK for the tools of the version an upgrade starts from. Set it with `jrsctl config set vendor.sourceJavaHome <dir>`. It is a directory key, and is in the schema, the writer and `config set`. Left unset, every step keeps using `vendor.javaHome`, as before; the only difference is that an upgrade whose `vendor.javaHome` the installed version cannot run is now refused in preflight instead of failing at `full-export` (amended by #348, below).
- **Which JDK each step uses:**
  - `full-export`, `rebuild-database` and `reimport-full-export` use `vendor.sourceJavaHome` when set, else `vendor.javaHome`.
  - Every step that runs the target package's tools still uses `vendor.javaHome`: `rehearse`, `run-vendor-upgrade`, `import-events`, the password migration and `repository load`.
- **Preflight refuses early:** when the plan takes its own full export (no `--export`), `verify-target-package` checks the JDK the export will run with against the installed version's Java line in the compatibility matrix. A mismatch refuses before anything is stopped or written, naming both ways out: set `vendor.sourceJavaHome` to a JDK that version supports, or take the export yourself and pass `--export`.
- **doctor's `vendor-java`:** it does not fail when either key names a JDK the installed version supports, and the detail says which key that is and what the other is for (a WARN when only `sourceJavaHome` fits, amended by #348, below). Before the upgrade, `sourceJavaHome` (11) fits 8.0.4. After it, `javaHome` (17) fits 10.0. The item fails only when neither fits, and the remediation then mentions `vendor.sourceJavaHome`.
- **After a successful upgrade the key stays.** `upgrade rollback --restore-database` needs the old version's JDK. Remove it once the rollback window has closed.

## Not decided here

- **Other commands:** `export`, `import` and `hotfix` keep running the installed buildomatic with `vendor.javaHome`. Before an upgrade, an operator who has already set `vendor.javaHome` to the target's JDK has to keep it on the installed version's JDK for those commands. Choosing the JDK by the installed version everywhere is a wider change.
- **Picking a JDK automatically:** jrsctl does not search the host for a suitable JDK (for example `C:\Program Files\Amazon Corretto`). The operator names it.

## Consequences

- An 8.x → 10.x upgrade no longer needs the hand-taken export: `full-export` runs on the source JDK inside the run, with its point-B record, timing and rollback as for every other step.
- doctor stops reporting a FAIL that every 8.x → 10 operator has to ignore.
- `upgrade rollback --restore-database` after an 8.x → 10.x run uses the old version's JDK. Previously it would most likely have hit the same Groovy failure; that path wasn't tested live.
- Tests against the fakes:
  - preflight refusal and pass
  - the JDK `full-export` and the rollback steps receive
  - doctor with each key
  - config round trip

  Not verified live.

## Amendment (issue #348, 2026-11-19)

A review of the merged change found gaps, fixed together:

- **Rehearsals are not judged.** `upgrade --test` takes no point-B export, so `verify-target-package` skips the source-JDK check in a rehearsal (and, as before, when `--export` is given).
- **The remedy fits the mode.** The refusal offers `--export <file>` only in newdb mode; samedb rejects `--export` as a usage error.
- **A database rollback judges the JDK too.** `upgrade rollback --restore-database` refuses, when the rollback is planned and before the server is stopped, a `sourceJavaHome` (else `javaHome`) that the version the upgrade started from does not support. `rebuild-database` and `reimport-full-export` check again in their prechecks, before the drop. The version comes from the run's manifest (`from`) or, for a run that failed before writing it, from a `from <version>` line the vendor-started marker now carries. A marker written by 2.8 or earlier has no such line, and then only a configured JDK is required, as before.
- **doctor is honest about the other commands.** When only `sourceJavaHome` fits the installed version, `vendor-java` is a WARN, not a PASS: vendor-strategy `export`/`import` and `passwords` still run the installed tools on `vendor.javaHome`. A `sourceJavaHome` that is set but broken, or does not fit, is a WARN even when `javaHome` fits, and is judged when `javaHome` is unset.
- **One check.** Preflight's target and source checks and the rollback's share one probe-and-judge helper; a JDK named by both keys is probed once.

Still not decided here: choosing the JDK from the version of the buildomatic about to run, for every command.

