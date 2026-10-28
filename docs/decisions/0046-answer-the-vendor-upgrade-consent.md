# ADR-0046: jrsctl answers the vendor's upgrade consent question

Status: accepted · Date: 2026-10-28 · Spec: §7.4, §10.2 step 8 · Field run r-20261027-211006-91a5 (2026-10-27, 9.0.0 PRO to 10.0.0, Windows)

## Context

In every upgrade but `test`, the 10.0.0 buildomatic (`bin/setup.xml`, line 1403) stops and asks on stdin: "The script has detected that your instance is a compact installation (repository and audit in the same database), and it will remain compact after the upgrade. ... Enter 'y' to confirm or 'n' to exit." jrsctl gives a vendor script no stdin, so Ant fails with `setup.xml:1403: unexpected end of stream while reading input` and the Windows wrapper still prints "Checking Ant return code: OK". The field run above failed this way at step 12, after every backup had passed. `upgrade --test` never sees the question, because the vendor skips it when `js.option=test`. So did the fake vendor scripts, which is why no test caught it.

The vendor skips the question when the Ant property `SKIP_UPGRADE_CONSENT` is `true`. Ant loads `default_master.properties` as properties (`setup.xml` line 422), so the key works there. It is not in the 10.0 or 10.1 upgrade guides. On 2026-10-27 the operator added it by hand to the installed file, and the next vendor run got past the question.

## Decision

`write-master-properties` always writes `SKIP_UPGRADE_CONSENT=true` into the file it stages in the target buildomatic. It is jrsctl's own key, like the key password of ADR-0028, so it is not part of `masterOverrides` (the installed keys). The answer the question asks for is already enforced: `verify-target-package` refuses an `installType` change, or an `audit.*` key the installed file lacks, with exit 6 (`MasterInvariants`). A release whose setup.xml has no such question ignores the key.

The fake `js-ant` of the ops fixture and of `Phase5UpgradeTest` now behaves like the vendor's: `upgrade-minimal-pro` without the key in the staged file prints `BUILD FAILED` and the setup.xml line and exits 0.

## Consequences

- A Split installation is answered "yes" as well. That is the vendor's own default when the property is set, and the installation type is checked before the run.
- If a later release renames the property, the run fails at the vendor step with the setup.xml line quoted in the message (the BUILD FAILED excerpt of the same change), not with stack frames.
- An operator who added the key by hand changes nothing: the staged value is the same.
