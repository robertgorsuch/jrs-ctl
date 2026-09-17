# ADR-0022: config.yaml stays the default; jrsctl.properties is an alternative

Status: accepted, 2026-09-16. Amends spec §5.1 (Draft 1.1). Issue #74.

## Context

jrsctl's configuration is `config.yaml` in the jrsctl home. The first field test, by a JasperReports Server support engineer, found that YAML "goes against the established practice in the JasperServer maintenance ecosystem, where we previously relied on properties files" (`default_master.properties`, `jasperserver.properties`). It is one more format for an administrator to learn.

#73 already removed the part of the file that duplicated buildomatic: the database settings are read from `default_master.properties`. What is left is jrsctl's own settings. Every one of them is a dotted key, the same key `--set` and `jrsctl config set` take.

## Decision

1. `config.yaml` remains the default: `jrsctl init` writes it, and every document and example uses it.
2. `jrsctl.properties` in the jrsctl home is accepted as an alternative, holding the same dotted keys as `key=value` lines, validated against the same schema:
   - **Values are literal**, as buildomatic's properties are read: a backslash is not an escape, so a Windows path is written as it is.
   - `#` and `!` start comments; `=`, `:` or whitespace separates key and value.
   - A list (`network.proxy.noProxy`) is comma-separated. The same rule applies to a list given through `--set`, `config set` or a `JRSCTL_*` variable.
   - An unknown or repeated key is refused with its line number.
   - This is deliberately not `java.util.Properties`, whose escape rules would silently corrupt `C:\Jaspersoft` into `C:Jaspersoft`.
3. Only one file may exist. When both are in the home, every command refuses with exit 2 and names both, instead of guessing.
4. `jrsctl init --format properties` writes `jrsctl.properties`. `init` refuses to write one format while the other exists, even with `--force`, since that would leave both.
5. `jrsctl config set` and `unset` rewrite the file in its own format, keeping the previous one as `<file>.bak`. The upgrade's `point-config-at-target` step and `upgrade rollback` save and restore whichever file is in use. `jrsctl config show --format properties` prints the effective configuration as properties lines.

## Consequences

- Administrators who prefer properties files can use one without learning YAML, and the documentation keeps a single default.
- A value cannot span lines or contain a newline. No jrsctl setting needs one.
- The console's configuration view shows the file as it is, in either format.
