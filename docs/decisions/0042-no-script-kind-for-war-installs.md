# ADR-0042: a WAR + buildomatic install uses the catalina kind, not a new one

Status: accepted · Date: 2026-09-26 · Spec: §5.1, §12.0, §12.1 · Issue #147

## Context

The vendor's binary installer is "intended for evaluation purposes only" (Platform Support 10.1 p.13). Production installs are WAR + buildomatic, for which the vendor registers no service at all: the operator either has a service their own team registered under its own name, or starts the server with a bare `startup.bat` / `startup.sh` in the Tomcat's `bin` and stops it the same way (vendor doc review P6, `docs/reviews/2026-09-17-vendor-doc-review.md`). Issue #147 asked whether `manual` covers that case or whether a new `script` kind, where jrsctl runs `startup` and `shutdown` itself, is needed. It also asked that `doctor` see a running Tomcat under the installation whatever `service.kind` says.

## Options

1. **A new `script` kind** that runs `startup` and `shutdown`. `startup` only calls `catalina start` and `shutdown` only calls `catalina stop`, so this would repeat the `catalina` kind (ADR-0016) under another name, with a second place for the forced-stop and stale-pid-file rules.
2. **Use `catalina`, and say so.** The `catalina` kind already runs `<tomcat>/bin/catalina stop|start` and judges state from the process list rather than from any service, so a Tomcat started by hand with `startup` is seen and stopped. `init` already proposes it when it finds no registered service and no `ctlscript`. What was missing was the wording: nothing said that this is the usual answer for a WAR install.
3. **Leave WAR installs on `manual`.** This works, but the operator does every stop and start by hand and `--yes` cannot run unattended.

## Decision

Option 2. No new kind.

- `init` explains a proposed `catalina` kind as "no registered service found (usual for a WAR + buildomatic install); jrsctl runs catalina.sh|bat to stop and start Tomcat, whether it was started by that script, by startup or by hand; choose manual if a supervisor or your own tooling starts it". It explains a `manual` fallback as jrsctl asking the operator to stop and start Tomcat.
- The operator guide says how to choose a kind: a vendor or team-registered service (`windows-service` or `systemd` with its name), `catalina` for a Tomcat started by script, `manual` when a supervisor or other tooling owns the process.
- `doctor` gains a `running-tomcat` item, based on `Platform.runningTomcats(dir)`. It reports the pid of a Tomcat running under the layout's Tomcat directory whatever the kind, and warns when the configured service disagrees: STOPPED while that Tomcat runs, which means the service does not control it, or RUNNING while no Tomcat under the installation runs, which means the service may belong to another server. A process whose command line this account cannot read and which listens on this Tomcat's ports is named as possibly it: a PASS when the service reports RUNNING, a WARN otherwise, never a contradiction (ADR-0014). On Windows the installer's service runs Tomcat inside `tomcat10.exe` as LocalSystem, which the scan used to leave out entirely. `TomcatProcessFinder.findWithServiceWrappers()` keeps such a wrapper as an opaque process for this report only; the service steps keep `find()`, so their behaviour is unchanged. The first live run found this: without it the item reported a false disagreement on the stock 10.0.0 install.

## Consequences

- One script kind remains, so ADR-0016's forced stop and the stale-pid-file rule keep a single home.
- A customer-named service that jrsctl is not configured for is at least seen: its Tomcat shows up under `running-tomcat`, and a wrong `service.name` shows up as a disagreement before the first stop.
- Not verified on a real WAR + buildomatic host; none is available here. Unit tests cover each branch, and the item was run against the local 10.0.0 binary install.
