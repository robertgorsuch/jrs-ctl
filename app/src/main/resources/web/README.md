# jrsctl console (static front-end)

Vanilla ES2020 modules, one stylesheet, no build step, no external resources. The Java side
serves this directory from the JAR at `/` and the API at `/api/*` (spec §13).

| File | Role |
|---|---|
| `index.html` | Page shell: nav rail, top bar, `<main id="view">` |
| `brand.css` | Every colour and font token (Actian Jaspersoft); replace to rebrand |
| `console.css` | Layout and components; uses only tokens from `brand.css` |
| `api.js` | Fetch wrapper with bearer token, SSE-over-fetch event stream |
| `mock.js` | In-memory sample backend for development (`?mock=1`) |
| `app.js` | Hash router and the six views |

## Developing without a server

Open `index.html?mock=1` and every endpoint is answered from `mock.js`, including a simulated
live run (New operation, Hotfix apply, any bundle path, Build plan, Run this plan). A bundle
path containing `HF-0002` simulates a failed run with rollback. A yellow "Sample data" banner
marks mock mode. Nothing is sent over the network.

- Firefox opens the page straight from disk: `file:///.../web/index.html?mock=1`.
- Chromium blocks ES modules on `file://` origins, so serve the directory with any static
  server (for example `python -m http.server 8000` in this folder, then
  `http://localhost:8000/index.html?mock=1`) or run `jrsctl console` and open the printed URL.
- Without `?mock=1`, if `/api/health` is unreachable the page offers "Use sample data".

Routes: `#/dashboard`, `#/new` (optional `?op=hotfix.rollback&id=...`), `#/runs`,
`#/runs/<runId>`, `#/doctor`, `#/hotfixes`.

## Token handling (spec §11.2)

`jrsctl console` prints a per-launch token. The console accepts it as the URL fragment
`#token=<value>` on first load (stored in `sessionStorage['jrsctl.token']`, fragment replaced
with `#/dashboard` so it never sits in history), or pasted into the "Token required" panel
that appears on any 401. Every `/api/*` call sends `Authorization: Bearer <token>`. No cookies.

The Java side should therefore print a URL of the form
`http://127.0.0.1:7420/#token=<token>` at startup.

## API contract assumed by `app.js`

Endpoints are exactly spec §13.1 plus `GET /api/hotfixes` and `POST /api/runs/{id}/resume`.
Errors: any non-2xx with an optional JSON body `{message}`; 401 switches the UI to the token
panel; 409/410 from `POST /api/run` are shown as a toast (lock held, fingerprint changed,
TTL expired); 421 for a rejected Host header.

### `GET /api/health`

```json
{
  "tool": {"version": "1.0.0", "matrixVersion": "2026.09"},
  "bind": "127.0.0.1:7420",
  "networkMode": "isolated",
  "lastRun": {"id": "r-20260908-1417", "op": "hotfix.apply", "outcome": "succeeded", "finishedAt": "..."},
  "lock": {"held": false},
  "pendingRuns": [{"id": "r-...", "op": "hotfix.apply", "startedAt": "...", "stepId": "start-service"}],
  "snapshots": {"count": 14, "bytes": 1932735283, "retentionDays": 30},
  "doctor": {"pass": 18, "warn": 2, "fail": 0, "ranAt": "...", "attention": [{"status": "WARN", "title": "...", "detail": "..."}]}
}
```

`lock` may carry `runId` and `pid` when held. `doctor.attention` is the list of non-PASS items
from the last doctor run (the dashboard shows the first two). `networkMode` is one of
`isolated`, `proxy`, `direct`.

### `GET /api/server`

```json
{
  "product": "JasperReports Server", "version": "8.2.0", "edition": "PRO", "tenancy": "multi-tenant",
  "database": {"vendor": "PostgreSQL", "version": "15.4"},
  "baseUrl": "http://localhost:8080/jasperserver-pro",
  "installDir": "C:\\Jaspersoft\\jasperreports-server-8.2.0",
  "service": {"kind": "Tomcat", "name": "jasperreportsTomcat", "state": "running"},
  "keystore": {"present": true, "user": "jasperserver"},
  "networkMode": "isolated"
}
```

### `POST /api/plan` with `{op, args}`

`op` is one of `hotfix.apply`, `hotfix.rollback`, `hotfix.verify`, `export`, `import`,
`upgrade`. `args` mirror the CLI flags of spec §8.4, §9.5, §10.4:

| op | args |
|---|---|
| `hotfix.apply` | `{bundle, allowUnsigned}` |
| `hotfix.rollback` | `{id, cascade}` |
| `hotfix.verify` | `{bundle}` |
| `export` | `{uris: [], usersRoles, accessEvents, fullServer, strategy: "rest"\|"vendor"\|null, out}` |
| `import` | `{archive, update, skipUserUpdate, sourceKeystore, sourceKeystorePassword, strategy}` |
| `upgrade` | `{to, package, mode: "newdb"\|"samedb", dbBackupConfirmed, reapplyHotfixes}` |

Response:

