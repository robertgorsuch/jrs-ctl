# ADR-0031: the import rollback deletes what the failed import created

Date: 2026-09-19. Status: accepted. Field test 2 finding I3 (`docs/reviews/2026-09-18-field-test-2-review.md`), issue #100.

## Context

A failed import was rolled back by re-importing the pre-import snapshot with `update`. That puts
back every resource the import overwrote, but a resource the archive *added* has no counterpart in
the snapshot and stayed behind. Spec §9.4, the plan summary, the operator guide and the recovery
runbook all said so, and the guide gave a recipe for finding the additions by hand (compare the
repository with the archive's `index.xml`). The tester's point stood: a run that ends "rolled back,
exit 3" should leave the repository as it was.

The REST API can answer the question directly. `GET /rest_v2/resources?folderUri=<f>&recursive=true`
lists every resource and folder under a folder, paged with `limit`, `offset` and a `Total-Count`
header (the maintainer's 10.0.0 server answers 172 entries for `/public` that way). What existed
before the import, listed just before it, against what exists after the failure, is exactly the set
the import created.

## Decision

1. A read-only backup step, `backup.pre-import-listing` (`RecordRepositoryListing`), runs right
   after the snapshot announcement and before a vendor snapshot stops the service. It lists every URI
   under the folders the snapshot covers (the sidecar's folders, `/organizations/<id>` for an
   organisation import, `/` for a full-server import) through the new read-only
   `JrsAdapter.listTree` and writes them, sorted, to `runs/<runId>/pre-import-listing.txt`. The
   file lives with the run, so `runs recover` rebuilds the same plan and retention prunes it with
   the run. A server that cannot be listed fails the step, so the run stops in the backup phase
   before anything is imported: an import whose rollback would silently be the old, weaker one is
   what the field test complained of.
2. The import-phase rollback anchor's compensation first deletes the additions, the URIs under
   those folders that are there now and were not in the listing, deepest first so a created folder
   goes after its children, one `DELETE /rest_v2/resources<uri>` each; then it re-imports the
   snapshot with `update` as before. A resource that cannot be deleted is logged and the rest still
   go; a rollback that cannot list the server, or finds no listing, leaves the additions behind and
   says so in a warning naming the folders. Deleting twice finds nothing new, so a repeated
   compensation is idempotent.
3. The plan summary's rollback sentence says what is deleted and that anything anyone else creates
   under those folders between the listing and the failure is deleted with them. The run lock keeps
   jrsctl itself out of that window; another operator or a scheduled job is not kept out, which is
   why the listing is taken as late as the strategy allows and the sentence is in the summary.
4. `listTree` is paged on REST (`limit=500`, following `Total-Count`) and a breadth-first walk of
   `listFolder` by default, which the fakes and any other adapter get for free.

The deletion is the compensation itself, not a mutation with a compensation of its own, so the
anchor step keeps its `mutating() == true` rollback-anchor contract and is not `irreversible()`: the
snapshot re-import that follows is what restores the state the operator confirmed.

## Alternatives

- **Keep the best-effort rollback and the recipe.** Rejected: it left the one case the tester hit
  as a hand procedure, and the recipe depended on reading `index.xml` and the run log by eye.
- **Diff the archive's `index.xml` against the snapshot.** Rejected: the archive's index names what
  the import *wanted* to create, not what it managed to before it failed, and the vendor path has
  no parsed index at all; the server's own listing is the only true record.
- **Delete the whole subtree and re-import the snapshot.** Rejected: a full-server import would
  delete every resource on the server, and the snapshot does not hold what the import never
  touched when the import targets a subtree of what was snapshotted.

## Consequences

- One recursive listing per import, before it starts. On a full-server import into a large
  repository that is a few hundred pages; it is bounded by `Total-Count` and streamed.
- The vendor strategy's rollback lists through REST after the service is back up (the stop step's
  compensation runs first), so a server that does not come back leaves the additions behind with the
  warning, as before this ADR.
- A field test of a failed import against a real server is still owed; the behaviour is proven with
  the fake adapter, the real Runner and WireMock paging.
