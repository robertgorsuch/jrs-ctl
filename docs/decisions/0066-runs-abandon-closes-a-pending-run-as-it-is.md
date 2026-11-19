# ADR-0066: `runs abandon` closes a pending run as it is

Status: accepted · Date: 2026-11-19 · Spec: §5.5, §6.6, §18 · Issue #350 (ported from jrs-hotfix #61)

## Context

A run that never reached a terminal state (a crash, a kill, a power cut) blocks every later mutating command with exit 8, and until now there were two ways out: `runs recover <id> --resume` and `runs recover <id> --rollback`. Neither fits when the operator has already put the server right another way (a restore, a redeploy, the remaining steps done by hand), or when compensating would undo a state that is now good.

The field run of 2026-11-18 hit exactly this. An 8.0.4 → 10.0 upgrade into a new Tomcat was killed at the start-service prompt, with JRS 10 already serving from the new Tomcat. Resuming would wait on the wrong Tomcat (issue #340). Rolling back would delete the webapp from the running new Tomcat and start the old one against the rebuilt database. jrs-hotfix met the same need in its own field test and added `runs abandon` (its #61).

## Decision

- **New command:** `jrsctl runs abandon <id> [--yes] [--json]`. It closes a pending run as it stands.
- **It shows before it asks:**
  - the steps by the last state the journal gives them: completed (SUCCEEDED or SKIPPED); interrupted (PENDING, RUNNING or FAILED); rolled back; and the stored plan's steps that never started;
  - a warning when the run's last service step to succeed was `stop-service`, with no later start or compensation, because abandoning does not start the service.
- **It asks `[y/N]`:** `--yes` answers. `--non-interactive` or `--json` without `--yes` refuses with exit 2, like every confirmation.
- **It changes the run record only.** No step executes or compensates. Nothing on the server changes, and no snapshot, backup or run directory is touched.
- **How the end is stored:**
  - the run is ended as `FAILED` with exit code 4 (ended, neither completed nor undone);
  - a `runs.abandon` audit row whose detail is the run id is written in the same SQLite transaction;
  - it is written under the run lock, so a run another process is executing is refused with exit 9.
- **Why no new state:** `runs.terminal_state` has a CHECK constraint, and an older jrsctl reads the column with `TerminalState.valueOf`. A new `ABANDONED` value would need a table rebuild and a schema version, and every older jrsctl refuses a schema newer than it knows. As jrs-hotfix did, the stored state stays one every version reads. The audit row, which is append-only, is what tells an abandoned run from a failed one.
- **Display:** `runs list` and `runs show` print `ABANDONED (exit 4)`. Their JSON keeps `terminalState: "FAILED"` and adds `"abandoned": true|false` to the run object (`run.schema.json`). `runs abandon --json` prints `runs-abandon.schema.json`.
- **Pointers:** the exit-8 message names `runs abandon` as the third choice, and the guided menu's recovery entry offers "Close job".
- **Afterwards:** pruning treats the run as any ended run. Its snapshots are removed when `backups.retentionDays` or `backups.maxSnapshots` say so, and are no longer protected as a pending run's are.

## Consequences

- An operator who fixed the server by hand can unblock jrsctl without editing `state.db`.
- Abandoning is a statement by the operator, not a check by jrsctl. Nothing verifies that the server is in fact right. The preview and the audit row (actor and time) make the decision visible afterwards.
- An abandoned upgrade is a `FAILED` run for `upgrade rollback`, exactly as a failed one is. This ADR changes nothing about what that command accepts.

## Not decided here

- A `--status ABANDONED` filter for `runs list`.
- Abandoning several runs at once.
