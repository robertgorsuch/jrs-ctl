# ADR-0045: the webapp backup leaves locked files out by default

Status: accepted · Date: 2026-10-27 · Spec: §10.2 step 6, §10.4 · Supersedes the "never the default" part of ADR-0044 · Field screenshots 2026-10-27 (runs r-20261027-195010-751f, r-20261027-201035-a93c)

## Context

ADR-0044 added `--skip-locked-files` and kept it off by default, because for an in-place upgrade the point-B restore puts the webapp archive back and a missing file would be noticed only then. On 2026-10-27 the field installation (9.0.0 PRO, WAR-file layout, Windows) ran 2.4.0: without the option the backup failed after twelve minutes naming `WEB-INF\logs\transactionBtm1.tlog`; with it, both `transactionBtm1.tlog` and `transactionBtm2.tlog` were left out and the step passed. Those two files are the Bitronix transaction manager's disk journal for Virtual Data Sources (`applicationContext-virtual-data-source.xml`, bean `transactionManagerConfiguration`, `lazy-init`): the running server holds a byte-range lock on them as long as it is up, so every server that has used a Virtual Data Source refuses the default backup, every time, after the archive has run for minutes. The maintainer asked for skipping to be the default.

## Decision

`jrsctl upgrade` skips locked files unless `--no-skip-locked-files` is given. `--skip-locked-files` is still accepted (2.4.0 command lines and the 2.4.0 guidance keep working) and changes nothing; giving both is a usage error (exit 1). Everything ADR-0044 records about a skip still holds: the file is named in the step output and in `<archive>.skipped`, the plan warns before the run (the warning now says it is the default and names the opt-out), the choice is in the stored arguments and the fingerprint.

The default lives in the command line, not in `UpgradeOptions`: the operations layer still skips only when told to, and `PlanRegistry` still reads a stored run without the key as "do not skip", so `runs recover` of a run planned by 2.4.0 rebuilds the plan it fingerprinted.

## Consequences

- An in-place upgrade (no `--tomcat-dir`) can now finish with a backup that lacks files nobody asked to leave out. They are runtime state in the cases seen so far, and they are listed, but a point-B restore does not recreate them and does not yet say so (ADR-0044's follow-up). The operator guide tells an in-place upgrade to pass `--no-skip-locked-files`.
- Every default `upgrade` plan carries the warning, so plans of 2.5.0 read differently from 2.4.0's.
- The help example for the option now shows the opt-out.
