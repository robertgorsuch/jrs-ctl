# ADR-0019: The vendor tools do not inherit jrsctl's secrets

Status: accepted, 2026-09-15. Amends spec §11 (security). Issue #47.

## Context

`VendorTools.run` starts `js-export`, `js-import` and `js-ant` with `JAVA_HOME`, `PATH` and
`JAVA_OPTS` set on top of jrsctl's own environment, and `DefaultProcessRunner` added them to the
inherited environment. The vendor scripts, the Ant build they launch and every child process of it
therefore saw everything jrsctl had: `JRSCTL_PASSPHRASE` (which unlocks `secrets.enc`) and every
variable a configured `env:` secret reference reads, `JRS_PASSWORD` and `JRS_DB_PASSWORD` by
default. None of these tools needs them, and anything they log or pass on can leak them.

## Decision

1. `ProcessRunner.Request` gains `unset`, a set of variable names the child must not inherit,
   compared without regard to case. A name also given explicitly in `environment` is set to that
   value. The previous four-argument constructor remains and means "inherit everything".
2. `VendorTools` withholds every inherited `JRSCTL_*` variable and every variable named by an
   `env:` reference in the configuration (`Config.envSecretNames()`, built from the one list of
   secret references `Config.secretRefs()` that `Bootstrap` also registers with the redactor).
3. Where no configuration is in scope (`Strategies`), only `JRSCTL_*` is withheld.

## Alternatives

- **An allowlist** (`PATH`, `JAVA_HOME`, `SystemRoot`, `TEMP` and so on). Rejected: buildomatic and
  the JDBC drivers read host-specific variables jrsctl cannot predict (`ANT_OPTS`, proxy settings,
  Oracle and DB2 client variables), and a missing one breaks an export or an upgrade half-way.
- **Withhold nothing and rely on redaction.** Rejected: the redactor filters what jrsctl prints,
  not what a child process does with its environment.

## Consequences

- A vendor tool that genuinely reads a `JRSCTL_*` variable, or a configured `env:` variable, no
  longer sees it. None of the 10.0.0 buildomatic scripts does.
- Secrets referenced as `file:` or `enc:` were never in the environment and are unaffected.
- A secret held in an environment variable that the configuration does not reference is still
  inherited; the operator guide recommends `enc:` references for that reason.
