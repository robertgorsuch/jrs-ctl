# ADR-0064: `write-master-properties` writes `dbPassword` from `database.passwordRef` when the target's file has none

Status: accepted · Date: 2026-11-18 · Spec: §7.4 · Issue #339 · Extends ADR-0028

## Context

`write-master-properties` copies the installed `default_master.properties` keys into the target package's buildomatic, leaving out every key whose name contains `pass` (spec §7.4). Every buildomatic validation needs the repository password, though. In the live 8.0.4 → 10.0.0 run of 2026-11-18, `upgrade --test` stopped with:

```
...\buildomatic\bin\validation.xml:340: Error: dbPassword property is not set
```

The vendor script still exited 0. jrsctl caught the `BUILD FAILED` and rolled back. The real run's `run-vendor-upgrade` validates the same way, so it would most likely have failed in the same place, but only after the service stop and the full export.

The plan only warned: "add them there before running if the vendor scripts need them". The fix that worked was found by reading `MasterProperties.stage`:

1. Create the target's file with just `dbPassword=`.
2. `stage` keeps the existing file and appends jrsctl's keys after it.
3. `restore` puts the file back afterwards.

That left a hand-made plain-text password file on disk, prepared outside jrsctl's redaction and journal.

jrsctl already holds the password: `init` offers to store it encrypted (`JRS_DB_PASSWORD`), and `database.passwordRef` names it. `doctor`'s database check and `clear-repository-cache` use it. ADR-0028 set the precedent: one password key, the import key's, is written by jrsctl from a reference, resolved at execute time, registered with the redactor, written from `char[]` and zeroed.

## Decision

- **Where `dbPassword` comes from:** `write-master-properties` writes it the way ADR-0028 writes the key password. It comes from `database.passwordRef`, resolved at execute time, registered with the redactor before anything can log it, written from `char[]` with the escaping `Properties.store` applies, then zeroed. Passwords in the *installed* file are still never copied.
- **The operator's file wins:** if the operator's own file in the target buildomatic already names `dbPassword`, jrsctl writes none, and logs that it kept that one. "The operator's file" means the pristine copy once this run has staged it, so re-executing the step judges the operator's file and not jrsctl's line.
- **Neither available: refuse early.** With no reference and no operator value, the step's precheck fails before the service is stopped. The failure names both remedies: `jrsctl secrets set JRS_DB_PASSWORD` plus `database.passwordRef enc:JRS_DB_PASSWORD`, or `dbPassword=` in the file.
- **Reference fails to resolve:** for example a locked `secrets.enc` with no passphrase. The step fails as recoverable, still before the stop.
- **The plan's warning:** it now says where the password comes from, and that after a successful run it **stays** in the target's `default_master.properties` in plain text. That is what the vendor's buildomatic expects of an installed server, and later jrsctl commands use that buildomatic (`point-config-at-target`). The warning tells the operator to restrict who can read the file.

## Where the password is on disk

| Run | The target's `default_master.properties` afterwards |
|---|---|
| `upgrade --test` | Removed or restored by `leave-target-package-as-found` |
| Failed run, compensated | Restored by `write-master-properties`' compensation |
| `upgrade rollback` | Restored with the rest of point B |
| Successful run | Kept, with `dbPassword` (plain text, as an installed buildomatic has it) |

## Not decided here

- **File permissions:** restricting the file's ACL or mode when jrsctl writes the password. `FileOps` has only an owner-only *check* today, and setting permissions per platform needs its own decision.
- **The second hop:** removing the line after a successful run, and writing it again for every later command that uses the target buildomatic, instead of leaving it.

## Consequences

- `upgrade --test` and the real run no longer need a hand-made password file when `jrsctl init` stored the database password, which is its default offer.
- An operator who prepared the file keeps full control: their value is used.
- The demo script's appendix step D (the hand-made file) becomes unnecessary for jrsctl releases that include this. The 2.8.0 demo still needs it.
- Tests: `UpgradeRunTest` covers the three cases:
  - written from the reference, escaped, and never in the log
  - the operator's value kept
  - refusal before the stop
- The upgrade fixtures now give `database.passwordRef`, or an operator file for the database fixture whose config deliberately has no reference.
- Tested against the fakes only.
