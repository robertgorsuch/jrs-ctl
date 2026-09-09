# Phase 0 acceptance — Skeleton

Run: `scripts\mvn.cmd verify -Dphase=0` (from the repo root; the acceptance module runs last in the reactor).

Criteria (spec §14, Phase 0), each backed by a test in `Phase0SkeletonTest`:

- Multi-module Maven build compiles with `-Werror` and Error Prone.
- `CLAUDE.md`, `docs/spec.md`, `docs/spec-changelog.md`, `docs/BUILD_STATUS.md`, `docs/decisions/` exist.
- `jrsctl --version` prints the product, version and vendor.
- `jrsctl selfcheck` passes on the packaged jar and has a `--json` form.
- CI workflow runs on Windows and Linux runners.
