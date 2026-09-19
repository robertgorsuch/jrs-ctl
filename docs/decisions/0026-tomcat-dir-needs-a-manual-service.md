# ADR-0026: an upgrade into a new Tomcat copies the webapp there and needs `service.kind: manual`

Date: 2026-09-17. Status: accepted. Review §2.1 in `docs/reviews/2026-09-17-vendor-doc-review.md`.

## Context

JasperReports Server 10.0 moved to Jakarta EE and runs only on Tomcat 10.1.24+ or 11.0.11+
(platform-support 10.0.0 p.20, installation guide 10.1 p.58). Every 9-to-10 and 10.0-to-10.1
procedure in the upgrade guides therefore starts with a new Tomcat: "Copy ../webapps/jasperserver-pro
directory from Tomcat 9.0 to Tomcat 11.0.x folder" before running `js-upgrade-*`, whose
`appServerDir` names the new Tomcat (10.1 p.50, 10.0 pp.28-52). jrsctl always reused `server.tomcatDir`,
so a 9-to-10 upgrade would have deployed a Jakarta webapp into Tomcat 9 and produced a server that
cannot start, with no check saying so.

The hard edge is service control. A Windows service, a systemd unit or a `ctlscript.sh` is registered
for the old Tomcat: after the vendor run, `start-service` would start the old server on the old
webapp, `wait-for-server` would see the old version, and the run would misreport. Re-registering a
service inside the run (a new Windows service, an edited unit) is platform work jrsctl does not do.

## Decision

1. The matrix lists the certified Tomcat ranges per release line (`tomcat`). `doctor` gains a `tomcat`
   item judging the running Tomcat against the running server; `verify-target-package` refuses a
   target whose host Tomcat is not certified for it, naming the ranges; a Tomcat whose version cannot
   be read (no `lib/catalina.jar`, no `RELEASE-NOTES`) is a plan warning, not a refusal.
2. `upgrade --tomcat-dir <dir>` names the new Tomcat. The plan copies `webapps/<name>` into it after
   the stop (`copy-webapp-to-tomcat`, compensated by removing the copy; the old Tomcat is never
   touched), writes `appServerDir=<dir>` into the target buildomatic's `default_master.properties`,
   clears the new Tomcat's `work` and `temp`, and at the end points `server.tomcatDir` at it beside
   `server.buildomaticDir`.
3. `--tomcat-dir` is accepted only with `service.kind: manual`, where the operator stops and starts
   Tomcat on jrsctl's instruction and can start the new one. Any other kind is refused at plan time
   with exit 2, the message naming the kind and the vendor's manual step: register the new Tomcat as
   the service afterwards.
4. The plan summary states what stays with the operator: the new Tomcat must listen on the port
   `server.baseUrl` names, carry the `JAVA_OPTS` the vendor requires for it (`--add-opens` on Tomcat
   11 with JDK 17/21, installation guide 10.1 pp.84-86), and be registered as the service afterwards.

## Consequences

- A 9-to-10 upgrade is possible through jrsctl on a host where the operator runs Tomcat by hand,
  and is refused with a clear reason everywhere else, instead of producing a dead server.
- Switching a registered service to the new Tomcat inside the run is left open. When it is built,
  the `manual` restriction goes and this ADR is amended.
- The JDBC driver jar the vendor deploy target copies into `<tomcat>/lib` and the `setenv` options
  are not carried over; both are the operator's, as in the vendor procedure.

## Amendment 2026-09-19: the service switch stays the operator's, and jrsctl says how (issue #109)

The open point was decided as *refuse, but say how* rather than a `SwitchService` step.
Re-registering a Windows service (`service.bat remove` and `install`, then the JVM options, log-on
account and start type of the old one) or rewriting a systemd unit needs elevation jrsctl otherwise
never asks for, has no compensation that could put the old registration back with its settings, and
cannot be exercised here without a host to break; the refusal is the safer option of spec §0.

1. The refusal (exit 2) keeps its message and its remediation now carries the platform's own steps
   for the configured kind, with the real paths and service name filled in (`ServiceSwitch`): a
   Windows service, a systemd unit (`systemctl cat`, the `CATALINA_HOME`, `ExecStart` and `ExecStop`
   paths, `daemon-reload`, `restart`), a `catalina` script (`jrsctl config set service.scriptPath`)
   and a `ctlscript` (which starts only the bundled Tomcat: switch to the `catalina` kind).
2. With `service.kind: manual` the plan summary carries the same steps for this operating system's
   registered kind with `<name>` as a placeholder, so an operator who set the kind aside for the
   upgrade has the recipe in front of them. The operator guide lists all four.
3. The `--add-opens` list the installation guide 10.1 (pp.84-86) gives for Java 17 and 21 is judged,
   never required: the plan warns when the host Tomcat's `bin/setenv.sh|bat` carries no
   `--add-opens` and the Tomcat is of the Jakarta generation (major 10 or later), naming the file;
   `doctor`'s `tomcat` item is WARN in the same case. It is advice because the vendor's own bundled
   installer writes a `setenv` without the options and that server starts (10.0.0 on Tomcat 10.1.41
   and Java 17, checked on the maintainer's installation). A Tomcat 9, or one whose version cannot
   be read, is not judged.

The `manual` restriction therefore stays. Point 4 of the decision and the second consequence are
superseded by this amendment.
