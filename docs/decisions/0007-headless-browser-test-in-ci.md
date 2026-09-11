# ADR-0007: The console's headless-browser test runs in CI, not in the local build

## Status

Deferred (2026-09-10). Accepted in principle in Phase 6, but the CI job it describes has not been
written: the `integration` job in `ci.yml` selects tests tagged `needs-docker` and no test carries
that tag, so the job passes on zero tests (assessment item 5.2). Since 2026-09-11 the `release`
job no longer depends on `integration`: a green that proves nothing must not gate a release. The
job itself stays so that the first `needs-docker` test runs somewhere. Until the Playwright job
exists, the console is covered only by `ConsoleServerTest`, `Phase6ConsoleTest` and manual use of
the front-end's mock mode from the source tree. The decision below stands once the job is written,
at which point `integration` returns to `release.needs`.

## Context

Spec §14, Phase 6 requires that "the UI renders a run end-to-end in a headless browser test".
Driving a browser from JUnit needs a browser-automation dependency (Playwright for Java, Selenium
with a driver, or an HtmlUnit-class engine). None of these is on the approved list of spec §13.3,
and spec §0 rule 9 forbids a new third-party dependency without an ADR. The build machine has no
Docker (`docs/BUILD_STATUS.md`), so a container-provided browser is not available locally either.

The front-end already runs against an in-memory backend (`index.html?mock=1`, `mock.js`) that
simulates a complete run including a failure with rollback, and the Java side is covered at the HTTP
level: `ConsoleServerTest` drives every endpoint with `java.net.http`, including the SSE stream to
its terminal event, and `Phase6ConsoleTest` does the same against the packaged jar.

## Decision

- No browser-automation dependency is added to the Maven build.
- The headless-browser test lives in CI as a job tagged `needs-docker`, in the same class as the
  `needs-jrs` Testcontainers suite (spec §0 rule 10): it starts the shaded jar's `console`, runs a
  Playwright container (`mcr.microsoft.com/playwright`) against the printed URL, loads
  `#token=<token>`, builds and runs a hotfix plan through the UI, and asserts the step tree reaches
  `succeeded` and the outcome banner appears. It is a release gate, not a phase gate.
- The local build covers the API with HTTP tests (`ConsoleServerTest`, `Phase6ConsoleTest`) and the
  front-end with its mock mode (`WebAssetsTest` keeps the assets self-contained; the mock backend is
  the manual and CI smoke path for the views).

## Consequences

- `scripts\mvn.cmd verify -Dphase=6` stays free of browsers and Docker and runs anywhere the JDK
  runs.
- A regression that only a real browser would catch (DOM wiring, SSE parsing in `api.js`) is caught
  in CI, not on the developer machine; developers exercise the UI manually through `?mock=1` or a
  running console before pushing.
- If a browser-automation library is ever approved for the local build, this ADR is superseded and
  the CI job moves into the `acceptance` module under the `phase6` tag.
