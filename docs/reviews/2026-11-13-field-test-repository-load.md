# Field test of `repository load` on a real 10.0.0 buildomatic and PostgreSQL (#311)

Date: 2026-11-13. Server: JasperReports Server 10.0.0 PRO, build 20260730_0457, bundled PostgreSQL, Windows, on the
development machine. Release under test: jrsctl 2.7.1, then a local build of `main` with PRs #320 to #323. Gate:
ADR-0052 ("a field test on a copy of a real repository is the gate before the command leaves the release notes'
'verified only against fake vendor scripts' list").

The live databases (`jasperserver`, `foodmart`, `sugarcrm`) were never touched. Every rollback that ran dropped only
the schema of its own run.

## Setup

- A copy of the installation's buildomatic (with its `apache-ant` and `java` siblings) in a scratch folder, with
  `js.dbName` set to a throwaway schema (`jrsctl_ft311`, later `jrsctl_ft311b` and `jrsctl_ft311c`) and
  `appServerType=skipAppServerCheck`.
- `jrsctl init --tools-only --remote ... --buildomatic-dir <copy>`.
- The archive: the vendor's `js-export --everything` from the running server, then `jrsctl export record`.
- Two test jobs on the source, one active and one paused, both first due in 2030.

## First run, jrsctl 2.7.1

| Step | Outcome |
|---|---|
| Export and `export record` | OK. The archive carries the calendar and both jobs, with their `<paused>` flags. |
| Refusals | `js.dbName` different from `--schema`: exit 2, nothing created. An existing schema (a dummy database made for the test): exit 2, nothing created. The source-database collision (exit 6) did not fire in a tools-only layout (#317). |
| Real runs | 1st: `import-minimal-pro` failed on a stale `iecp.jar` left by a hotfix (#315). 2nd: `js-import` failed under a Git Bash `PATH` (#318). 3rd: `js-import` failed on the key alias taken from `index.xml` (#312). Each rollback dropped its schema. 4th, with `--key-alias importExportEncSecret`: **all 10 steps OK** (create 7 s, init 7 s, minimal 52 s, `js-import` 57 s). |
| Content | Source against the loaded schema: resources 2376 = 2376, users 6 = 6, jobs 2 = 2, calendars 1 = 1; folders 671 against 670. The missing folder is a broken leftover from an earlier test, which the vendor export skips. |
| Job state | **Preserved**: in the loaded schema's `qrtz_triggers`, the active job is `WAITING` and the paused one `PAUSED`. This answers #304's question for 10.0 to 10.0; 8.2 to 10.0 is not tested. |
| Server on the new schema | The real Tomcat was pointed at `jrsctl_ft311` for the test (four JNDI URLs in `context.xml`, restored afterwards, hash checked). `doctor` exit 0; `smoke` 6/6 PASS (login, repository, report to PDF, scheduler, export round trip). `import verify`: organisations, users and roles PASS; resources FAIL with 164 "missing", 163 of them false positives from the vendor's `-002d` path escaping (#314) and 1 the skipped leftover folder. |
| Passwords | The loaded schema keeps the minimal import's default `superuser` and `jasperadmin` passwords, as the plan says. A first round of checks with the source's password locked `superuser` (10 failed logins disable it) (#313). |
| Crash | jrsctl and its vendor children were killed during `js-import`. The next command exited 8 naming the run; `runs recover <id> --rollback` dropped exactly that run's schema (`jrsctl_ft311b`). The earlier loaded schema and the live databases were untouched. |

## Re-run with the fixes

Same machine, archive and buildomatic copy; a local build of `main` with PRs #320 to #323; new throwaway schema
`jrsctl_ft311c`, dropped afterwards.

| Fix | Field result |
|---|---|
| #315 (PR #323, ADR-0054) | With the hotfix-era `iecp.jar` put back, `repository load` stopped at `verify-buildomatic` with exit 2 and created nothing: "iecp.jar is stale: 123 of its 511 class-path entries are no longer in …lib". After the file was deleted as the message says, buildomatic rebuilt it. |
| #312 (PR #320) | The load ran **without** `--key-alias`: no `--keyalias` passed, all 10 steps OK (create 9 s, init 8 s, minimal 78 s, `js-import` 59 s). |
| #314 (PR #321) | `import verify` of the archive against the live server: organisations, users and roles PASS; **resources: all 1235 found on the server** (164 "missing" before). |
| #313 (PR #322) | `doctor` and `smoke` with a wrong password, for a user that does not exist so nothing could be locked: Tomcat's access log shows **one** 401 per command (2.7.1 sent five for one `smoke`). Later items report "not sent: the server already refused these credentials in this jrsctl run …". |

## Follow-ups

| Issue | Fix | Field-tested |
|---|---|---|
| #312 key alias from `index.xml` | PR #320 | yes, in the re-run |
| #313 refused credential resent | PR #322 | yes, in the re-run |
| #314 `-002d` escaping in `import verify` | PR #321 | yes, in the re-run |
| #315 stale `iecp.jar` | PR #323 (ADR-0054) | yes, in the re-run |
| #316 `schema.created` before the absence re-check | PR #324 (ADR-0055) | no: fake vendor scripts and a fake JDBC catalogue |
| #317 narrow source-database check | PR #325 | no |
| #318 Unix `find.exe` before System32 | PR #326 | no: the re-run was started from PowerShell with System32 first on `PATH` |
| #319 smaller items | PR #327 | no |

All eight shipped in v2.7.2. The v2.7.2 release notes and `docs/BUILD_STATUS.md` state the result: `repository load`
has run against a live server on this one machine only.

## Not covered

An 8.2 source; Linux; a separate database server; Oracle, MySQL or SQL Server; `--include-events`; the fixes #316 to
#319 in the field. A repeat of this test on an 8.2 source, or on Linux, is what would widen the release notes'
statement.