```json
{
  "planId": "p-...",
  "plan": {
    "op": "hotfix.apply",
    "title": "Plan: apply JRS-8.2.0-HF-0004 to JasperReports Server 8.2.0 PRO",
    "fingerprint": "sha256:9f3c1a...e07b",
    "validUntil": "2026-09-08T14:47:00Z",
    "summary": {
      "filesTouched": "3 under webapps\\jasperserver-pro\\WEB-INF\\ (2 replace, 1 add)",
      "service": "Stop and start jasperreportsTomcat; WEB-INF\\lib changes require it",
      "database": "No SQL in this bundle",
      "backups": "C:\\ProgramData\\jrsctl\\snapshots\\<runId>\\",
      "rollbackPoints": "After verify, after backup; full rollback via hotfix rollback once recorded",
      "strategy": "File swap with service restart",
      "requires": "JRS-8.2.0-HF-0003 installed (present)",
      "downtime": "Running stops the server for about 2 minutes.",
      "warnings": ["..."]
    },
    "steps": [{"id": "verify-signature", "phase": "verify", "title": "Verify signature", "why": "publisher key jaspersoft-2026"}]
  }
}
```

Summary values are strings (objects and arrays are flattened for display); unknown string
keys are shown too. `warnings` render as warning callouts. `validUntil` drives the
"valid for N min" countdown; when it passes, "Run this plan" is disabled. Steps are rendered
in order and grouped by consecutive `phase`.

### `POST /api/run` with `{planId, confirm: true}` returns `{runId}`.

### `GET /api/runs` returns `{runs: [...]}` (a bare array is also accepted)

```json
{"id": "r-20260908-1417", "op": "hotfix.apply", "target": "JRS-8.2.0-HF-0004", "startedAt": "...", "finishedAt": "...",
 "durationMs": 127000, "outcome": "succeeded", "rollbackAvailable": true, "supportBundleAvailable": true}
```

`outcome` is one of `succeeded`, `failed`, `failed_rolled_back`, `rollback_incomplete`,
`cancelled`, `running`, `interrupted`. `rollbackAvailable` is true only while a compensating
plan exists (recorded hotfix, pre-import snapshot, upgrade point B).

### `GET /api/runs/{id}`

The list item plus `title`, `subtitle`, `backups` (path string), `steps`
(`[{id, phase, title, why, status, durationMs}]` with `status` in `pending`, `running`,
`succeeded`, `failed`, `skipped`, `rolled_back`, `rollback_failed`) and, when failed,
`failure: {stepId, cause, backups: [], nextAction}`.

### `GET /api/runs/{id}/events` (`text/event-stream`)

Replays the journal, then streams live. Each event is

```
event: StepSucceeded
data: {"ts": "...", "runId": "r-...", "stepId": "snapshot", "phase": "backup", "durationMs": 2600}
```

Event names are the spec §5.9 types. Payload fields the UI reads: `ts`, `stepId`, `phase`;
`Log` adds `message` and `level` (`info`, `warn`, `error`); `StepSucceeded` adds
`durationMs`; `StepRetry` adds `attempt`, `of`, `delayMs`; `StepFailed` adds
`failure.cause`; `RunFailed` / `RunRolledBack` add `cause`, `backups`, `nextAction`;
`RunFailed` may set `rollbackIncomplete: true` (exit code 4 case). The server should close the
stream after the terminal event. The client reconnects with backoff if the connection drops
before that and expects a full replay on every connect (it resets its state on `open`).

Because `EventSource` cannot send the bearer header, the stream is read with `fetch` and a
`ReadableStream`; the server must not require anything an ordinary GET cannot provide.

### `POST /api/runs/{id}/cancel`, `POST /api/runs/{id}/rollback`, `POST /api/runs/{id}/resume`

`cancel` returns 2xx. `rollback` and `resume` return `{runId}` of the new run when one is
started (the UI navigates to it); `resume` is assumed from spec §6.6 (CLI `runs recover`).

### `GET /api/runs/{id}/support-bundle`

Binary zip. Fetched with the bearer header and handed to the browser as a download.

### `GET /api/doctor`

Runs doctor and returns `{ranAt, counts: {pass, warn, fail}, items: [{id, status, title, detail, remediation}]}`
with `status` in `PASS`, `WARN`, `FAIL`. Same content as `jrsctl doctor --json`.

### `GET /api/hotfixes`

`{hotfixes: [{id, title, installedAt, files, state, blockedBy: []}]}` where `files` is a
count (an array is accepted) and `state` is `installed` or `rolled_back`.

## Rules kept by this front-end

- No `http://` or `https://` references outside comments (checked by `WebAssetsTest`).
- Status is always icon + word (`.status.pass/.warn/.fail/.running/.pending/.skipped`).
- No `innerHTML`; all API text enters the DOM through `textContent`.
- No global key handlers; the confirm dialog handles Escape and Tab on its own element.
- Light and dark themes come from `brand.css` via `prefers-color-scheme`; motion is disabled
  under `prefers-reduced-motion`.
