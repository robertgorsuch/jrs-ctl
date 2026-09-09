# Phase 2 acceptance — Adapter + Detection + Doctor

Run: `scripts\mvn.cmd verify -Dphase=2`.

Criteria (spec §14, Phase 2). Unit suites in `jrs`, `ops` and `app` cover the detail; `Phase2AdapterDoctorTest` proves the packaged jar against a WireMock JasperReports Server:

- WireMock fixtures for `serverInfo` and the capability probes (`/rest_v2/resources`, `/rest_v2/jobs`, `/rest_v2/organizations`, `/rest_v2/export|import/<id>/state`, `/rest_v2/login`) — `jrs` tests across 7.x/8.x/9.x/10.x; this suite uses 8.2.0 PRO with features `Fusion AHD EXP DB AUD ANA MT`.
- Single adapter behaves correctly under each capability set; isolated-mode allowlist refuses a second host — `jrs` tests.
- `init` detects a fake layout on both OS naming conventions and writes a schema-valid config — `ops.init.InitOperationTest`, `app.InitCommandTest`, and `jrsctl init --yes --non-interactive --home <tmp> --install-dir <fake>` here (asserts `webappName: jasperserver-pro`, no password copied from `default_master.properties`, log file under `<home>/logs`).
- `doctor` produces a full report: every check name present, sorted FAIL/WARN/PASS/SKIP, remediation on every non-PASS item; unreachable server → `server` FAIL and every server-dependent check SKIP, exit 2; unsupported version → `compat` FAIL, exit 6, or WARN plus an audit row with `--allow-unsupported` — `ops.doctor.DoctorOperationTest`, `app.DoctorCommandTest`, and `jrsctl doctor --home <tmp> --json` here (asserts `server`/`identity`/`auth` PASS, identity detail contains 8.2.0; only `compat`/`keystore` may FAIL on a machine without a real keystore).
- `smoke` passes with a fake PDF, WARNs (not FAILs) on a missing sample report, and `--mutating` runs the journaled create/upload/run/delete plan with rollback — `ops.smoke.SmokeOperationTest`.
- `@Tag("needs-jrs")` Testcontainers test exists and is stubbed if no image is available — `jrs` module (release gate, excluded here).
