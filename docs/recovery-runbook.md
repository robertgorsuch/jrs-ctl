# jrsctl recovery runbook

What to do when a jrsctl run did not end the way it should have. Every situation below is
identified by the exit code and the message the command printed; the commands named here are
described in full in the operator guide (`jrsctl docs operator-guide`, or `jrsctl <command>
--explain`). Nothing in this runbook needs network access.

Start with the two commands that never change anything:

- `jrsctl runs list` shows every run, its terminal state and exit code; a run without a terminal
  state is pending.
- `jrsctl runs show <id>` shows the plan the run was started from, every step transition in the
  order it was journaled (`PENDING -> RUNNING -> SUCCEEDED` or `FAILED`, `ROLLED_BACK`, `SKIPPED`),
  the snapshots it took and, for a failed run, the outcome block with the cause, the affected paths,
  the backup location and the next action.

## Exit 8: "N runs need recovery before anything else can run"

A previous run has no terminal state: the process was killed, the machine lost power, or the
journal could not be written (see below). Every mutating command refuses with exit 8 until the run
is recovered, and prints the exact command.

1. `jrsctl runs show <id>` and look at the last transition. `RUNNING` means the process died inside
   that step; `FAILED` means it died after the step failed but before the step was compensated.
2. `jrsctl runs recover <id> --resume` re-runs the precheck of the interrupted step and then
   re-executes it. Every step is idempotent, so a step that had already done its work converges
   without doing it twice. Resume then continues with the remaining steps and ends with the run's
   normal exit code.
3. `jrsctl runs recover <id> --rollback` compensates the interrupted step (whether journaled
   `RUNNING` or `FAILED`) and then every succeeded step, newest first, and exits 3. Choose this
   when the precheck of the interrupted step fails (resume then offers only rollback), when the
   operator has changed their mind, or when the step cannot be trusted to converge.
4. Export and import runs are rebuilt from their stored arguments: the archive must still be where
   it was.

Two runs cannot be pending at once, because the second could not have started.

## Exit 9: "another jrsctl run holds the lock"

`runs.lock` in the jrsctl home names the process and run that holds it. If that process is still
alive, wait for it or cancel it with Ctrl-C (it compensates the step in flight and exits 5). If the
process is gone, the lock is stale: `jrsctl doctor` reports it, and the next mutating command
removes it after confirming the holder is not running. Do not delete `state.db`; the lock file
alone is safe to remove when its holder is dead.

## Exit 3: "run failed, rolled back"

The step named in the outcome block failed; jrsctl undid that step's partial work first, then
every succeeded step back to the start of the failing phase (or the whole plan with
`--rollback-all`). The server is in the state it was in before the phase started. Read the cause,
fix what it names (a locked jar, a refused credential, a database that rejected a statement), and
run the command again. Nothing needs to be restored by hand.

An import that rolled back is the one exception: the pre-import snapshot restores overwritten
resources but cannot delete resources the failed archive created. Check the repository for
resources the failed import added; the snapshot stays under `snapshots/pre-import/` and can be
re-imported by hand with `jrsctl import <snapshot> --update`.

## Exit 4: "rollback incomplete"

A compensation failed after the run had already mutated the server, or a fatal failure happened
after a mutation. The outcome block lists the backups, always under `snapshots/<runId>/` in the
jrsctl home, and the step whose compensation failed.

1. `jrsctl runs show <id>` to see which steps are `ROLLED_BACK`, which is `ROLLBACK_FAILED`, and
   which succeeded steps were never compensated because the rollback stopped at the first failure.
2. Restore what the failing compensation could not: for a file step, the snapshot directory named
   in the outcome holds every touched file with its original permissions; for a SQL step, the
   bundle's rollback scripts under `runs/<id>/bundle/sql/` are the ones jrsctl would have run, in
   reverse order; for a service step, stop or start the service by hand.
3. `jrsctl doctor` confirms the server, the service and the configuration before anything else
   runs.
4. For an upgrade whose point-B restore failed, `jrsctl upgrade rollback <runId> --to-point B`
   repeats the whole restore (webapp, buildomatic, configuration, keystore) from the recorded
   backups and refuses to start if any of them is missing or does not match its recorded hash.

## "the run journal could not be written"

`state.db` could not take the next transition: the disk is full, the file was deleted or made
read-only, or the database is damaged. The run stops at once with exit 4 (exit 2 when nothing had
been mutated yet), says so, and names the `runs recover` command, because nothing further could
have been recorded. Free the disk or repair the permissions, run `jrsctl doctor`, then recover the
run as under exit 8. If the database itself is damaged, move it aside, run `jrsctl selfcheck` to
create a fresh one, and restore the server from the newest `snapshots/<runId>/` by hand; the
journal is gone with the file.

## "sc.exe stop ... exited 5: Access is denied" and similar

The service manager refused the command; jrsctl did not wait for the service because waiting
could not have helped, and nothing was changed. Run jrsctl with the rights the message names: an
elevated (administrator) prompt on Windows, root or a polkit or sudo rule on Linux.

## "js-upgrade-... was started in run <id> and never reported back"

The vendor upgrade script was launched and the process died before it reported an exit code, so
jrsctl cannot tell whether the repository database was migrated. Read the buildomatic log under
the target package. If the upgrade did not run, delete the attempt marker the message names and
resume; if it did, `jrsctl upgrade rollback <id> --to-point B`. With `--mode samedb` the database
migration is not undone by jrsctl: restore the database from the backup you confirmed with
`--db-backup-confirmed` before rolling the files back.

## "passphrase does not unlock secrets.enc on this machine"

Either the passphrase is wrong or the store was created on another machine. A store created by a
build before 2026-09-10 (version 1, bound to the DNS host name) is rebound to the machine identity
automatically the first time the right passphrase unlocks it, and the log says so; if the host was
renamed before that happened, the old name is gone and the store cannot be opened: run `jrsctl
secrets init` after moving the old file aside, then `jrsctl secrets set` for every entry
(`jrsctl secrets list` on the old file still lists the names without a passphrase).

## "keystore fingerprint mismatch: archive was exported with keystore ..."

The archive comes from a server with a different `.jrsks`; its encrypted passwords cannot be
decrypted here. Copy the source server's `.jrsks` and `.jrsksp` to this host and import again with
`--source-keystore <path> --source-keystore-password-ref <ref>`; that import always uses the
vendor tools with the service stopped, whatever `--strategy` says, and restores the current
keystore from its backup on rollback.

## A hotfix that should not have been applied

`jrsctl hotfix rollback <id>` at any later time restores every file from the snapshot the apply
run took and runs the bundle's rollback SQL in reverse. A hotfix declared `"rollback":
"irreversible"` has no SQL rollback; its files are still restored and the plan says which database
changes remain. Snapshots of installed hotfixes are never pruned.

## Getting the facts to whoever helps you

`jrsctl console` and the run page's support bundle, or by hand: `jrsctl runs show <id> --json`,
`jrsctl doctor --json`, and `logs/jrsctl.log` from the jrsctl home. All three are already
redacted; none contains a secret, the key ring, the keystore or an archive.
