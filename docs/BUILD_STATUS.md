# jrsctl build status

Maintained by the build agent. Updated at the end of every phase.

| Phase | Name | Status | Acceptance |
|---|---|---|---|
| 0 | Skeleton | in progress | `Phase0SkeletonTest` |
| 1 | Core + Engine | not started | |
| 2 | Adapter + Init + Doctor | not started | |
| 3 | Hotfix | not started | |
| 4 | Export/Import | not started | |
| 5 | Upgrade | not started | |
| 6 | Console | not started | |
| 7 | Distribution | not started | |
| 8 | Hardening | not started | |

## Environment facts

- Development machine: Windows 11, JDK 21 (Microsoft build) selected by `scripts/mvn.cmd`, Maven 3.9.9. No Docker, so `needs-docker` and `needs-jrs` tests are excluded locally and run in CI only.
- Code signing and the Jaspersoft publisher key are CI-only (spec §0 rule 11); local artifacts are unsigned.

## Stubbed or deferred components

- `needs-jrs` Testcontainers suite: image not yet named (spec §19 Q6).
- Brand colour values: provisional, see `docs/branding.md`.

## Known gaps

- None recorded yet.
