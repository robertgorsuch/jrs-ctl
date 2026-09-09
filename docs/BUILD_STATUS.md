# jrsctl build status

Maintained by the build agent. Updated at the end of every phase.

| Phase | Name | Status | Acceptance |
|---|---|---|---|
| 0 | Skeleton | done | `Phase0SkeletonTest` |
| 1 | Core + Engine | done | `Phase1CoreEngineTest` + 213 core unit tests |
| 2 | Adapter + Init + Doctor | done | `Phase2AdapterDoctorTest` + 75 jrs, 27 ops, 17 app unit tests |
| 3 | Hotfix | done | `Phase3HotfixTest` (7 cases against the packaged jar) + 52 ops, 31 app unit tests |
| 4 | Export/Import | in progress (jrs strategies merged, ops/app in flight) | |
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
- The console front-end (`app/src/main/resources/web`) is built and exercised only in mock mode until Phase 6 serves the endpoints it assumes (contract in `web/README.md`).
- The bundled Jaspersoft publisher key file holds only comments until CI injects the real key; until then only customer keys added with `jrsctl keys add` can verify bundles.
- `doctor`'s database check loads the JDBC driver from `database.driverDir` or buildomatic's bundled driver directory; there is no driver on the build machine, so it is exercised only up to the "no driver directory" failure in unit tests.
