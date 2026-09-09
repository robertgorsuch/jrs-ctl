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
| 8 | Hardening | done | `Phase8CrashRecoveryTest` (4), `Phase8RetentionTest` (4), `Phase8JsonSchemaTest` (5), `Phase8ExplainDocsTest` (6), all tagged `phase8`; + 81 ops/jrs idempotency tests, 20 retention tests, 70 JSON-schema tests, 13 explain/docs tests. Full `scripts\mvn.cmd verify` (all modules, all phases) green on 2026-09-09 in 8 min 12 s: jrs 139, ops 207, app 178, acceptance 50 |

## Phase 8 in detail

- **Idempotency.** `ops/.../IdempotencyCoverageTest` scans the classpath for every concrete `Step` in `ops` and `jrs` (58 today) and fails if one is missing from its step-to-test table. Every mutating step has an execute-twice test, every compensating step a compensate-twice test; `AtomicSwap`, `DownloadExport`, `RunJsExport` and `PlanCustomizationReapply` also have partial-execution cases. Two genuine bugs were found and fixed: `RecordUpgrade` re-executed after a crash overwrote its `superseded` marker with an empty list, and `MasterProperties.restore()` deleted the restored original on a second call.
- **Crash injection.** `Phase8CrashRecoveryTest` runs `export --strategy vendor` from the jar against a fake layout whose `js-export` blocks on a sentinel, kills the process with the literal `taskkill /F /PID` (Windows) or `kill -9` (Linux), then proves: the run is pending and a `--yes` mutating command exits 8 printing the exact `runs recover` command; the OS lock is free; `runs recover --resume` exits 0 with an end state identical (hashes, sidecar, service state, journal) to a clean run; `runs recover --rollback` exits 3 and restores the pristine fixture with the service running; a second kill during recovery still converges. Executed on Windows; the Linux branch is written but not run locally.
- **Retention.** `jrsctl runs prune [--dry-run]` and automatic best-effort pruning after every successful run (`ops.retention`). Protected: snapshots of installed hotfixes, registered customizations, the most recent successful upgrade only, and runs pending recovery. Rows and directories are removed together; one audit row per pass.
- **`--json` everywhere.** All 30 leaf commands emit one JSON document (or, for plan-running commands, a strict JSONL stream: plan line, one `Event` per line, then `{"outcome":…}`); every refusal and usage error with `--json` emits `{"error":{"class","message","exitCode",…}}`. 26 JSON Schemas (draft 2020-12) ship in the jar under `schema/json/`; `JsonOutputSchemaTest` enumerates the picocli tree and fails if a command lacks a schema or a validated scenario.
- **Offline docs and `--explain`.** `README.md`, `docs/operator-guide.md`, `docs/security.md` and `docs/hotfix-authoring.md` are copied into the jar at build time; `jrsctl docs [<name>]` lists or prints them; `jrsctl help <command>` is picocli's `HelpCommand`; `--explain` is added to every `CommandSpec` programmatically and prints that command's operator-guide section without running anything. `ExplainTest` fails if a command has no guide section with Mutates / Rollback / Exit codes / Flags.

## Verified against a real server

`init` and `doctor` were run from the packaged jar against the JasperReports Server 10.0.0 PRO installation on the build machine (`C:\Jaspersoft\jasperreports-server-10.0.0`, Windows service `jasperreportsTomcat`, port 8081). `init` detected the layout, port, webapp, service, database type and bundled Java 17; `doctor` passed 18 of 21 checks (server, auth, identity 10.0.0 PRO MULTI, compat, layout, service, permissions, disk, keystore, vendor tools, vendor Java); the remaining items were the operator's database credentials and the REST-login probe, which has since been fixed. No mutating command has been run against it.

## Environment facts

- Development machine: Windows 11, JDK 21 (Microsoft build) selected by `scripts/mvn.cmd`, Maven 3.9.9. No Docker, so `needs-docker` and `needs-jrs` tests are excluded locally and run in CI only.
- Code signing and the Jaspersoft publisher key are CI-only (spec §0 rule 11); local artifacts are unsigned.

## Stubbed or deferred components

- `needs-jrs` Testcontainers suite: image not yet named (spec §19 Q6).
- Brand colour values: provisional, see `docs/branding.md`.
- Linux execution of `Phase8CrashRecoveryTest` and the Linux half of every fake-script fixture: written for both OSes, run only on Windows locally; CI's `ubuntu-latest` job is the gate.

## Known gaps

- Work is committed on `main` rather than a branch per phase because this repository has no remote yet; the phase boundary is the commit, and the PR checklist in spec §15 applies once a remote exists.
- The bundled Jaspersoft publisher key file holds only comments until CI injects the real key; until then only customer keys added with `jrsctl keys add` can verify bundles.
- `doctor`'s database check loads the JDBC driver from `database.driverDir` or buildomatic's bundled driver directory; there is no driver on the build machine, so it is exercised only up to the "no driver directory" failure in unit tests.
- Old upgrade set directories (`snapshots/<runId>/upgrade/` holding the webapp archive and full export) are not manifest snapshots and are never pruned; only the per-step snapshots of an unprotected upgrade run are. Follow-up if disk usage from old upgrades matters.
- Transient Windows file-system errors were each seen once during concurrent builds and passed on rerun: `HotfixRollbackTest` ("cannot swap … foo-1.2.3.jar") and `PointB.moveTree` (`AccessDeniedException` renaming the webapp directory). Neither is an idempotency defect; a bounded retry around rename/move on Windows is a candidate robustness change.
- Fourteen merged agent worktrees remain under `.claude/worktrees/`; they are gitignored scratch and can be removed with `git worktree remove`.
