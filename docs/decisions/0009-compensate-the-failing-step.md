# ADR-0009: Compensate the failing step before the steps that succeeded

Status: accepted · Date: 2026-09-10 · Spec: §6.3, §6.6

## Context
Draft 1.1 §6.3 said a `Recoverable` failure "runs compensations in reverse order for all succeeded Steps". `Runner` implemented exactly that: the step that failed was journaled `FAILED` and never compensated. For a hotfix whose `atomic-swap` failed after k of n renames, or an upgrade whose vendor script exited non-zero, the earlier steps were undone (the service was started again by `stop-service`'s compensation) while the failing step's partial work stayed in `WEB-INF/lib` or the webapp, and the run was recorded `ROLLED_BACK` with exit 3. Every "the run is rolled back from the snapshot" message in `ops` was false for that step. The same gap existed in `runs recover --rollback`, which compensated `SUCCEEDED` and `RUNNING` steps but not one the crash had left `FAILED`. Found as item 1.9 of the 2026-09-10 codebase review.

## Decision
`Runner` compensates the failing step first, then the succeeded mutating steps in reverse, when the failing step is mutating and its `execute` actually ran. A step whose precheck failed never ran and is not compensated. `runs recover --rollback` treats a mutating step the journal left `RUNNING` or `FAILED` the same way. The `Step.compensate` contract is stated explicitly: it must converge from any partial state, including one where `execute` never started or a previous compensation failed. Irreversible steps are still skipped; a compensation failure still ends the run with exit 4 and names the step.

## Consequences
The "rolled back" outcome now means what it says for the step that failed. Every mutating step's compensation is already required to be idempotent and to run twice (`IdempotencyCoverageTest`), so no step needed rewriting; the ops-level tests that drive a mid-step failure through the Runner are the follow-up that proves it per step. The cancelled-in-flight path already compensated the interrupted step, so cancellation and failure now behave alike. `Fatal` failures are unchanged: they never compensate.
