# ADR-0060: import verify --content compares file payloads and key descriptor fields

Status: accepted · Date: 2026-11-15 · Spec: §9.4 · Issue #307

## Context

`import verify` (#262) compares by id: it says what an import left out, not whether what arrived is identical. A customer review (November 2026) noted that limit. Comparing whole descriptors is wrong, because an import rewrites part of every one: versions, creation and update times, permissions, and data-source passwords, which the server keeps encrypted and REST never returns.

## What was checked against a real server

On the development machine's 10.0.0 PRO server, read-only, against a REST export of `/public/Samples` (113 top-level resources, 494 file payloads):

- A file's content is the archive entry `resources/<path>.data`. A top-level file's sits next to its descriptor (`<id>.data`, named by the descriptor's `dataFile` attribute), and a report unit's own files sit under `<unit>_files/<name>.data`. The server returns each at `GET /rest_v2/resources/<path>` with any `Accept` that is not a descriptor type. All 494 came back byte for byte.
- `GET /rest_v2/resources/<uri>?expanded=false` with `Accept: application/json` returns the descriptor of every type except a file, which needs `application/repository.file+json` and otherwise returns the content. An empty description is left out of the JSON.
- REST does not list an Ad Hoc view's input controls, and gives some types' `dataSource` in another shape, so comparing those fields for every type reported false differences. Limited to report units, the rules below reported none across the 113 resources.

## Decision

- `import verify <archive> --content [--uri-prefix <folder>]` adds a **`content`** item in the `doctor` layout.
- **Files:** every `resources/<path>.data` entry is hashed as it streams from the archive and compared by SHA-256 with the server's copy of `/<path>`. The copy is downloaded to a temporary file in the jrsctl home and deleted right after, never held in memory.
- **Descriptors:** for every other top-level resource (not a folder, not a resource local to another one), **label** and **description**; for a **report unit**, also the **data source** URI and the set of **input control** URIs (a reference or a local resource's folder plus name). Never compared: version, creation and update times, permission mask, data-source passwords and connection details, and every other field. Names are decoded from the archive's escaping the way the `resources` item decodes them (#314).
- **Outcomes per URI:** `content differs`, `not on the server` (404), `label`, `description`, `data source`, `input controls`, or `could not be read: <reason>`. The item fails naming the first few; the JSON report's `content` lists them all with `--uris`. A server that cannot be reached fails the item. A refusal on one resource is recorded for that resource, and the comparison continues.
- **Rate:** one request at a time, in URI order. `--uri-prefix` limits the comparison to one folder, since a full comparison downloads every file. `--uri-prefix` without `--content` is a usage error (exit 1).
- Without `--content`, `import verify` is unchanged.

## Consequences

- An operator can show that an import is identical in content, not only complete.
- "Identical" means the fields above. A change in an excluded field (for example a data source's connection URL) is not reported. The operator guide says so.
- A full comparison of a large repository downloads every file once; `--uri-prefix` is the way to scope it.
- Verified live on one 10.0.0 server (Windows, PostgreSQL) with a 10.0.0 export; other versions are not tested.
