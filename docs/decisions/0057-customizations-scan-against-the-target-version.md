# ADR-0057: customizations scan classifies site files against the target version

Status: accepted · Date: 2026-11-15 · Spec: §10.3 · Issue #305

## Context

`customizations scan --vendor <source>` compares a site's webapp with the vendor's copy of the version it runs and lists each file as `CHANGED`, `ADDED`, `INSTALLER` or `REMOVED`. For an upgrade, the operator also needs to know what the vendor did to the same files between the source and the target version. A customer planning an 8.2 to 10.0 upgrade (November 2026) called this their largest manual step: about 40 overridden vendor files plus their own Spring XML. Until now the answer was two scans and a hand-made join.

## Decision

- `customizations scan --vendor <source> --target-vendor <target> [--installed <dir>] [--out <dir>]` adds a second comparison: for each path the scan lists, how the vendor's file changed from the source to the target version (`UNCHANGED`, `CHANGED`, `REMOVED`, `ADDED`, `ABSENT`), by SHA-256. A war is read as a stream, never unpacked, as in the scan itself.
- Every site file gets one class:

| Scan | Source to target vendor | Class |
|---|---|---|
| any file under `scripts/` | — | `OVERLAY`: rebuild in jasperserver-ui (#117) |
| `INSTALLER` | — | `INSTALLER`: put the site's values into the target's copy |
| `CHANGED` | `UNCHANGED` | `CARRY`: re-apply as is |
| `CHANGED` | `CHANGED` | `MERGE` |
| `CHANGED` | `REMOVED` | `OBSOLETE`: moved or dropped in the target |
| `ADDED` | `ADDED` | `CLASH`: the target now ships a file at that path |
| `ADDED` | `ABSENT` | `OWN` |
| `REMOVED` | — | `REMOVED`: the site deleted a vendor file |

- **`CLASH` is not in the issue's table.** Calling such a file `OWN` would tell the operator to copy it over, which silently replaces the vendor's new file. Two separate classes are the safer report.
- **`--out <dir>`** writes each `MERGE` file as `<path>.mine` (the site's), `<path>.base` (the source vendor's) and `<path>.theirs` (the target vendor's), ready for `git merge-file` or any merge tool. Only those entries are copied out of a war, in one streamed pass per war. A directory that exists and is not empty is refused before anything is written; every file stays under it. jrsctl merges nothing itself, and no dependency is added.
- **Report only.** `--target-vendor` with `--register` is a usage error (exit 1), as for `--installed`: a comparison of two versions is not the inventory of the running installation. `--out` without `--target-vendor` is a usage error too. `--vendor` and `--target-vendor` naming the same webapp is refused with exit 2.
- The text report starts with the count per class and then lists the files by class, each class with one line saying what it asks of the operator. `--json` keeps every existing field and adds `targetVendorWebapp`, `classCounts`, `mergeOut`, and per entry `class`, `target` and, for a written merge, `merge`.

## Consequences

- An operator gets the join they were making by hand, and three-way merge inputs for the files that need a merge.
- `CARRY` says nothing about whether the file still works on the target: a Java class or Spring XML may still need porting to Jakarta EE for a 10.x target. The report says so; a readiness check is #306.
- Expect a long `MERGE` list for a move to 10.x, since the Jakarta change touches many vendor files. The counts per class give the scale first.
- A vendor file the site left alone is not listed, whatever the target did to it: that is the upgrade's own business.
- Verified against small fixture webapps and wars only; not run against real 8.2 and 10.0 distributions yet.
