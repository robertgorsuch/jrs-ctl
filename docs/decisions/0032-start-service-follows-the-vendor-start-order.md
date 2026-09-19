# ADR-0032: the start-service step follows the vendor's start order, and doctor names what the vendor guides warn about

Date: 2026-09-19. Status: accepted. Issue #113 (vendor documentation review §3.3).

## Context

The vendor guides document six operational facts that jrsctl neither checked nor acted on:

1. `heartbeat.enabled` in `WEB-INF/js.config.properties` uploads usage telemetry to the vendor; the telemetry programme reaches a server through a cumulative hotfix (administrator guide). An isolated host must not call out.
2. `feature.audit_monitoring.enabled` makes the events table grow; an export that includes events can be very large (administrator guide pp.250, 416), and a newdb upgrade loses the events unless they are carried across.
3. The bundled installer registers `jasperreportsPostgreSQL` beside `jasperreportsTomcat`, and the start order is the database first, then Tomcat (installation guide p.51). `init` already knew the name; the shared start-service step started Tomcat alone.
4. A `<tomcat>/temp/catalina.pid` left behind by a JVM that died makes `catalina.sh start` refuse (installation guide p.237).
5. Basic authentication with non-ASCII credentials "will always return an error" (REST API reference p.24); the login form carries them.
6. The vendor's AWS images control Tomcat through `tomcat.socket` (AWS guide p.30). `init` listed service units only, and a socket-activated Tomcat stopped through its service alone is started again by the socket on the next request, in the middle of a run.

## Decision

Each fact becomes a `doctor` item (`telemetry`, `audit`, `database-service`, `pid-file`) or an amendment to one (`auth`), with the guide page in the text, so the report stays the one place an operator looks before a change. Two of them also change what a run does, because a warning that the run then ignores is not worth much:

- **Database first.** `ServiceRuntime.databaseController()` names the companion database service when the host registers one beside the configured Windows service or systemd unit (`CompanionDatabase`: the same `sc.exe query` and `systemctl list-units` that `init` uses, a name mentioning both jasper and postgres). The shared start-service step starts it before Tomcat when it is not running, and a refusal or a timeout there is a recoverable failure that names the database service and leaves Tomcat alone. Script and manual kinds have no companion: `ctlscript.sh` starts both itself, and the others name no service manager. A stop never touches the database service; jrsctl stops what it will change, and it does not change the database server.
- **A stale pid file goes.** `ScriptServiceController.start` removes `temp/catalina.pid` under the watched Tomcat (or the bundled Tomcat inside an install for the `ctlscript` kind) when it names no live process, and logs which file went. A pid file naming a live process is left alone: that JVM is the reason a start would fail, and ending it belongs to the stop path. This is a mutation inside a Step (the start step), idempotent, and needs no compensation because the file was already useless.
- **A socket unit is a unit.** `SystemdServiceController` accepts `X.socket`: its state is `systemctl is-active X.service` (the JVM), and stop and start act on `X.socket X.service` together, socket first, so a stopped server stays stopped. `init` lists `--type=service,socket` and prefers `X.socket` over `X.service` whenever both are listed, confirming the choice through the service's `ExecStart`.

The telemetry finding is reported twice on purpose: as the `doctor` item, and as a warning on a hotfix apply plan when the package lays down `js.config.properties` or the server already has the switch on, because that is when the switch changes hands.

## Consequences

- Four new `doctor` items; the JSON report and the console carry them like any other. A remote configuration skips them with the usual reason.
- The first start after a hotfix or upgrade can now start a second service, the bundled PostgreSQL one; the plan's start-service step is where it happens, and the run journal records the outcome as that step's. Nothing is started on a host that has no such service.
- `hotfix apply --plan` may carry one more warning.
- Neither the database-first start, the pid-file removal nor the socket handling was run against a live bundled installation or an AWS image; they are verified with the fakes, and the vendor's own guides are the source of the commands.

Related: ADR-0014 (script service kinds), ADR-0015 (shared service steps), ADR-0026 (service switch on upgrade).
