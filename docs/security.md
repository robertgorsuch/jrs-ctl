# jrsctl security notes

This page collects the security rules the tool keeps and the reasoning behind them. The normative
text is spec §5.2 (secrets), §5.8 (redaction), §11 (security) and §13 (console); this page explains
them for operators and reviewers.

## Threat model

jrsctl runs on the machine that hosts JasperReports Server, usually as the administrator who owns
the installation. The assets are the server's files and database, the credentials in `config.yaml`
references, and the ability to start a mutating run. The threats the design answers:

| Threat | Where it comes from | What stops it |
|---|---|---|
| Another local user starts a run | A shared host: any user can open `http://127.0.0.1:7420` | Every `/api/*` request needs the per-launch bearer token; the token file is owner-only; the static UI is public but inert without the token |
| DNS rebinding | A web page the operator visits resolves an attacker's name to `127.0.0.1` and calls the console from the browser | The `Host` header must be `localhost`, `127.0.0.1`, `[::1]` or the bound address, else 421; the token is never in a cookie, so the browser has nothing to attach automatically |
| Cross-site request forgery | A page posts to `/api/run` from the operator's browser | No cookies are set; authentication is a header the attacker cannot add cross-origin; `connect-src 'self'` in the content security policy keeps the UI itself from calling elsewhere |
| Console exposed on the network | `--bind 0.0.0.0` or `console.bind` on an interface address | Refused with exit 2 unless `console.tls.enabled: true` and `console.auth.mode: local` (a second factor, the operator password) are both configured |
| Secrets in output | Logs, `--json`, SSE payloads, support bundles, error messages | One `Redactor` masks every configured secret (raw, Base64, URL-encoded) and the well-known patterns (`password=`, `Authorization:`, `JSESSIONID`, `Bearer`, the console token) on every stream that leaves the process |
| Token on disk | `$JRSCTL_HOME/console.token` read by another user or left behind | Owner-only permissions (0600 / owner-only ACL), one token per launch, deleted by the shutdown hook |
| Replaying a stale plan | A plan built against an earlier server state | Plans expire after 30 minutes, execute at most once, and are refused (409) when their fingerprint no longer matches the server, the input artifact and the target files |

Out of scope: an attacker with the operator's own account (they can read the config and the token
file), and kernel- or hypervisor-level access to the host.

## Console token lifecycle

1. `jrsctl console` generates 32 bytes from `SecureRandom` and renders them as unpadded base64url
   (43 characters).
2. The token is written to `$JRSCTL_HOME/console.token` with owner-only permissions, replacing any
   file a crashed launch left behind, and an audit row `console.token.issued` is recorded.
3. The token is registered with the process-wide redactor **before** it is printed, so the one and
   only place it appears unmasked is the launch line
   `Console: http://127.0.0.1:7420/#token=<token>` on the command's own standard output. Logs,
   SSE payloads, error messages and support bundles show `[redacted]` in its place.
4. The browser takes the token from the URL fragment (never sent to the server), stores it in
   `sessionStorage`, rewrites the fragment to `#/dashboard`, and sends it as
   `Authorization: Bearer <token>` on every API call. Nothing is stored in a cookie.
5. Every comparison is constant-time (`MessageDigest.isEqual`).
6. On Ctrl-C, `stop` on standard input, or normal exit the shutdown hook stops the listener,
   cancels live runs through their cancellation token (waiting up to 30 s for the in-flight step to
   finish or compensate) and deletes the token file. The next launch issues a fresh token.

Copy the token from the terminal only into a browser on the same machine. If it must be pasted
(the "Token required" panel), paste it into the console page, never into a chat or ticket.

## Bind, TLS and local authentication rules

- Default bind is `127.0.0.1:7420`; `--bind` and `--port` override `console.bind` and
  `console.port`.
- Any address that is not loopback (`127.0.0.1`, `localhost`, `::1`, or an address that resolves to
  loopback) is refused with exit 2 unless both hold:
  - `console.tls.enabled: true` with `console.tls.certPath` (PEM certificate chain, leaf first) and
    `console.tls.keyPath` (unencrypted PKCS#8 PEM, `BEGIN PRIVATE KEY`; RSA, EC or Ed25519). A
    PKCS#1 key (`BEGIN RSA PRIVATE KEY`) is refused with the `openssl pkcs8 -topk8 -nocrypt`
    command to convert it; the JDK reads PEM without extra libraries and no PKCS#12 handling is
    needed.
  - `console.auth.mode: local` with `console.auth.passwordRef` (an `env:`, `file:` or `enc:`
    reference as everywhere else in the configuration).
- In `local` mode the API expects `Authorization: Basic base64(<token>:<operator password>)`: the
  token travels in the user-name slot so both factors fit the one header a browser can send, and the
  bearer form alone is refused. The bundled front-end speaks `token` mode; `local` mode is for
  operators who script against the API or front it with their own page.
- When bound to a wildcard address, the `Host` check additionally accepts the machine's own
  interface addresses and host name; anything else is still 421.
- Every response carries `Cache-Control: no-store`,
  `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and `X-Frame-Options: DENY`.

## What runs from the console can and cannot do

- `POST /api/plan` builds a plan through the same code as the CLI and stores it for 30 minutes; it
  mutates nothing.
- `POST /api/run` requires `confirm: true`, refuses while a run needs recovery (409), rebuilds the
  plan from its stored arguments and refuses when the fingerprint differs (409) or the plan expired
  or already ran (410), then executes it through the same `Runner`, run lock and journal as the CLI.
  Console-started runs are non-interactive: no step waits for a terminal prompt.
- Cancel acts through the run's single cancellation token; rollback and resume use the same recovery
  code as `jrsctl runs recover`. Every console action leaves an audit row with actor `console`.

## What the support bundle contains

`GET /api/runs/{id}/support-bundle` streams a zip meant to be attached to a support ticket:

| Entry | Content |
|---|---|
| `run.json` | The run document of `GET /api/runs/{id}` (steps, statuses, durations, failure block) |
| `plan.json` | The stored plan: summary, fingerprint inputs, steps |
| `transitions.jsonl` | Every `step_transitions` row of the run, one JSON object per line |
| `events.jsonl` | Every event the console saw for the run (only for runs started from this console) |
| `server.json` | The `GET /api/server` document at bundle time |
| `doctor.json` | The `doctor` report (cached for 60 s) |
| `config-redacted.yaml` | The effective configuration with secret references, never values |
| `logs/jrsctl.log` | The last 2000 lines of the JSON log |

Every entry passes through the redactor as it is written, so configured secrets, the console token,
session cookies and bearer tokens appear as `[redacted]` in any encoding the redactor knows. The
bundle never contains `secrets.enc`, private keys, keystore files, snapshots or exported archives.
Review `config-redacted.yaml` for host names and paths you consider sensitive before sharing.
