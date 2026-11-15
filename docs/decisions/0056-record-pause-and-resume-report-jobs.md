# ADR-0056: record, pause and resume report jobs as commands of their own

Status: accepted · Date: 2026-11-15 · Spec: §10.7 · Issue #304

## Context

A customer planning an 8.2 to 10.0 move stops the 8.2 scheduler during the export and resumes jobs on 10.0 only after validation. They asked that each job keep its original state: resume exactly the jobs that were active on 8.2 and leave the deliberately paused ones paused.

`import --pause-jobs` (ADR-0050) pauses only the jobs an import added and does not know which jobs were paused on the source; `repository load` (ADR-0052) pauses nothing. If the source scheduler is stopped by pausing every job before the export, the archive shows every job as paused and the original states are gone unless they were recorded first.

What the server reports, from the vendor's REST API reference (8.2 pp.145 and 167, 10.0 pp.196-197) and the #310/#311 field test on 10.0.0:

- `GET /rest_v2/jobs` lists each job's `id`, `label`, `reportUnitURI`, `owner` and `state.value`, the same shape on 8.2 and 10.0. The documented values are `NORMAL`, `EXECUTING`, `PAUSED` "or some error state"; the field test also saw `WAITING` on an active job after `js-import`.
- Both export routes write `<paused>` into each job, and `js-import` into a new schema keeps it. Whether job ids survive an import is not documented; a REST import into a server that already holds jobs can renumber them.

## Decision

A command group `jrsctl jobs` with three commands, independent of any import route:

- **`jobs record [--out <file>]`** is read-only (a report, like `doctor`). It writes every job's id, label, report URI, owner and state to a JSON file (`format: jrsctl-jobs/1`, the server's URL and version, the time) in the home's `jobs/` folder, or to `--out`. It exits 2 when the listing fails, and nothing is written.
- **`jobs pause (--all | --from <file>)`** is a journaled plan with one mutating step, `jobs.pause`. It pauses every job, or the jobs the file names (matched as below), except those already paused. Before the call it writes the ids it is about to pause to `runs/<runId>/jobs-paused.txt`; a second execution adds to that record, never shrinks it, because the first already paused them. Compensation resumes exactly the recorded ids.
- **`jobs resume --from <file>`** is a journaled plan with one mutating step, `jobs.resume`. It resumes the jobs the file records as active (`NORMAL`, `EXECUTING`, `WAITING`) that are paused on the server now. Jobs recorded as `PAUSED` stay paused. Jobs recorded in any other state (an error state) are reported and not resumed: the operator decides. The ids it resumes go to `runs/<runId>/jobs-resumed.txt` (same growing record), and compensation pauses exactly those again.

**Matching a recorded job to a job on the server.** The id comes first, but only when the job with that id has the same label, report URI and owner. Otherwise the match is by label + report URI + owner. When that key matches no job, or more than one, the entry is reported as unmatched and skipped. jrsctl never guesses between candidates.

**Failure.** Unlike ADR-0050's best-effort pause after an import, here the pause or resume *is* the operation. A listing or call the server refuses fails the step; the run rolls back (resuming or pausing what the record names), and the exit code says so.

The plan's summary counts what will happen (to pause, already paused, to resume, left paused, error state, unmatched) and names the unmatched entries; the fingerprint covers the server URL, the selection and, for `--from`, the file's SHA-256. The step recomputes the set at execution time, because jobs can change between the plan and the run.

The adapter gains `listJobs()`, returning `JobSummary(id, label, reportUnitUri, owner, state)`; `listJobIds()` becomes a default method over it.

## Consequences

- The customer's sequence works with any route: `jobs record` on the source, `jobs pause --all` before the export, the import or `repository load` on the target, validation, then `jobs resume --from <record>` on the target.
- An error-state job needs a decision by hand. The plan names it.
- Two jobs with the same label, report and owner whose ids changed cannot be told apart. Both are reported unmatched, and the operator resumes them by hand (`POST /rest_v2/jobs/resume`) or in the scheduler UI.
- `repository load --pause-jobs` (ADR-0052 follow-up) and recording job states in the export sidecar (#309) build on the record format and the matcher. They are not part of this change.
- Only checked against the vendor documentation and the 10.0 field data so far; an 8.2 server has not been tried.
