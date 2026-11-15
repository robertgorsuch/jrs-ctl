# ADR-0058: report commands gate a CI pipeline with --fail-on and --junit

Status: accepted · Date: 2026-11-15 · Spec: §12, §18 · Issue #308

## Context

A customer builds JasperReports Server as a container image in CI (November 2026) and asked how to gate a built image with jrsctl. The commands exist: `customizations scan --installed` needs no configuration, and `doctor` and `smoke` run from a remote configuration (`init --remote`), where the container refusal of ADR-0043 does not apply because the `deployment` item is skipped. Two things were missing: `doctor` exits 0 on warnings, so a pipeline cannot fail on them, and a pipeline's test view cannot read the text or JSON report.

## Decision

- `doctor`, `smoke` and `import verify` take **`--fail-on warn|fail`** (default `fail`). With `warn`, a report that would exit 0 exits **2** when any item is a WARN. The option never lowers a non-zero exit and never replaces one: an exit 6 (unsupported, ADR-0043) stays 6, a failure stays 2. The JSON report's `exitCode` is the exit the command returns, so the two never disagree. The default keeps every exit code as before.
- The same commands take **`--junit <file>`**: the report as JUnit XML, one `testcase` per report item in report order, named by the item's stable name, in a suite named `jrsctl <command>`. FAIL is a `failure`, SKIP is `skipped`, PASS carries its detail in `system-out`. A WARN is a `failure` of type `WARN` only with `--fail-on warn`; otherwise it passes with its text in `system-out`, so the test view agrees with the exit code. Every text goes through the redaction filter.
- The file is written by hand (elements, attributes and XML escaping; characters XML 1.0 cannot carry are dropped), with no XML library and no new dependency, through a sibling temporary file and one move. It replaces an existing file.
- A JUnit file that cannot be written is reported on stderr, and a command that would have exited 0 exits **2**, so a pipeline never reads a missing test file as a pass. The file is written only when a report was produced: `import verify` refusing an archive that is not an export writes none.
- The operator guide gains a "Gating a container image in CI" section.

## Consequences

- A pipeline can gate on `doctor --fail-on warn` and publish `--junit` files without parsing JSON.
- `--fail-on warn` on `doctor` is strict: an item that is WARN by design in that environment (for example `service` with `service.kind: manual`, or `cluster` on a commercial licence) fails the gate. That is the operator's choice to make; the default stays `fail`.
- `hotfix verify`, which shares the report rendering, is not changed: it is not a gate a pipeline runs against a built image.
