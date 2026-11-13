# ADR-0055: schema marker states and a last-moment absence check

Status: accepted · Date: 2026-11-13 · Spec: §10.5 · Issue #316 · Amends ADR-0052 (compensation)

## Context

ADR-0052's compensation drops the schema named in `snapshots/<runId>/schema.created` when `default_master.properties` still names it. The `create-js-db` step wrote that marker before launching `js-ant create-js-db` and did not look at the catalogue again: the absence check ran in `verify-target-schema`'s precheck, a minute or more earlier, with `render-config` in between. A schema that appeared in that window (another run, another jrsctl home on the same buildomatic, a DBA) made `create-js-db` fail on "already exists", and the compensation then dropped a database this run had not created.

## Decision

- `create-js-db` asks the catalogue again immediately before it writes the marker. If the schema is there and the marker does not already name it, the step fails and writes nothing, so nothing is dropped. If the marker names it (a crash interrupted creation in this run), the step fails and the operator rolls back with `runs recover`.
- The marker records a state and the instant the schema was seen absent: `creating` while the vendor target runs, `created` once it returns 0. When the target fails, the step asks the catalogue again: schema absent → the marker is removed; schema present → state `uncertain`.
- The compensation drops in state `creating` or `created` (with the existing `default_master.properties` check) and refuses in state `uncertain`, which the run reports as exit 4 with the DBA's statement, the same path as the existing refusal.
- A one-line marker written by 2.7 reads as `created`, so those runs still roll back as before.

The safer option was taken for the remaining ambiguity: a failed `create-js-db` with the schema present may be this run's half-finished work or a concurrent creation, and jrsctl cannot tell; it leaves the schema to the DBA rather than guess.

## Consequences

- The window in which a foreign schema can be dropped shrinks from minutes to the instant between the catalogue query and the vendor's `CREATE DATABASE`, and even then only after a crash in that instant (a failed target never drops).
- A failed `create-js-db` that did create the schema now ends in exit 4 instead of a clean rollback; the message carries the `DROP DATABASE` statement.
