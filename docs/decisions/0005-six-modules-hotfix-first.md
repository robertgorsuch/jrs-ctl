# ADR-0005: Six Maven modules and hotfix before export/import

Status: accepted · Date: 2026-09-08 · Spec: §4, §14

## Context
Draft 1.0 had nine modules (`core`, `adapters`, `engine`, `ops`, `cli`, `console`, `app`, `dist`, `acceptance`) and built export/import before hotfix and the console before upgrade.

## Decision
Modules: `core` (config, platform, state, engine), `jrs`, `ops`, `app` (CLI + console + main), `dist`, `acceptance`. Phase order: Skeleton, Core+Engine, Adapter+Init+Doctor, Hotfix, Export/Import, Upgrade, Console, Distribution, Hardening.

## Consequences
Fewer cross-module API decisions and less Maven friction for an autonomous build. Hotfix is the first-listed customer problem, has no dependency on export/import, and is fully testable without a real server. The console only renders events every operation already emits, so it comes after the last operation exists.
