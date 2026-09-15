# ADR-0018: The pre-authentication token can travel as a header

Status: accepted, 2026-09-15. Amends spec §5.1 (the `server.auth` block) and §7.3 (REST client).
Issue #45.

## Context

With `server.auth.mode: token`, `RestClient` appended `pp=<token>` to the URL of every request. A
URL is written to Tomcat's access log, to the log of every proxy or load balancer in front of the
server, and to jrsctl's own error messages before redaction. The token is registered with the
redactor, so jrsctl's output is clean, but the other places keep the token in clear.

JasperReports Server's pre-authentication filter reads the same `pp` value from either place. The
vendor sample `samples/externalAuth-sample-config/sample-applicationContext-externalAuth-preAuth-mt.xml`
of the 10.0.0 installation documents its `tokenInRequestParam` property: `false` reads the token
from the request header only, `true` from the URL parameters only, and when the property is not
set the header is tried first and the parameters after it.

## Decision

1. A new optional key, `server.auth.tokenLocation`, takes `query` (the default) or `header`.
2. `query` keeps the previous behaviour, so every existing token configuration works unchanged.
3. `header` sends the token as the `pp` request header on every request and never in the URL.
4. The key is written back by `ConfigWriter` only when it is not the default, so `init` output
   is unchanged.
5. The operator guide recommends `header` for servers whose `tokenInRequestParam` is `false` or
   not set.

## Alternatives

- **Make `header` the default.** Rejected for a minor release: a server configured with
  `tokenInRequestParam=true` would stop authenticating jrsctl after an upgrade of the tool.
- **Keep the URL form and document the exposure.** Rejected: the header form exists on the server
  side and removes the exposure for most installations.

## Consequences

- With `header` against a server that reads the parameter only, login fails with HTTP 401 and
  jrsctl stops with exit 2 naming `server.auth`; nothing is mutated.
- `Config.Auth` gains a component; its previous constructor remains and means `query`.
