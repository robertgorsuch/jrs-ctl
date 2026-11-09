# ADR-0053: the vendor's password migration as a command of its own

Status: accepted · Date: 2026-11-09 · Spec: §10.6 (new) · Related: issue #108 (`upgrade --migrate-passwords`), ADR-0052

## Context

JasperReports Server 10.1 introduced modern password storage and a buildomatic utility to move existing users to it: `js-ant migrate-passwords-dry-run`, `migrate-passwords`, `detect-password-strategy` and `cleanup-password-migration` (installation guide 10.1 pp.194-199). The migration is permanent; the guide's only way back is a database restore. jrsctl runs it today only as a step of a samedb upgrade to 10.1 or later (`upgrade --migrate-passwords`, issue #108).

The field procedure this tool is being fitted to (an 8.2 to 10.1 move through a new schema, section 9.12 of the customer's guide) runs the migration on its own, after the new server has been validated and the hop was made with `password.strategy=legacy`. It also fits any installation that chose the legacy strategy at install time and switches later. In both cases the operator today runs the four targets by hand, with no dry run forced first, no gate on a backup, no journal and no exit code a pipeline can read.

## Options

1. **A `passwords` command group**: `status` (read-only: `detect-password-strategy` and the webapp's storage setting, in the `doctor` layout) and `migrate` (a plan: doctor, verify the utility and its settings file, the backup gate, dry run, migration, verification, optional cleanup). Requires the installation on this machine.
2. **A flag on `upgrade`** for a run that upgrades nothing. Misleading: an upgrade plan backs up and stops the service, which the migration needs neither of.
3. **Leave it manual.** Honest, but the irreversible step of the procedure stays outside the journal and without the dry run and the gate the guide asks for.

## Decision

Option 1. The shape:

- **Where it runs.** On the server host, with the installation configured (`server.installDir`). The utility reads its configuration from `WEB-INF/js.password-storage-config.properties` of the deployed webapp (installation guide 10.1 p.196), so a REST-only or tools-only configuration (ADR-0052) is refused with exit 2 and the reason. A server below 10.1, read from the webapp's jar names or the running server, is refused with exit 6: the utility and its settings file arrived with 10.1.
- **`passwords status`**, read-only: `password-config` (the file and its `password.strategy`; WARN when not `modern`, since new logins then keep the legacy format), `vendor` (js-ant and `vendor.javaHome`), `strategy` (the counts `detect-password-strategy` prints; PASS when complete, WARN with the number of users still legacy, FAIL when the utility fails or prints no counts). Exit 0 unless an item fails. `--json` carries the counts and the strategy.
- **`passwords migrate --db-backup-confirmed [--cleanup]`**, four phases: `preflight` (`doctor`, `verify-password-tools`, `confirm-db-backup`, `migrate-passwords-dry-run`; nothing mutated), `migrate` (`migrate-passwords`, irreversible), `verify` (`detect-password-strategy`), `cleanup` (`cleanup-password-migration`, irreversible, only with `--cleanup`). The gate sentence is printed by the CLI before anything is planned and by the step when the flag is missing; the confirmation is audited.
- **The service keeps running.** The guide schedules the migration in a maintenance window but does not require the server down, and the utility works in batches against the database; the plan warns when the webapp is still configured for legacy storage, because new logins then keep the legacy format until it is reconfigured and restarted.
- **Completion** is the guide's rule read generously: at most one legacy password (the `anonymousUser`) and every other user modern. More legacy users is a WARN, not a failure, because externally authenticated users are skipped by design.
- **Idempotency.** `migrate-passwords` and `cleanup-password-migration` run once per run through a done marker, and the vendor utility itself skips users already migrated, so a resume repeats nothing that matters. Both are `irreversible()` with the guide's justification; their compensations are no-ops.

## Consequences

- Section 9.12 of the field procedure becomes one journaled run with the dry run, the gate and the verification the guide asks for, and `status` gives a pipeline an exit code to act on before and after.
- `upgrade --migrate-passwords` stays as it is: the samedb upgrade runs the same targets while the server is down, which `passwords migrate` cannot assume.
- Verified against a fake buildomatic only; the counts parser is pinned to the guide's output format, and a vendor change to that format degrades `status` to a FAIL ("printed no counts") rather than a wrong answer.
- A tools-only configuration cannot migrate passwords; a container deployment has to run the command where the webapp is deployed, or run the four targets by hand there.
