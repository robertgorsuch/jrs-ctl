# ADR-0036: the import rollback removes folders the import created from nothing

Date: 2026-09-19. Status: accepted. Issue #139; follows ADR-0031.

## Context

ADR-0031 made the rollback of a failed import delete what the import created under the folders the
pre-import snapshot covers. The snapshot covers only sidecar folders that already exist
(`snapshotRequest`), and when none exists there is no snapshot, no `backup.pre-import-listing` and no
rollback anchor at all: spec §9.4 said "importing new content is the ordinary case ... nothing to put
back". A live check against a real 10.0.0 server before 1.8.0 showed the consequence: an archive
whose folder was absent failed on the server after 31 entries had been created, jrsctl reported
"failed; rolled back" (exit 3), and all 31 stayed. The plan contradicted itself (the verbatim
warning that a rollback deletes what the import created, and "nothing to put back") and the failing
step logged "repository rollback is handled by the pre-import snapshot step", a step the plan did not
have. The same check confirmed that the designed case (folder exists) works.

## Decision

1. **A rollback anchor for new folders.** `RemoveNewContent` (`import.new-content-rollback`) is a
   mutating no-op-on-execute rollback anchor placed before the import steps, like
   `RestoreFromPreImportSnapshot`. Its `execute` records in `runs/<runId>/new-content-roots.txt` the
   topmost missing ancestor of each folder that does not exist at that moment (for `/a/b/c` with only
   `/a` present: `/a/b`), and writes nothing to the server. Its `compensate` deletes each recorded
   root that exists, deepest first, with `DELETE /rest_v2/resources<uri>` (which removes the subtree,
   observed on the real server).
2. **The step is in the plan whenever the sidecar names folders**, existing or not. What is new is
   decided when the step runs, not when the plan is built. `runs recover` rebuilds the plan from the
   stored options against the server as it is then; if the plan depended on plan-time existence, a
   plan rebuilt after the import created the folders would lack the step and the rollback would
   silently do nothing. The record file is what the compensation trusts.
3. **A folder that existed when the step ran is never recorded, so never deleted**, including one
   created by someone else between the plan and the run. One created there after the step ran is
   deleted with the rest, as ADR-0031 already says for additions; the plan summary keeps saying so.
4. **Never recorded:** `/`, and an organisation's own folder (`/organizations/<id>`): resources
   cannot delete it (403 for the system administrator too, observed), so the plan and the run log
   warn and name `DELETE /rest_v2/organizations/<id>`.
5. **A deletion that fails is a failed rollback**: the step returns a recoverable failure naming the
   folders left, which the Runner reports as rollback incomplete (exit 4). ADR-0031's additions
   deletion only warns; that was left as it is, because a partial deletion under a restored folder is
   followed by the snapshot re-import and a warning.
6. **Whole-repository imports** (no sidecar, or a full-server archive) have no such step: their
   snapshot is `/`, so the listing already covers every addition.
7. The plan summary's rollback sentence names the folders deleted, the two snapshot warnings say a
   failed import deletes what it created, and the import steps' log note no longer claims the
   snapshot step does everything.

## Alternatives

- **Record the roots at plan time.** Rejected: see decision 2.
- **Snapshot the parent folder.** Rejected: it would export and re-import unrelated content to
  protect something that does not exist.
- **Delete the archive's own folder rather than the topmost missing ancestor.** Rejected: importing
  `/x/y/z` into a server with no `/x` creates `/x` and `/x/y` too, and leaving them is the same
  leftover the issue reports.

## Consequences

- One existence check per ancestor level per sidecar folder, at plan time (for the summary) and at run
  time (the step); a server that cannot answer them stops the run before anything is imported.
- Every import plan with sidecar folders has one more step; the plan, the run journal and
  `IdempotencyCoverageTest` know it.
- Not covered, as before: users, roles, settings and events an archive carries, and resources the
  server creates outside the archive's folders.
