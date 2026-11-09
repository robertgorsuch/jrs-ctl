# ADR-0051: a full-server export from another machine is the REST export's `everything` parameter

Status: accepted · Date: 2026-11-09 · Spec: §9.2, §9.5 · Amends ADR-0021 (export keeps the service running) and the remote mode of #68 · Issue #265

## Context

`export --full-server` has always meant `js-export --everything` through the installed buildomatic: the repository, permissions, report jobs, calendars, users, roles and the settings changed in the UI (administrator guide p.266), events on request. The strategy rule (spec §9.2) therefore routed every full-server export to the vendor tools, and from a machine with no installation (`init --remote`, #68) the request was refused, because the vendor tools need the installation on the machine they run on.

That left operators of a server jrsctl cannot reach with the vendor tools, a container deployment above all (ADR-0043), with no full export from jrsctl at all, while the REST export that does run from there is a repository export: `GET`-able resources, permissions and, on request, users, roles, settings and events, but no report jobs. Yet the REST export service has an `everything` parameter, and the REST adapter already sends it when the request's scope is `EVERYTHING`; nothing reached that code from the command line except through the vendor route.

What `everything` carries over REST was checked in the vendor's REST API reference for 8.2 (p.89), 9.0 (p.107), 10.0 (p.110) and 10.1 (p.110), which all say the same: "Export everything except audit and monitoring: all repository resources, permissions, report jobs, users, roles, and server settings." Calendars are not mentioned. Whether they are included and simply undocumented is not known from the reference, so this decision does not claim them.

## Options

1. **REST everywhere.** Make REST the default for `--full-server` wherever the probe passes, host included. Simplest rule, but it changes what `export --full-server` produces on every server host today, with a documented difference (calendars) the operator may not notice.
2. **REST where there is nothing else.** Keep `js-export --everything` on the server host, use the REST `everything` export from a machine that names no installation, and say in the plan what the REST reference lists and does not. Nothing changes for existing host installations; the refused case becomes a working one with a stated caveat.
3. **Leave it refused.** The operator takes the export by hand and records a sidecar for it (`export record`, #263). Honest, but the tool declines the one export the remote mode exists for.

## Decision

Option 2.

- `Strategies.select` for an export takes whether the configuration names a local installation. A full-server request on a host with an installation selects the vendor tools as before. A full-server request from a machine without one goes through the probe like any other export: REST when `EXPORT_ASYNC` passes, with a reason naming the `everything` parameter and what the reference lists for it; the vendor tools when it fails, which the planner then refuses naming the failed probe, as for any other export.
- The plan of a REST full-server export carries `FULL_SERVER_REST_NOTE`: the REST reference's list for `everything`, that calendars are not mentioned, and that `export --full-server` on the server host is `js-export --everything` if they matter. `--stop-service` on such an export carries `STOP_SERVICE_REST_NOTE`: it applies to js-export only.
- The sidecar records what it always did for a full export (`scope: EVERYTHING`, `fullServer: true`), so `import` snapshots the full server before importing it, whichever route produced the archive.
- `--strategy rest` on a host still forces the REST route, as before; `--strategy vendor` from another machine is still refused, as before.

## Consequences

- An operator of a container deployment can take a full export from the migration host with jrsctl, with the hash, keystore fingerprint, request flags and source version in the sidecar, instead of by hand.
- The archive may lack calendars. The plan says so before the run, and the operator who needs them has the host route. If a later vendor reference, or a test against a server with calendars, shows that `everything` carries them over REST, the note shrinks to a sentence and this ADR gets an amendment; the rule itself need not change.
- The guided menu's wording for "Everything" says which tool runs where. The question it asks (report jobs and calendars?) still decides between the plain REST export and `--full-server`.
- `requireLocalInstallation` loses its full-server branch: a vendor selection from another machine is now only ever forced or the result of a failed probe, and the refusal names which.
