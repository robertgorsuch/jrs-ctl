# Console payload records

Design, 2026-09-12. Status: implemented.

## Problem

Every `/api/*` document the console serves is assembled by hand from `LinkedHashMap`. Six
builders across four classes put string keys into maps: `ConsoleViews.health` and
`ConsoleViews.doctor`, `ServerViews.server`, `RunViews.planResponse`, `RunViews.runList`,
`RunViews.runDetail` and `HotfixViews.hotfixes`. Nothing in the compiler or the test suite
relates those keys to the contract published in `app/src/main/resources/web/README.md`, and
no JSON Schema covers the console API at all. The 26 schemas under `schema/json/` describe
CLI `--json` output; `console.schema.json` describes only the token URL that
`jrsctl console` prints.

The result is drift that has already happened. The README's `/api/server` example omits
`reachable`, which the code emits. It documents `database.version` as a real version string
when the code hard-codes the empty string. Its `/api/plan` example shows `database` and
`requires` summary keys that no code path produces.

## Goal

Replace the maps with immutable records, publish a JSON Schema for each endpoint, and prove
by test that the records, the schemas and the README agree. The JSON on the wire must not
change, because the front-end in `web/app.js` reads it as it is today.

## Non-goals

Nothing about the front-end behaviour, the endpoint set, authentication, or the SSE event
stream changes. `SseEvents` serialises `core.event.Event`, which is already a sealed record
hierarchy, and is out of scope.

## Where the records live

One file per endpoint document, in the existing `com.jaspersoft.jrsctl.app.console` package,
package-private, with nested records for nested objects. This matches the one-area-per-file
rule the view classes already follow (roadmap item 17), so each view class gains exactly one
payload file it owns.

| File | Records |
|---|---|
| `HealthDoc.java` | `HealthDoc`, `Tool`, `LastRun`, `Lock`, `PendingRun`, `Snapshots`, `DoctorSummary`, `Attention` |
| `ServerDoc.java` | `ServerDoc`, `Database`, `Service`, `Keystore` |
| `PlanDoc.java` | `PlanDoc`, `PlanBody`, `Summary`, `PlanStep` |
| `RunsDoc.java` | `RunList`, `RunItem`, `RunDetail`, `StepRow`, `Failure` |
| `DoctorDoc.java` | `DoctorDoc`, `Counts`, `Item` |
| `HotfixesDoc.java` | `HotfixesDoc`, `Row` |

The two alternatives were rejected. A single `ConsolePayloads` class holding all 26 nested
records puts the whole contract on one screen but produces a 300-line file that no view owns.
A flat `console.payload` subpackage of 26 top-level files forces every type public for no
gain.

No caller changes. `ConsoleApi.json` takes `Object`, `SupportBundle` calls
`Json.writePretty(Object)`, and the shared mapper in `core.json.Json` already registers
`JavaTimeModule` and `Jdk8Module`, so records, `Instant` and `Optional` serialise correctly
with no configuration change.

## Component shapes

Component order reproduces the current `put` order exactly, which is what preserves key order
on the wire.

```
HealthDoc(Tool tool, String bind, String networkMode, Optional<LastRun> lastRun,
          Lock lock, List<PendingRun> pendingRuns, Snapshots snapshots,
          Optional<DoctorSummary> doctor)
  Tool(String version, String matrixVersion)
  LastRun(String id, String op, String outcome, Optional<Instant> finishedAt)
  Lock(boolean held, Optional<String> runId, Optional<String> pid)
  PendingRun(String id, String op, Instant startedAt, Optional<String> stepId)
  Snapshots(int count, long bytes, int retentionDays)
  DoctorSummary(int pass, int warn, int fail, Instant ranAt, List<Attention> attention)
  Attention(String status, String title, String detail)

DoctorDoc(Instant ranAt, Counts counts, List<Item> items, int exitCode)
  Counts(int pass, int warn, int fail, int skip)
  Item(String id, String name, String status, String title, String detail, String remediation)

ServerDoc(String product, String version, String edition, String tenancy, Database database,
          String baseUrl, String installDir, Service service, Keystore keystore,
          String networkMode, boolean reachable)
  Database(String vendor, String version)
  Service(String kind, String name, String state)
  Keystore(boolean present, String user)

PlanDoc(String planId, PlanBody plan)
  PlanBody(String op, String title, String fingerprint, Instant validUntil,
           Summary summary, List<PlanStep> steps)
  Summary(String filesTouched, Optional<String> resourcesTouched, String service,
          String backups, String rollbackPoints, String strategy, String downtime,
          List<String> warnings)
  PlanStep(String id, String phase, String title, String why)

RunList(List<RunItem> runs)
RunItem(String id, String op, String target, Instant startedAt, Optional<Instant> finishedAt,
        long durationMs, String outcome, boolean rollbackAvailable,
        boolean supportBundleAvailable)
RunDetail(String id, String op, String target, Instant startedAt, Optional<Instant> finishedAt,
          long durationMs, String outcome, boolean rollbackAvailable,
          boolean supportBundleAvailable, String title, String subtitle, String backups,
          List<StepRow> steps, Optional<Failure> failure)
  StepRow(String id, String phase, String title, String why, String status,
          Optional<Long> durationMs)
  Failure(String stepId, String cause, List<String> backups, String nextAction)

HotfixesDoc(List<Row> hotfixes)
  Row(String id, String title, Instant installedAt, int files, String state,
      List<String> blockedBy)
```

