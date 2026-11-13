# ADR-0054: refuse a stale import/export class-path jar instead of deleting it

Status: accepted · Date: 2026-11-13 · Spec: §7.4, §10.2, §10.5 · Issue #315

## Context

buildomatic's Ant import targets (`import-minimal-<ce|pro>`, the imports inside `js-upgrade-newdb` and `-samedb`, every `run-import-export` call in `bin/import-export.xml`) run `ImportCommand` with a manifest-only jar, `conf_source/iePro/lib/iecp.jar` (`ieCe` for CE), whose `Class-Path` lists every jar in that `lib` directory. The macro `make-ie-classpath-jar` builds it **only when it does not exist yet**.

In the #311 field test, a hotfix applied on 2026-09-30 had replaced jars in `conf_source/iePro/lib` (for example `spring-context-6.2.11.jar` became `6.2.17`) and left the `iecp.jar` written at install time on 2026-05-28: 123 of its 511 entries named files that were gone. `repository load` created the schema, initialised it, and then `import-minimal-pro` died with `NoClassDefFoundError: org/springframework/context/ApplicationContext`; the rollback dropped the schema. Deleting `iecp.jar` made buildomatic rebuild it, and the next run succeeded. The `js-export` and `js-import` wrappers are not affected: they build their class path from `lib\*` themselves.

## Options

1. **Refuse in the precheck** with the file to delete and why. No mutation; the operator acts once.
2. **Delete it in a step** before the first Ant import. Automatic, but jrsctl would then change the vendor tree, which no plan does today outside `render-config`'s `build_conf/default`; the step needs a compensation (nothing to put back, since the file is regenerable output) and an `IdempotencyCoverageTest` entry.
3. **Do nothing** and document it.

## Decision

Option 1. `IeClasspathJar.stale(buildomaticDir)` reads only the jar's manifest, streamed, and reports the entries missing from `lib`. It is used in three places:

- `repository load` `verify-buildomatic` fails (exit 2) before anything is created;
- `upgrade` `verify-target-package` fails (exit 2) before the service is stopped, for the target package's buildomatic;
- `doctor` `vendor` warns, naming the jar and the first missing files.

The remediation names the file to delete and says buildomatic rebuilds it on its next import. A jar without a readable manifest is never reported (it cannot be judged).

## Consequences

- A hotfixed installation's buildomatic is caught before a plan spends minutes or creates a schema.
- The operator deletes one file once; option 2 stays available if this proves to be common in the field.
- jrs-hotfix, which applies the hotfixes that cause this, should delete `iecp.jar` when it replaces jars under `buildomatic/conf_source/*/lib`; that belongs in its own repository.
