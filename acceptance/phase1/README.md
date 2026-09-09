# Phase 1 acceptance — Core + Engine

Run: `scripts\mvn.cmd verify -Dphase=1`.

Criteria (spec §14, Phase 1). Unit suites in `core` cover the detail; `Phase1CoreEngineTest` proves the integrated engine on a real state store:

- Config precedence flag > env > file > default, schema validation, `enc:`/`file:`/`env:` secrets with non-interactive passphrase — `core.config`, `core.secrets` tests.
- Platform detection, every `service.kind` controller against fakes, atomic replace with permissions, lock detection, streaming SHA-256 of a >2 GB sparse file under the default heap — `core.platform` tests.
- State store migrations, append-only triggers, transactional transitions — `core.state.StateStoreTest`.
- Run lock refuses a second holder and names the first (in-process here; cross-process in Phase 3).
- Snapshot create/verify/restore with permissions; prune with protected runs — `core.snapshot`.
- Redaction property test over raw, Base64 and URL-encoded forms — `core.redact`.
- Fake 5-step plan with a forced failure at step 4 rolls back 3, 2, 1 and exits 3; compensation failure yields exit 4; cancellation compensates; fingerprint mismatch refuses — `core.engine.RunnerTest` and this suite.
