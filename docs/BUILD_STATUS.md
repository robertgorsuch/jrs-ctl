# jrsctl build status

Maintained by the build agent. Updated at the end of every phase.

| Phase | Name | Status | Acceptance |
|---|---|---|---|
| 0 | Skeleton | done | `Phase0SkeletonTest` |
| 1 | Core + Engine | done | `Phase1CoreEngineTest` + 213 core unit tests |
| 2 | Adapter + Init + Doctor | done | `Phase2AdapterDoctorTest` + 75 jrs, 27 ops, 17 app unit tests |
| 3 | Hotfix | done | `Phase3HotfixTest` (7 cases against the packaged jar) + 52 ops, 31 app unit tests |
| 4 | Export/Import | done | `Phase4ExportImportTest` (4 cases) + 47 jrs, 15 ops, 18 app unit tests |
| 5 | Upgrade | done | `Phase5UpgradeTest` (5 cases with fake vendor scripts) + 38 ops unit tests |
| 6 | Console | done | `Phase6ConsoleTest` + 23 app unit tests; UI checked by hand in Chrome against the real backend |
| 7 | Distribution | done | `Phase7DistributionTest` (image manifest, `--version`/`selfcheck` with no JDK on PATH, unpacked archive); `-Pdist` |
| 8 | Hardening | in progress | |

## Verified against a real server

`init` and `doctor` were run from the packaged jar against the JasperReports Server 10.0.0 PRO installation on the build machine (`C:\Jaspersoft\jasperreports-server-10.0.0`, Windows service `jasperreportsTomcat`, port 8081). `init` detected the layout, port, webapp, service, database type and bundled Java 17; `doctor` passed 18 of 21 checks (server, auth, identity 10.0.0 PRO MULTI, compat, layout, service, permissions, disk, keystore, vendor tools, vendor Java); the remaining items were the operator's database credentials and the REST-login probe, which has since been fixed.

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
