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

## Key management

- Bundle signatures are Ed25519 over `manifest.json` only; the manifest carries the SHA-256 of
  every other file, so the signature transitively covers the whole bundle and any unlisted file
  fails verification.
- The trusted key ring is `$JRSCTL_HOME/keys/trusted/<name>.pub`, managed with `jrsctl keys
  add|remove|generate`; every change is audited. The Jaspersoft publisher key is a resource in the
  jar, cannot be removed, and is what `.sig` files on release archives are checked against.
- `keys generate` writes the private key once, owner-only, and never again; it is not kept in the
  home, not in the state store and not in any log. Store it outside version control, or in
  `secrets.enc` via `secrets set <name> --from-file`.
- `hotfix apply --allow-unsigned` bypasses the signature check for one run and writes an audit row
  naming the bundle and the actor. It exists for bundles an operator built themselves; a support
  process should never require it.
- `secrets.enc` is AES-256-GCM with a PBKDF2-HMAC-SHA256 key derived from the passphrase and a
  machine-bound salt; copying the file to another host does not move the secrets. The identity
  is `/etc/machine-id` on Linux and the computer name on Windows (store version 2); a version 1
  store, bound to the DNS host name by builds before 2026-09-10, is rebound in place the first
  time it is unlocked and a version 2 store is never retried with the old identity.

## Hardening (Phase 8)

Controls that hold for every command, and what the two offline help features deliberately do not
expose:

| Control | What it guarantees |
|---|---|
| Console token | One 32-byte `SecureRandom` token per launch, shown once on the launch line, owner-only on disk, registered with the redactor before it is printed, compared in constant time, deleted on exit. A non-loopback bind is refused unless TLS and local authentication are both configured. |
| Redaction | One process-wide `Redactor` knows every configured secret (raw, Base64, URL-encoded) plus the well-known patterns (`password=`, `Authorization:`, `JSESSIONID`, `Bearer`, the console token) and filters **every** stream that leaves the process: terminal text, `--json`, the JSON log, SSE payloads, vendor tool output and support bundles. `config show` and the bundle's `config-redacted.yaml` print references (`env:`, `file:`, `enc:`), never values. |
| Run lock | `runs.lock` in the home, held for the whole mutating run with the run id and pid inside; a second jrsctl process exits 9 and names the holder. A run that never reached a terminal state blocks every other mutating command (exit 8) until `runs recover` finishes it, so two processes can never interleave steps on the same installation. |
| Journal before effect | Every step transition is written to `state.db` (WAL, `synchronous=FULL`, append-only) before the step's effect is reported; a crash mid-step is recoverable because the interrupted step is known and every step is idempotent. |
| Plans are inert | `--plan` and the console's `POST /api/plan` build the plan and stop; a plan is stored 30 minutes, runs at most once, and is refused when its fingerprint (server identity, input artifact, target files, configuration) has changed. |
| Overrides are audited, never silent | `--allow-unsigned`, `--allow-unsupported`, `--db-backup-confirmed` and `--force` change behaviour but never the exit code, and each writes an audit row. |
| Retention never removes what rollback needs | Automatic and manual pruning (`runs prune`) skip every snapshot of an installed hotfix, a registered customization, the most recent successful upgrade and any run pending recovery; manual pruning takes the run lock and is audited (`runs.prune`). |
| Offline help is static | `jrsctl <command> --explain`, `jrsctl help` and `jrsctl docs` print text fixed at build time and exit. They open no `Bootstrap`: they read neither `config.yaml`, `secrets.enc`, the key ring, the state store nor the server, so they expose no value from the operator's environment, need no passphrase, and can be run by anyone who can execute the jar without learning anything about a particular installation. The embedded documents are the same Markdown files as in the repository, so a reader can diff them against the published version. |
| Least privilege | Nothing in the jar needs administrator rights except what the operation itself needs (stopping the service, writing under the installation); `init`, `doctor`, `smoke`, `selfcheck`, `docs`, `--explain` and every `list` run as any user who can read the installation. |
