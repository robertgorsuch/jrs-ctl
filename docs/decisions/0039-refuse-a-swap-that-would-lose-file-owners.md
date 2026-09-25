# ADR-0039: refuse a hotfix swap that would lose the files' owners

Status: accepted · Date: 2026-09-25 · Spec: §6 (prechecks), §8.2 (apply), §8.3 (rollback) · Issue #157

## Context

`atomicReplace` captures a file's owner and access rules, writes the new file and re-applies them. The access rules always come back; the owner comes back only when this account may assign it. `DefaultFileOps.applyOwner` logged a failure as a WARN and carried on, as its Javadoc said it would.

A real support bundle from 2.0.0 showed what that means. An official cumulative hotfix applied from a non-elevated Windows prompt ended `SUCCEEDED`, exit 0, while 178 replaced files, 124 of them in the webapp and including `WEB-INF\lib` jars, changed owner from `BUILTIN\Administrators` to the operator's account. Nothing in the plan, the progress lines or the outcome block said so: the only trace was 178 lines in `logs/jrsctl.log`. A `hotfix rollback` restores files the same way, so it would repeat the damage.

The owner matters. On Windows the owner of a file can always rewrite its access rules, so jars loaded by a service running as `LocalSystem` end up controlled by an ordinary account long after the run.

## Options

1. **Keep going, and report it.** Collect the files whose owner could not be restored and list them in the outcome block and as a plan warning. The change still happens; the operator learns about it afterwards and has to repair owners by hand.
2. **Refuse before anything changes.** Preflight works out whether the owners can be restored and, if not, fails with exit 2, nothing changed, naming the owner and saying to run elevated or as that owner.
3. **Guess from elevation.** Refuse on Windows when the process is not elevated, and on Linux when it is not root. That is simpler, but wrong both ways: an unelevated account that owns the files loses nothing, and an elevated one may still be refused a particular owner.

## Decision

Option 2, the safer option (spec §0: ambiguity resolves to the safer choice). The test is exact, not a guess:

- `FileOps.canRestoreOwner(file)`: true when a new file created in the same directory already gets `file`'s owner, so there is nothing to restore; otherwise true only if `file` can be given the owner it already has, which needs exactly the right a later restore of that owner needs. Neither probe changes anything: the probe file is removed again, as `isWritable`'s is, and re-assigning a file's own owner leaves it as it was.
- `OwnerRestore.problem` groups the files by current owner and probes one file per owner, so a package of 784 files costs one probe per owner.
- The hotfix `preflight` step (apply) checks the files it will replace or delete; the `restore-snapshot` precheck (rollback) checks the files it will restore.

Verified against a real JasperReports Server 10.0.0 install from a non-elevated prompt: a file owned by `BUILTIN\Administrators` is reported as one whose owner would be lost; a file already owned by the operator's account is restorable, not refused; neither file's owner changed and no probe file was left behind.

## Consequences

- A hotfix that would have silently changed owners now stops at preflight with exit 2. The operator runs it again from an elevated prompt (Windows) or as root or the owning account (Linux). That is what the vendor's own readme assumes anyway.
- Files whose owner was already changed by an earlier run are owned by the operator's account now. They pass the check, which leaves them as they are; restore their owner by hand (for example `icacls <file> /setowner "BUILTIN\Administrators"` from an elevated prompt).
- `applyOwner` still logs and continues when a restore fails during a run. With the precheck in place that only happens when something changed between preflight and the swap.
- Other users of `atomicReplace` and snapshot restore (customization re-apply, the upgrade's file restores) do not run this check yet.
