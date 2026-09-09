# jrsctl build status

Maintained by the build agent. Updated at the end of every phase.

| Phase | Name | Status | Acceptance |
|---|---|---|---|
| 0 | Skeleton | done | `Phase0SkeletonTest` |
| 1 | Core + Engine | done | `Phase1CoreEngineTest` + 213 core unit tests |
| 2 | Adapter + Init + Doctor | in progress | |
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

- Work is committed on `main` rather than a branch per phase because this repository has no remote yet; the phase boundary is the commit, and the PR checklist in spec §15 applies once a remote exists.
- Cross-process run-lock contention is verified in Phase 3 (needs a mutating CLI command); Phase 1 verifies it in-process.
- The JSON log file defaults to the temp dir until the app wires `-Djrsctl.log.file` from `$JRSCTL_HOME` (Phase 2).
