# ADR-0033: import leaves REST above 2 GB and skips themes across a major version

Date: 2026-09-19. Status: accepted. Vendor documentation review §4.3, issue #115.

## Context

The REST reference (p.118) says Jaspersoft does not recommend uploading files greater than 2 GB. An
import over REST streams the archive whole, so a large full-server archive fails inside the server
after a long transfer and the pre-import snapshot has been taken for nothing. Themes are repository
resources (themes guide §2-3); a theme written for an older major version may not fit a newer one,
and `--skip-themes` was off by default whatever the versions.

The issue proposed: above 2 GB choose the vendor strategy when its tools are available and the
strategy was not forced, otherwise exit 2 naming `--strategy vendor`; default `skipThemes` on when the
sidecar's source major differs from the target's, with `--themes` to turn it off.

## Decision

1. **Size.** After the strategy is chosen and only when it is REST: a file larger than 2 GiB is sent to
   the vendor tools when the configuration names a local installation, and planning is refused
   (`IllegalArgumentException`, exit 2) when it does not, saying the tools are not available here and
   that `--strategy rest` uploads it anyway. The plan warns in both cases and names the size.
2. **A forced `--strategy rest` is respected.** The issue would have refused it. The vendor's word is
   "does not recommend", not "cannot", and an operator who forced the strategy on purpose (a fast
   network, a server with a raised limit) should not need a second override. The plan carries a
   warning instead. This is the safer reading of an ambiguity: nothing runs that the operator did not
   ask for, and nothing the operator asked for is taken away.
3. **Themes.** When both versions parse and their majors differ, `skipThemes` is on unless `--themes`
   was given; the plan warns and names both versions. Without a sidecar, or with a version that does
   not parse, nothing is defaulted (there is nothing to judge). The default is computed at planning
   from the sidecar, so `runs recover` rebuilds the same plan; the stored arguments carry the
   operator's own switches (`skipThemes`, `keepThemes`), not the computed result.
4. `--themes` with `--skip-themes` is a usage error (exit 1).

## Consequences

- An import of a 9.x archive into 10.x no longer brings the old themes along silently; the operator
  who wants them passes `--themes`. The pre-import snapshot and rollback are unchanged.
- The 2 GB limit is a constant (`DefaultExportImportOperations.REST_UPLOAD_LIMIT`); tests lower it
  through a package-private constructor rather than creating a 2 GB file.
- Not verified against a real server: the limit is the vendor's stated recommendation, not something
  measured here.
