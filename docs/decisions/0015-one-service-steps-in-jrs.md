# ADR-0015: One set of service steps, in `jrs.service`

Status: accepted, 2026-09-14. Amends spec §4 (module map). Issue #43.

## Context

The steps that stop, start and wait for JasperReports Server existed twice. Review finding 1.13
had already merged the hotfix and upgrade copies into `ops.service.ServiceSteps` after they
drifted. The vendor export/import strategy kept its own copy in `jrs.strategy.ServiceSteps`,
because `jrs` may not depend on `ops` (spec §4). That copy drifted in the same three ways:

1. It wrote the "this run stopped the service" marker after a successful stop. A stop that went
   wrong half-way left no marker, compensation did not start the service, and a failed vendor
   export or import could leave the server down.
2. It had no handling for `ServiceControlException`, so a refused command waited out the whole
   stop timeout instead of failing at once with the rights remediation (spec §5.3).
3. It did not refuse an `UNKNOWN` state (ADR-0014) before stopping.

Porting the three behaviours into the second copy and pinning both to a contract test would keep
two implementations that can drift a third time.

## Decision

1. `ServiceSteps` and its `ServiceRuntime` seam move from `ops.service` to `jrs.service`. `ops`
   already depends on `jrs`, so the hotfix and upgrade plans import them unchanged.
2. `ServiceRuntime.Source` says where a step finds its runtime when it runs. An ops operation
   passes a `Fixed` source holding the runtime it already has. The vendor strategy builds its steps
   before any `Context` exists, so it passes `ContextServiceRuntime.source(clock, sleeper)`, which
   reads the controller, the stop timeout and the adapter from the context of the step that runs.
3. `jrs.strategy.ServiceSteps` is deleted. The strategy's step ids stay `<phase>.stop-service`,
   `<phase>.start-service` and `<phase>.wait-for-server`, so runs journaled by 1.2.0 still match a
   rebuilt plan during recovery, and the marker file name is unchanged.

## Consequences

- The vendor strategy gains the marker-before-stop, the fast failure on refusal and the `UNKNOWN`
  precheck. `ServiceStepsTest` and `VendorCliStrategyTest` in `jrs` pin all three.
- The vendor strategy's wait-for-server now polls `serverInfo` through `refreshIdentity()` with the
  spec §6.5 backoff under a ten-minute cap, as the hotfix and upgrade plans do. It used to poll an
  authenticated repository listing with the strategy's polling policy. The cap is unchanged. The
  progress line at INFO every thirty seconds becomes a DEBUG line per attempt.
- For the vendor strategy the stop step's plan detail names `service.stopTimeoutSeconds` instead of
  printing the number, because the configuration is not read until the step runs. Ops plans still
  print the number.
- `IdempotencyCoverageTest` lists three service steps instead of five, each covered by the hotfix,
  upgrade and jrs suites.
