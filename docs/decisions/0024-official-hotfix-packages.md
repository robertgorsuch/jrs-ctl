# ADR-0024: Apply official Jaspersoft hotfix packages by deriving a bundle

- **Status:** accepted
- **Date:** 2026-09-17
- **Issue:** [#66](https://github.com/robertgorsuch/jrs-ctl/issues/66)
- **Spec:** §8 (hotfixes)

## Context

jrsctl applied only its own bundle format: `manifest.json`, a `SIGNATURE` over it, and a `payload/` tree. Support ships something else. The sample package a field tester supplied, `hotfix_JRSPro10.0.0_cumulative_20260730_0457.zip` (288 MB), is:

```
readme.txt                 product, release 10.0.0, build 20260730_0457, closed issues, install steps
jasperserver-pro.zip       300 files, paths relative to the deployed webapp (WEB-INF/lib, scripts, …)
  readme.txt               added / modified / deleted file lists, an "Important" section, manual notes
js-install.zip             207 files, paths relative to the installation (buildomatic/, samples/)
  readme.txt               the same sections for the installer tree
```

The vendor's own instructions are: stop the server, copy the whole webapp somewhere as a backup, delete the files the readme lists (and, if an earlier hotfix is installed, everything matching about forty `WEB-INF/lib/jasperreports-*-*.jar` globs), unpack both archives over the installation, re-apply any settings the overwritten files held, clear the JSP cache and start the server. Undoing it means copying the backup folder back.

Asking an operator to build a signed jrsctl bundle out of that first was the one thing the field test called "another layer of complexity"; the tester said it would be easier to apply the hotfix by hand.

## Decision

`hotfix verify` and `hotfix apply` accept an official package and **derive a jrsctl bundle from it**, rather than growing a second apply engine.

1. **Detection is by shape:** a ZIP with `readme.txt`, no `manifest.json`, and at least one of `jasperserver[-pro].zip` or `js-install.zip`.
2. **Conversion streams** the package into a bundle under the jrsctl home, named after the package's SHA-256, so `--plan` followed by an apply converts once. The derived manifest carries the SHA-256 of every payload file, so the ordinary verifier checks the conversion as it checks any bundle.
3. **Paths:** webapp entries map to `webapps/<server.webappName>/…`, installer entries to the installation directory. Both halves are applied, because jrsctl runs upgrades out of buildomatic and a stale installer tree would surface later.
4. **Actions come from this installation, not the readme's lists:** a file that exists now is `replace`, one that does not is `add`. The readme's "Deleted files" list and the glob deletions in its "Important" section become `delete` entries, expanded against this installation, never covering a file the package itself lays down, and only for files that are actually here.
5. **Authenticity:** the package is unsigned, so applying it needs `--allow-unsigned` exactly as an unsigned bundle does, and the override is audited. The plan prints the package's SHA-256 for comparison with the support portal.
6. **Manual steps stay manual:** the readme's SQL for particular databases, its optional properties and the settings to re-apply in overwritten configuration files are printed as plan warnings. jrsctl runs none of them.

The schema's hotfix id pattern gains a second form, `JRSHF-<version>-<date>-<time>`, for derived ids; authored bundles keep `JRS-<version>-HF-<nnnn>`.

## Consequences

- An official hotfix now runs as any other plan: prechecks, one snapshot, service stop, staged swap, postchecks, a journal row and `hotfix list`. **Rollback is per file from the snapshot**, which is finer than the vendor's "copy your backup over the webapp".
- The operator no longer generates keys or builds bundles for a vendor hotfix. The `--allow-unsigned` audit row records that an unsigned package was accepted.
- Conversion writes a second copy of the package (about 290 MB for the sample) under the jrsctl home, and the apply then stages and snapshots as usual. `runs prune` removes leftovers by age.
- jrsctl is now coupled to the readme's wording ("Deleted files:", "IMPORTANT", "Additional Notes:") and to the archive names. A package that drops those headings still applies: only the deletions and the notes are lost, and `OfficialHotfixTest` pins the parsing.

## Alternatives

- **A second apply engine for official packages.** Rejected: it would duplicate the snapshot, staging, journal and rollback machinery that spec §8 makes non-negotiable.
- **Have the operator convert with a separate command** (`hotfix import-official`). Rejected: one more step to learn, and the derived bundle is an implementation detail, not something to keep.
- **Trusting the package because of its shape.** Rejected: it would install unverified code from any ZIP in that layout. The signature rule does not bend for convenience.
- **Running the readme's SQL.** Rejected: the scripts are conditional on a site's upgrade history and database, the readme states them as prose, and getting that wrong is a data-loss bug.
