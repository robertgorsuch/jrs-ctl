# ADR-0030: `hotfix record` enters a hand-applied official package into the ledger

Date: 2026-09-19. Status: accepted. Field test 2 finding H6 (`docs/reviews/2026-09-18-field-test-2-review.md`), issue #99.

## Context

The hotfix ledger (`hotfixes_installed`, spec §5.4) only ever held what `jrsctl hotfix apply` did,
because a row carries the files jrsctl snapshotted and can therefore be rolled back. A tester
applied a Jaspersoft cumulative hotfix by hand, then found `jrsctl hotfix list` empty. Task 2 of the
field test 2 plan made `hotfix list` say that it lists jrsctl's own work; the question left open
was whether the ledger should also be an inventory.

The two options were (a) a `hotfix record <package.zip>` command that stores an `INSTALLED` row
from the package's readme with no file ownership, visibly different from an applied row, or (b)
leaving the ledger as the record of jrsctl's own work. The issue recommended (b) unless support
wanted an inventory. The operator asked for the fix, and the tester's expectation was the inventory:
a server that carries a vendor hotfix should say so in the one place that lists hotfixes, and an
upgrade should know the hotfix is there so it can say it will not re-apply it.

## Decision

1. `hotfixes_installed` gains an `origin` column (migration `V003__hotfix_origin.sql`, default
   `JRSCTL` for every row written before it). `HotfixInstalled.Origin` is `JRSCTL` (files owned, a
   snapshot taken, rollback possible) or `RECORDED` (applied by hand, an inventory row only).
2. `jrsctl hotfix record <package.zip> [--json]` reads the package's outer readme exactly as the
   official-package conversion of ADR-0024 does and stores an `INSTALLED` row with the same derived
   id (`JRSHF-<release>-<date>-<time>`), title and release, origin `RECORDED`, run id `recorded`,
   no snapshot and no files. It refuses a file that is not an official package and an id already in
   the ledger, so a package applied through jrsctl and the same package recorded by hand cannot both
   be rows. It writes the state store and an audit row (`hotfix.recorded`) and touches nothing on the
   server; it is a plain operation, not a plan, like `init` writing `config.yaml`.
3. A recorded row is visibly different everywhere it appears: `hotfix list` prints an `ORIGIN`
   column (`jrsctl` or `recorded (by hand)`), `--json` carries `origin`, and the console's hotfix
   rows carry `origin` with the same words in the Hotfixes page.
4. `hotfix rollback` refuses a recorded row with exit 2: applied outside jrsctl, nothing to put
   back. An upgrade's `plan-hotfix-reapply` classifies it `SUPERSEDED` with the reason "applied
   outside jrsctl and only recorded; re-apply by hand if the new version needs it", so it is listed
   in the plan and never re-applied.
5. jrsctl does not verify that the package's files are actually in place on the server. The row
   says what the operator states was applied, no more; the readme's file list is not checked
   against the webapp, because a cumulative hotfix's files are overwritten by the next one and by
   an upgrade, and a false "not applied" would be worse than trusting the operator.

## Alternatives

- **Leave the ledger as jrsctl's own record (option b).** Rejected once the fix was asked for: the
  tester's question was "what does this server carry", and the answer has to come from the tool
  that also decides what an upgrade re-applies.
- **Record by scanning the webapp for the package's files.** Rejected for the reason in point 5:
  the vendor's packages overwrite each other, so file presence proves nothing about which hotfix
  put a file there.
- **A new `HotfixState`.** Rejected: a recorded hotfix is installed; what differs is who installed
  it, which is what `origin` says, and the lifecycle (`INSTALLED` to `SUPERSEDED` on upgrade) is the
  same.

## Consequences

- `state.db` moves to schema version 3; an older jrsctl refuses a newer store as before.
- The ledger no longer means "jrsctl can undo everything in it"; the `ORIGIN` column and the
  rollback refusal carry that distinction, and the operator guide says so where `hotfix list` and
  `hotfix rollback` are described.
- A recorded row has no run directory and no bundle copy, so nothing under `runs/` refers to it
  and retention never prunes anything on its account.
