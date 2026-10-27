# ADR-0044: the webapp backup names a file it cannot read, and leaves it out only when asked

Status: accepted, the default amended by ADR-0045 (2026-10-27) · Date: 2026-10-12 · Spec: §10.2 step 6, §10.4 · Field bundles r-20261012-194157-4a57, r-20261012-202802-6c66

## Context

Two newdb upgrades of a 9.0.0 server on Windows (field test, 2026-10-12) failed in `backup-webapp` after three to five minutes with `cannot archive: The process cannot access the file because another process has locked a portion of the file`, exit 3, nothing mutated. That text is Windows error 33, a byte-range lock held by another process, which no permission or elevation reads past (the second run was elevated and failed at the same point, leaving the same 370.9 MB of partial archive both times, so the lock is held continuously). The failure named no file: the read error from the channel carries only the operating system's text, and the step passed it through with a remediation about disk space. A read-only scan of a stock running 10.0.0 webapp on the build machine found no locked file, so the holder is specific to that installation; the bundle cannot say which file.

The backup phase runs while the server is up by design: the outage of a newdb upgrade starts at `stop-service` and lasts through the export and the vendor run (ADR-0025), and the backups are taken first to keep that window short. The operator ruled out stopping the server earlier: it would lengthen the outage. The vendor's own guides take the file backups with the server stopped, which is the alternative jrsctl chose against for that reason.

## Options

1. **Stop the server before the backup phase.** Removes the lock and every other reason a file could change during the archive. Lengthens the outage by the time the archives take (minutes to tens of minutes on a slow disk) and contradicts ADR-0025's reason for ordering the phases. Rejected by the operator for this installation.
2. **Skip a file the archive cannot read, always, with a warning.** Makes the step pass in this case without an option, but turns an incomplete backup into something a run can produce without anyone having asked for it. For an in-place upgrade (no `--tomcat-dir`) the point-B restore puts that archive back over the webapp, and a missing file would be noticed only at restore time, when it is too late to take another backup.
3. **Name the file, and skip only on request, recording what was skipped.** The error names the path, with a remediation that says who to look for and what the option does. With `--skip-locked-files` a file that opens but cannot be read is left out, with no partial entry, named in the step output and listed next to the archive; the plan warns before the run; the stored arguments and the fingerprint carry the choice.
4. **Read through a Volume Shadow Copy.** The right answer to a locked file on Windows in general, and the only one that gives a consistent image. Needs either a new dependency or a shell-out to `vssadmin`/WMI with elevation, and nothing on Linux; out of proportion to one file on one installation.

## Decision

Option 3. The archive opens each regular file and reads its first chunk before it writes the entry header, so a file that cannot be read either fails the archive with its path in the message (default) or, when skipping is on, is left out without a partial entry. A read that fails after the first chunk is always an error naming the path: an entry already begun cannot be taken back. The step writes `<archive>.skipped` (one absolute path per line) next to a finished, hashed archive, logs a warning per file, and reports the count; `--skip-locked-files` is an `upgrade` option, in the plan's warnings, in `PlanRegistry`'s stored arguments (so `runs recover` archives the same way) and in the fingerprint (only when set, so the fingerprint of an unchanged plan stays what an earlier jrsctl computed). The remediation of the default failure names the file, the tools that name its holder (Resource Monitor, `handle.exe`) and the option.

Skipping is a choice the operator makes for a run, never the default, because for an in-place upgrade an incomplete backup is worse than a stopped run. The case that justifies it is the one in the field: an upgrade into a new Tomcat, where the old webapp is left as it was and the archive is a safety copy of a tree the upgrade never modifies, and a file a server holds a lock on is runtime state (a cache, an index, a lock file), not deployed content.

## Consequences

- `Archives.create` has an overload returning `Created` (entries and skipped paths); the four-argument form keeps returning the count and fails on the first unreadable file, naming it. `ArchiveFormat.write` takes the `LockedFiles` policy; both formats open through it.
- `UpgradeOptions` gains `skipLockedFiles` (every `with` method now keeps all other fields; `withKeyPassword` used to drop `migratePasswords`). `SnapshotSet.skippedFileFor` names the record. The option is in `PlanRegistry.upgradeArgs`, the plan warning `SKIP_LOCKED_FILES_WARNING`, the fingerprint input `skipLockedFiles`, and `jrsctl upgrade --skip-locked-files`.
- A restore of an archive with a `.skipped` record does not recreate those files and does not yet say so; a warning at restore time is a follow-up.
- The lock test holds a `FileChannel` lock in the test process, which Windows enforces across handles, so it reproduces the field error byte for byte; it is Windows-only, since a Linux lock is advisory. The behaviour is exercised in `ArchivesTest` and `UpgradeStepIdempotencyTest`.
- Not exercised against the field installation yet: the operator still has to identify the holder, and may find that closing it is enough.
