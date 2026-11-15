# ADR-0059: the export sidecar records report job states

Status: accepted · Date: 2026-11-15 · Spec: §9.3, §10.7 · Issue #309 (depends on #304, ADR-0056)

## Context

`jobs record` (ADR-0056) keeps each report job's state across a move, but it is a separate file the operator must remember to take. The export already writes a sidecar next to the archive (`<archive>.jrsctl.json`, spec §9.3), and `export record` (#263) writes one for an archive made by hand. Carrying the job states in the sidecar keeps them with the archive they belong to.

## Decision

- The sidecar gains **`sidecarVersion`** and a **`jobs` block**. A sidecar without `sidecarVersion` is version 1 and has no block; this jrsctl writes version 2. jrsctl's JSON reader ignores fields it does not know, so an older jrsctl still reads a version 2 sidecar.
- The block is either **recorded**: `recordedAt`, `serverUrl` and, per job, `id`, `label`, `reportUnitUri`, `owner` and `state`, the same data `jobs record` writes; or **not recorded**, with the `reason`. It is never a silent empty list.
- **`export`** fills the block in its `export.sidecar` step from `GET /rest_v2/jobs`. A listing the server refuses never fails the export; the block then says why. The states are those at export time.
- **`export record`** asks the configured server only with **`--jobs`**. The archive it describes may come from another server (that is what the command is for), so recording the configured server's jobs by default could attach the wrong server's states; without `--jobs` the block says so. With `--jobs` and a server that cannot be asked, the sidecar is still written, with the reason. This is the safer of the two readings of the issue's "when the configured server is reachable".
- **`jobs resume --from`** (and `jobs pause --from`) accept an export archive, or its sidecar, as well as a jobs record. A sidecar of version 1, or one whose block is not recorded, is refused with exit 2 and the reason, never read as "no jobs". For the plan fingerprint, an archive stands for its sidecar: the sidecar is hashed, not a multi-gigabyte archive.
- **States paused for the move.** If the operator paused the jobs before the export (the order ADR-0056 describes), the block records every job as paused, and resuming from it resumes nothing. jrsctl cannot tell such a pause from a real one, so the resume plan warns when every job in a sidecar was paused, naming the `jobs record` file as the source to use instead. The operator guide says to export before pausing, or to keep using `jobs record` before the pause.

## Consequences

- An archive exported by jrsctl carries its jobs' states with no extra step.
- `jobs record` stays the reliable source when the scheduler must be paused before the export.
- `export record --jobs` puts the responsibility of naming the right server on the operator, as the flag's help says.
- Verified against WireMock and fakes only.