`ReportItem.detail` and `ReportItem.remediation` are plain `String`, so `Item` and `Attention`
need no `Optional`.

## Keeping the wire byte-identical

The current builders are not uniform: some keys are omitted entirely and some are emitted as
`null`. The distinction is visible to the front-end and must survive.

| Key | Today | Mechanism |
|---|---|---|
| `health.doctor` | absent when no doctor run is cached | `Optional` + `@JsonInclude(NON_ABSENT)` |
| `health.lock.runId`, `health.lock.pid` | absent unless the lock is held | `Optional` + `@JsonInclude(NON_ABSENT)` |
| `health.lastRun` | present, `null` when no run has finished | `Optional`, default inclusion |
| `health.lastRun.finishedAt` | present, `null` while unfinished | `Optional`, default inclusion |
| `health.pendingRuns[].stepId` | present, `null` when no transition yet | `Optional`, default inclusion |
| `runs/{id}.failure` | absent when the run succeeded or is running | `Optional` + `@JsonInclude(NON_ABSENT)` |
| `runs[].finishedAt` | present, `null` while running | `Optional`, default inclusion |
| `plan.summary.resourcesTouched` | absent when the list is empty | `Optional` + `@JsonInclude(NON_ABSENT)` |
| step `durationMs` | present, `null` when unknown | `Optional<Long>`, default inclusion |

`Jdk8Module` renders an empty `Optional` as `null` under default inclusion and omits the key
under `NON_ABSENT`, which is exactly the two behaviours needed. Using `Optional` everywhere
also satisfies the project rule that public APIs return no `null`.

## Two structural problems

**`runDetail` is built on `runItem`.** Today `RunViews.runDetail` calls `runItem`, then adds
five keys to the map it returns. Records cannot inherit, and `@JsonUnwrapped` is not reliable
on record components. `RunDetail` therefore declares the nine base components followed by its
own five, and is constructed by a static `RunDetail.of(RunItem base, String title, String
subtitle, String backups, List<StepRow> steps, Optional<Failure> failure)`. The nine names are
written twice, in exchange for a guaranteed key order and no Jackson magic.

**`plan.summary` reads as open-ended.** The README says unknown string keys are shown too, but
that describes the front-end's tolerance, not the server's output: the code emits a fixed set
of eight keys. `Summary` is a record with those keys and the schema uses
`additionalProperties: false`. If the summary should become genuinely open later, that is a
deliberate change to both the record and the schema.

## Schemas

Eight files under `app/src/main/resources/schema/json/`, draft 2020-12, `$id` under the
existing `https://jaspersoft.com/jrsctl/` prefix so `JsonSchemas.resourcePath` resolves
cross-references from the jar with no network access.

| File | Endpoint |
|---|---|
| `api-health.schema.json` | `GET /api/health` |
| `api-server.schema.json` | `GET /api/server` |
| `api-plan.schema.json` | `POST /api/plan` |
| `api-runs.schema.json` | `GET /api/runs` |
| `api-runs-show.schema.json` | `GET /api/runs/{id}` |
| `api-doctor.schema.json` | `GET /api/doctor` |
| `api-hotfixes.schema.json` | `GET /api/hotfixes` |
| `api-run-started.schema.json` | `POST /api/run`, `/api/runs/{id}/rollback`, `/api/runs/{id}/resume` |

`api-runs.schema.json` and `api-runs-show.schema.json` share the run item through a `$ref`, in
the way `runs-show.schema.json` already refs `run.schema.json`.

`JsonSchemas` gains a second map keyed by endpoint path alongside `BY_COMMAND`, and
`JsonSchemas.schemas()` includes its values. That pulls the new files into the existing
loads-and-parses assertion in `JsonOutputSchemaTest` at no extra cost.

