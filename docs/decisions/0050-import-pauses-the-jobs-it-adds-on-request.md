# ADR-0050: an import pauses the report jobs it adds, on request and best effort

Status: accepted · Date: 2026-11-09 · Spec: §9.4, §9.5 · Issue #264

## Context

A full export carries report jobs. Imported into a server that runs beside the source, which is how a repository is moved to a new schema and validated before traffic switches, and how a `--tomcat-dir` upgrade leaves the old server when it keeps running, the jobs fire on both servers and deliver every report twice. The vendor's remedy is `POST /rest_v2/jobs/pause` before the scheduler runs them (REST API reference 10.0 p.229). jrsctl had no step for it: the operator had to sequence service start-up by hand or call the API themselves between the import and the scheduler's first tick, with nothing in the run journal to say it was done.

Three questions decided the shape:

1. **Which jobs.** Pausing every job on the server would also stop jobs that were already there and running on purpose. The jobs the import *added* are what should not fire. They are found by listing before and after: `GET /rest_v2/jobs` before the import, again after it, and the difference is what the archive brought.
2. **What a refused pause means.** A pause the server refuses after a finished import is not a failed import. Were the pause a step of the import phase, its failure would compensate the whole phase: delete what the import created and re-import the snapshot, undoing a good import because the scheduler API was down. The same precedent exists: `clear-repository-cache` in the upgrade is best effort for the same reason.
3. **What a rollback does.** A rollback of the run (Ctrl-C after the pause, or a later failure) must resume what jrsctl paused and nothing else: the jobs that were running before the import must not be touched.

## Decision

- `jrsctl import --pause-jobs` adds two steps. `backup.jobs-listing` (read-only, last in the `backup` phase, before any import-phase step) writes the sorted ids of the server's jobs to `runs/<runId>/jobs-before.txt`; a listing that cannot be taken fails the step before the import, because without it the pause could only pause every job or none. `jobs.pause-imported` runs in a phase of its own, `jobs`, after the import phase: it lists again, pauses the ids that were not there before, and writes them to `runs/<runId>/jobs-paused.txt`.
- The pause is **best effort**. A listing or pause the server refuses is a WARN naming the ids and the call to make by hand (`POST /rest_v2/jobs/pause` with that id list, or the scheduler UI), and the step succeeds: the import is done either way, and the run's exit code says so. Nothing in the `jobs` phase can roll the import back.
- **Compensation resumes exactly the recorded ids** (`POST /rest_v2/jobs/resume`), as often as it is asked. Pausing a paused job and resuming a running one are no-ops on the server, so a repeated execution or compensation is harmless, and `runs recover` can replay either.
- The plan says so: a warning (`PAUSE_JOBS_WARNING`) in the summary, a rollback entry for the `jobs` phase ("resume the report jobs this run paused"), the flag in the stored arguments (`pauseJobs`, absent means `false`, so older runs rebuild as before) and in the fingerprint only when set, so a plan made without the flag keeps the fingerprint it was journaled with.
- The adapter gains `listJobIds`, `pauseJobs` and `resumeJobs`; the two mutating ones are called only from inside these steps, like every other repository-mutating adapter method.

## Consequences

- An operator moving a repository beside a running source gets the vendor's remedy inside the run, journaled, and undone by the run's own rollback. The jobs stay paused until the operator resumes them; the plan and the step output both say how.
- A refused pause leaves jobs that will fire. The warning is the signal; the operator must act on it before the scheduler's first run. This is the price of not undoing a good import, and the same trade-off `clear-repository-cache` makes.
- `upgrade --tomcat-dir`, where the old server's jobs are the duplicate risk, does not get the step in this change; it is the natural follow-up, with the listing taken before `stop-service`.
- A job the import *replaces* (same id as one that existed, with `--update`) is not in the difference and is not paused: the server already held it, and a replaced definition inherits the old job's state.
