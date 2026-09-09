# Phase 6 acceptance — Console

Run: `scripts\mvn.cmd verify -Dphase=6`.

Criteria (spec §14, Phase 6). The unit suite in `app` (`ConsoleServerTest`) covers the API behind a real listener on a free port: 401 without the token and 421 on a foreign `Host`, the README shapes of `/api/health`, `/api/server` (reachable and unreachable), `/api/doctor` and `/api/hotfixes`, `POST /api/plan` for `hotfix.verify` (planId, 30-minute TTL) and 501 for an operation this build does not implement, `POST /api/run` executing a fake hotfix plan with the event stream replaying the journal and then streaming to `RunSucceeded`, 409 on a changed fingerprint and 410 on an expired plan, cancel mid-run ending in `RunCancelled` with compensation, rollback of a recorded hotfix as a new `hotfix.rollback` run, resume of an interrupted run from its interrupted step, a support bundle with the expected entries and no registered secret in any encoding, refusal of a non-loopback bind without TLS, the owner-only token file deleted on close, and TLS from PEM material. `Phase6ConsoleTest` proves the packaged jar end to end:

1. `jrsctl console --home <home> --port <free port> --no-open` is started as a background process against a fake Tomcat layout and a WireMock JasperReports Server 8.2.0 PRO; `<home>/console.token` appears with the per-launch token and the same token is printed once as `Console: http://127.0.0.1:<port>/#token=<token>`.
2. `GET /` is 200 HTML containing `jrsctl` without any token (the static UI is public; only `/api/*` is gated).
3. `GET /api/health` without a token is 401; with `Authorization: Bearer <token>` it is 200 and `tool.version` equals the build's version.
4. `GET /api/server` reports version `8.2.0` and edition `PRO` from the adapter.
5. `GET /api/doctor` returns a report with items and counts; `GET /api/runs` returns `{"runs": []}` on the fresh home.
6. `stop` on the process's standard input stops the console cleanly (exit 0) and the token file is gone; the same shutdown hook runs on Ctrl-C.

Not covered here: the headless-browser rendering test of the UI runs in CI inside a Playwright container (`needs-docker`, see ADR-0007); TLS with a non-loopback bind and `console.auth.mode: local` (unit-tested for PEM loading and the refusal rule only); `console.auth.mode: local` (HTTP Basic with the operator password) beyond the unit-tested header parsing.