The `networknt` validator is already a compile-scope dependency of `core` and is therefore on
the `app` test classpath. No new dependency, so no decision record is required under spec
§13.3.

## Testing

Test-first, in this order. Each step is a commit.

1. **Golden files, while the views are still maps.** A new `ConsoleWireGoldenTest` drives the
   existing console fixtures and writes each of the eight documents to
   `app/src/test/resources/console-wire/`. Volatile values are normalised before comparison:
   run and plan ids, instants, absolute paths, durations and byte counts. Committing the
   goldens produced by the current map code is the safety net for everything that follows.
2. **Schemas and their test.** Add the eight schema files and `ConsoleSchemaTest`, validating
   every golden against its schema. Red until the schemas match reality, which is the point of
   writing them second.
3. **Migrate one area at a time**, easiest first: health and doctor, then server, then
   hotfixes, then runs and plan. A golden that moves is a bug in the migration, not a golden to
   update.
4. **Validate live responses.** `ConsoleServerTest` gains schema validation on every `/api/*`
   response it already fetches, so the guarantee holds against the running server and not only
   against fixtures.

Existing assertions in `ConsoleServerTest`, `ConsoleHardeningTest` and `Phase6ConsoleTest` read
the responses as `JsonNode` over HTTP. They are wire-level, so they should pass unchanged
throughout. Any one of them that fails is evidence the wire moved.

## README corrections

`web/README.md` is the published contract, so the schemas cannot agree with it while it is
wrong. Three fixes ship with this work:

- `/api/server` gains `reachable` in its example.
- `/api/server` notes that `database.version` is always the empty string today.
- The `/api/plan` example drops `database` and `requires`, which nothing emits, and the note
  about unknown summary keys is reworded to describe front-end tolerance rather than server
  output.

## Risks

The golden files are the whole safety net, so they must cover the interesting branches, not
just the happy path: a held lock, a missing doctor cache, a failed run with a `failure` block,
a running run with null `finishedAt`, a plan with and without `resourcesTouched`, and a hotfix
with a non-empty `blockedBy`. If a branch has no golden, the migration is unguarded there.

The `app` module has a Jacoco line floor. Records add generated accessors that tests may not
call, which can move the measured number. Watch it on the first full `verify` rather than at
the end.

## As built

`api-runs.schema.json` and `api-runs-show.schema.json` do not share the run item through a
`$ref`, as planned above. `additionalProperties: false` does not compose through `$ref`: a
schema that referenced the other's run-item definition and then added its own five properties
would let through any property either schema recognises, not just its own. Each schema instead
repeats the nine base fields as its own `properties`/`required` list, and the two share only the
`outcome` enum through `$ref`.

Four README fixes shipped, not three: the three planned server and plan corrections, plus a
fourth that corrected `/api/health`'s `networkMode` note to `isolated` and `public` (the two
values the config schema actually allows). A fifth correction, to `GET /api/doctor`, shipped in
the fix wave after the initial review: the README described `counts: {pass, warn, fail}` and
`items: [{id, status, title, detail, remediation}]`, but the code has always emitted
`counts.skip`, `items[].name` and a top-level `exitCode`, and allows `status: SKIP`.

18 golden files shipped, not the eight endpoints originally scoped, because more than one golden
was needed per endpoint to cover the branches under Risks, and two are structural rather than
value-exact: `doctor` and the doctor-cached branch of `health` probe the real host and are
captured as type tokens instead. The extra value-exact goldens cover a held run lock
(`health-run-in-flight`, `runs-show-running`), a pending run with no transitions yet
(`health-pending-no-steps`), a pending run read back as `interrupted`
(`runs-show-interrupted`), a reachable server (`server-reachable`), and an import plan
(`plan-import`).

The harness normalises more than instants and ids: every backslash in a string value is turned
into a forward slash before any other scrubbing runs, and `ConsoleWireGoldenTest` also normalises
`\r\n` to `\n` on both sides of the golden comparison, so goldens compare equal on either OS and
line-ending convention. A guard test,
`should_contain_no_backslash_when_any_golden_is_read`, fails the build if a committed golden
ever regains a raw backslash.

Schema validation covers both value-exact goldens and live responses: `ConsoleSchemaTest`
validates every value-exact golden against its endpoint's schema (and separately asserts that
its golden list matches the golden directory, so a new golden cannot go unvalidated by
omission), and `ConsoleServerTest` validates live responses from a running console for every
endpoint, including the two structural ones and `POST /api/runs/{id}/rollback` and
`POST /api/runs/{id}/resume`, which share `api-run-started.schema.json` with `POST /api/run`.
