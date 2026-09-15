# ADR-0017: An import start the server may have accepted is fatal, not retried

Status: accepted, 2026-09-15. Amends spec §9.4 (import rollback). Issue #44.

## Context

`StartImport` streams the archive to `POST /rest_v2/import` and records the task id the server
returns in `import-handle.txt`, so a second execute reuses the task instead of uploading again. The
id was written only after the answer arrived. Every transient answer (408, 429, 502, 503, 504) and
an unreachable server made the step retryable, and the runner uploaded the archive again.

Two of those answers do not mean the request failed. A 502 or 504 comes from a proxy or gateway
that gave up waiting, and a connection that drops after the upload looks like an unreachable
server; in both cases JasperReports Server may already have accepted the archive and started an
import. REST v2 has no endpoint that lists running imports, so jrsctl cannot find out. The retry
then started a second server-side import of the same archive, and a later failure re-imported the
pre-import snapshot over imports that might still be running.

408, 429 and 503 are different: the server (or a proxy that never forwarded the body) refused the
request, so no import started.

## Decision

1. Before the POST, `StartImport` writes `import-started.txt` in the run directory.
2. A 408, 429 or 503 answer deletes the marker and stays retryable with the `Retry-After` delay.
3. A 502 or 504 answer, or an unreachable server, is a **fatal** failure. The marker stays.
4. An execute that finds the marker but no `import-handle.txt` is fatal too, so a retry, a resumed
   run or `runs recover --resume` never uploads the archive a second time.
5. Any other HTTP error means the server rejected the request: the marker is deleted and the error
   propagates as before.
6. The fatal failure names what happened and the next action: check the server log and the
   repository for an import started at that time, let it finish, verify the repository, and
   re-import by hand only if it did not run.

A fatal failure runs no compensation, so the pre-import snapshot is not re-imported over an import
that may still be running. This is the rule the import poll already follows when the server still
reports the task running after the poll timeout.

## Alternatives

- **Keep retrying every transient answer.** Rejected: it is the defect.
- **Retry an unreachable server when the connection was refused before any byte was sent.**
  Rejected for now: `JrsUnreachableException` does not say which phase of the exchange failed, and
  guessing wrong starts a second import. A refused connection while starting an import is rare,
  because the snapshot step talked to the server moments earlier.
- **Find the running import by listing tasks.** Not possible: REST v2 offers only
  `GET /rest_v2/import/{id}/state` for a known id.

## Consequences

- A gateway error or a dropped connection while starting an import ends the run with exit 4 and a
  next action, where it used to retry. The operator checks the server before importing again.
- `import-started.txt` is a run-scoped file like `import-handle.txt`; run directory retention
  (issue #53) removes it with its run.
