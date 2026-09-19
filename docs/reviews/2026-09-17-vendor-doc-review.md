# jrsctl against the vendor documentation (2026-09-17)

Sources: the Jaspersoft PDFs under `C:\Users\rgorsuch\tx-geocoder\docs` (218 files). The ones that describe
operations were read in full: the 10.1.0 / 10.0.0 / 9.0.0 / 8.2.0 / 8.1.0 upgrade guides, the 10.1.0 and
10.0.0 installation guides, the 10.1.0 administrator, security and REST API guides, the 10.1.0 / 10.0.0 /
9.0.0 platform-support sheets, the 10.1.0 / 10.0.0 / 9.0.0 / 8.2.0 release notes, the AWS 9.0.1 guide, and
the community decks on keystore, encryption, clustering, JavaScript customisation, themes, OpenTelemetry,
Docker/Kubernetes and telemetry. Studio, user, domain, OLAP and visualize.js guides were skipped.
Page numbers are the printed ones. Each item names the jrsctl code it concerns.

Legend: **P1** likely wrong today · **P2** vendor step missing from a plan · **P3** detection and diagnostics ·
**P4** export/import model · **P5** customisations · **P6** platform.

## P1 — corrects behaviour that disagrees with the vendor

### 1.1 REST import parked in `pending` is polled for two hours
*Fixed 2026-09-17.*

REST reference pp.119-123: an import whose catalog has broken dependencies or an organisation mismatch stops in
phase `pending` ("cannot run because of an error, but it can be restarted with new options"), and
`brokenDependencies` defaults to `fail`, so any missing data source makes this the normal outcome. The error is
nested: `{"phase":"pending","error":{"code":"import.broken.dependencies","parameters":[...]}}`. Recovery is
`PUT /rest_v2/import/{id}` with `{"brokenDependencies":"skip|include"}` or `DELETE /rest_v2/import/{id}`.

jrsctl (`jrs/rest/RestJrsAdapter.phase`) maps `pending` to `INPROGRESS`, and `Wire.AsyncState` reads a flat
`errorCode`, so the nested code is dropped. `PollImport` keeps polling until `Polling.DEFAULT_TIMEOUT` (2 h)
and then fails with no reason.

Recommend: a fourth `Handles.Phase.PENDING`; parse `error.code` and `error.parameters`; add
`import --broken-dependencies skip|include|fail` mapped to the REST query parameter and to js-import's
`--broken-dependencies skip|include|cancel`; on `pending` either restart with the operator's choice or
`DELETE` the task and exit 2 with the listed URIs (nothing has been imported at that point, so the pre-import
snapshot is not needed). Same shape for export warnings (`export.broken.dependency`).

### 1.2 Compat matrix Java requirements are not what the platform sheets say
*Fixed 2026-09-17: matrix version 2 lists allowed sets; 9.x keeps 17 as the sheet lists it (runtime only).*

`core/src/main/resources/compat/matrix.yaml` requires one Java major per range (7.x→8, 8.x→11, 9.x→17,
10.x→17) and `verify-target-package` refuses anything else. The vendor sheets:

| JRS | JDK certified (PS sheet) | Note |
|---|---|---|
| 8.2 | 8, 11 | "JDK 17 is supported in Runtime mode, but only on Tomcat 9.0.x" (RN 8.2 p.9) |
| 9.0 | 8, 11; 17 runtime-only on Tomcat 9 | installer embeds JDK 8; JS-69038: installer errors when JDK 17 is present |
| 10.0 | 17 only | Jakarta EE 10, Tomcat 10.1.24+ / 11.0.11+ |
| 10.1 | 17 and 21 | RN 10.1 p.14 "Added: JDK/Jakarta 21" |

No vendor document distinguishes a buildomatic Java from the server Java; the guides only require a JDK
(not a JRE) on `JAVA_HOME`. So today a 9.0 target refuses the JDK 11 the vendor certifies and demands the
JDK 17 the vendor calls runtime-only, and a 10.1 target refuses JDK 21.

Recommend: `javaForBuildomatic` becomes a list of allowed majors; split `>=10.0.0 <10.1.0` from
`>=10.1.0 <11.0.0`; `doctor` reports "certified" vs "compatible" rather than pass/fail on the exact major.

### 1.3 Upgrade paths depend on mode and minor version, the matrix has neither
*Fixed 2026-09-17 for modes and ranges; 2026-09-18 for the Compact/Split invariant (exit 6 while planning) and the Oracle `dbVersion` precheck, both in `verify-target-package` (`MasterInvariants`).*

`upgradePaths` in the matrix is `{from: 8.x, to: 10.x}` style. The guides (10.1 pp.10-11, 10.0 pp.11-12, 9.0
pp.10-12, 8.2 §1.1.1):

| Target | samedb from | newdb from |
|---|---|---|
| 10.1.0 | 10.0.0 | 9.0.0, 10.0.0 |
| 10.0.0 | 9.0.0 | 7.1.x – 9.0.0 (chapter text: 8.0.x – 8.2) |
| 9.0.0 | 8.2.x | 8.0.x – 8.2.x |
| 8.2.0 | 8.0 / 8.1 | 7.1 – 7.9 |

So `8.x → 10.x` is only valid to 10.0.x and only as newdb; `9.0 → 10.1` is newdb only; `8.0 → 9.0` is newdb
only. Also (10.1 pp.82-95): Compact and Split installations never cross in one upgrade (`installType=split`
and `audit.*` must be identical on both sides; `js-migrate-to-split-*` is a separate step), and Oracle needs
`dbVersion=` in `default_master.properties` before any 10.1 install or upgrade.

Recommend: `upgradePaths` gain `modes: [samedb, newdb]` and use full ranges; `verify-target-package`
refuses a mode the path does not list (exit 6) and, for Oracle targets ≥10.1, a missing `dbVersion`.

### 1.4 The target buildomatic gets no `keystore.init.properties`
*Fixed 2026-09-17: `stage-keystore-init` step, and the creation banner fails any vendor run.*

Security guide pp.11-13, installation guide pp.192-193, every upgrade guide: buildomatic finds the keystore
through `buildomatic/keystore.init.properties` (`ks`, `ksp`), then `default_master.properties`, then the
`ks`/`ksp` environment variables, then the home of the user running the script. If none resolves, the script
"prompt[s] the user to create a new one", and a second keystore "may overwrite database passwords and the
server will no longer be able to access its internal database".

`ops/upgrade/VendorSteps.WriteMasterProperties` writes only `default_master.properties` into the freshly
unpacked target package. Nothing copies the installed `buildomatic/keystore.init.properties` there, and
jrsctl runs the script as its own process user, which is not necessarily the installing user. With stdin
closed the vendor prompt either hangs until the two-hour timeout or creates a keystore.

Recommend: a `stage-keystore-init` step that copies the installed `keystore.init.properties` into the target
buildomatic (or writes `ks=`/`ksp=` from `KeystoreInspector`), verified by `postcheck`; `doctor` checks that
the three vendor copies (`buildomatic/`, `buildomatic/conf_source/iePro/`, `WEB-INF/classes/`) agree with
each other and with `ksPath` inside the base64-decoded `.jrsksp`; `run-vendor-upgrade` treats the prompt
text about creating a keystore as fatal.

### 1.5 Keystore lookup order in `KeystoreInspector`
*Fixed 2026-09-19 (issue #105, PR #119): the running webapp's own `keystore.init.properties` first, then buildomatic's copy, then the run-as user's home; `doctor keystore` WARNs on a keystore readable beyond its owner.*
The same sources put the files in the **installing** user's home, not the Tomcat user's, unless
`keystore.init.properties` or `ks`/`ksp` point elsewhere. The documented failure when they differ is
`KeystoreManager was never initialized` at startup. `KeystoreInspector` starts from `server.runAsUser`'s
home and consults only `buildomatic/keystore.init.properties`. Recommend: consult
`WEB-INF/classes/keystore.init.properties` first (that is what the running server uses), then the
buildomatic copy, then homes; `doctor` FAILs when the running server's file names a path the run-as user
cannot read, and WARNs when permissions are wider than 600/640 (keystore deck p.6).

### 1.6 Newdb upgrades restart the server between the export and the rebuild
*Fixed 2026-09-17 (ADR-0025): the full export is taken after `stop-service` and the service stays down until the vendor run; a vendor-phase rollback restarts it through the stop step's compensation.*
Phase B stops the service, takes `FullExport`, then **starts the service again** (`full-export-start-service`,
`full-export-wait-for-server`) before the file backups; Phase C stops it a second time and `js-upgrade-newdb`
drops the repository and re-imports that export (ADR-0012). Every repository change made while the server
was back up is lost: scheduler output, users edited, saved reports. The vendor's procedure for newdb is
"Stop your application server" before anything else (10.1 pp.43-44) and the 2023 upgrade deck says "shut
down old server when ready to flip; perform final full export".

Recommend: in `newdb` mode take the export after the vendor-phase stop and do not restart before the
vendor run (one outage instead of two). `samedb` can keep the current order because the export is only a
rollback aid there. *Fixed 2026-09-17, ADR-0025.*

## P2 — vendor upgrade steps the plan does not perform

### 2.1 New Tomcat generation and the webapp move
*Fixed 2026-09-17 (ADR-0026): Tomcat check in doctor and preflight, `--tomcat-dir` with the copy step, `manual` service only; switching a registered service is left open.*

10.0 requires Tomcat 10.1.24+ / 11.0.11+ (Jakarta), 10.1 says "the appropriate version of Tomcat must be
installed" and shows Tomcat 11; every 9→10 and 10.0→10.1 path has the step "Copy ../webapps/jasperserver-pro
directory from Tomcat 9.0 to Tomcat 11.0.x folder" before running js-upgrade (10.1 p.50, 10.0 pp.28-52).
Tomcat 11 on JDK 17/21 additionally needs the `--add-opens` list in `JAVA_OPTS` (`bin/setenv.*`, IG pp.84-86).

jrsctl always sets `appServerDir` to the current `tomcatDir`. Recommend: `upgrade --tomcat-dir <new>`;
`tomcat` version ranges per matrix entry (read `<tomcat>/lib/catalina.jar`'s manifest or `RELEASE-NOTES`);
preflight refuses a Tomcat the target does not certify; a `MoveWebapp` step with compensation; `doctor`
checks the running Tomcat against the running JRS.

### 2.2 Post-upgrade "Additional tasks" (10.1 pp.34-36, 47-48, 60-61)
*Fixed 2026-09-17: `clear-tomcat-caches` and `clear-repository-cache`.*

With the server still stopped: clear `<tomcat>/work` and `<tomcat>/temp`, and clear the repository cache
with `update JIRepositoryCache set item_reference = null; delete from JIRepositoryCache;` (symptom otherwise:
`local class incompatible`). jrsctl already has JDBC access for hotfix SQL. Recommend three idempotent
steps before `StartService`.

### 2.3 Events are not carried by `js-upgrade-newdb`
*Fixed 2026-09-19 (issue #106, PR #120): `upgrade --include-events` adds `import-events` after the vendor run; without it the plan summary says the events are left behind.*
"Starting version 7.9.0, the js-upgrade-newdb script does not import the access, audit, monitoring data"
(10.1 p.80; IG p.256 gives the fix: `js-import --input-zip … --include-access-events --include-audit-events
--include-monitoring-events` with the server stopped). Recommend: the point-B export includes the three
event kinds when `feature.audit_monitoring.enabled` is on, and `upgrade --include-events` adds that
js-import after the vendor run; otherwise the plan summary states that events are left behind.

### 2.4 Vendor test mode as preflight
*Fixed 2026-09-18 (field test 2, U1): `upgrade --test` runs the vendor's own validation as a rehearsal that changes nothing, and the guided menu offers it first.*
`js-upgrade-newdb.bat test <export>` / `js-upgrade-samedb.bat test` "check your default_master.properties
settings and validate your application server location and its ability to connect to your database …
without altering your system" (10.1 pp.45, 58). Recommend running it in `verify-target-package` after
`write-master-properties`; it is the vendor's own check of the file jrsctl wrote.

### 2.5 License file, JDBC drivers, password migration
*Fixed 2026-09-19 (issue #108, PR #124): `verify-vendor-preconditions` judges the licence file, the driver jar and the password storage settings before anything is stopped; `upgrade --migrate-passwords` for samedb to 10.1+; `check-analytics-jndi` for 9.0.x targets.*
- 10.0+: "Place the jaspersoft.jrs.license file in the C:\Users\<user> directory" of the user running the
  script (10.1 pp.43-44), else `-Djs.license.directory`. Preflight check.
- Oracle/SQL Server/DB2: the driver jar must sit in the target's `buildomatic/conf_source/db/<db>/jdbc/`
  and `maven.jdbc.*` must name it; 10.1 RN p.33 says the shipped property files reference old jars.
  `masterOverrides` carries `maven.jdbc.*` over but nothing copies the jar. Preflight check plus an
  optional copy from the installed buildomatic.
- 10.1 samedb with `password.strategy=modern` needs `js-ant migrate-passwords` (dry-run target exists,
  IG pp.194-199); add as an opt-in reconcile step, and WARN when `js.password-storage-config.properties`
  differs between old and new webapp.
- 9.0 targets need JNDI resources `jdbc/jasperserverSystemAnalytics` and `jdbc/jasperserverAuditAnalytics`
  in `META-INF/context.xml` "even if the feature is disabled" (RN 9.0 p.15); a post-upgrade check.

### 2.6 Rollback caveat for 10.1
*Fixed 2026-09-19 (issue #107, PR #121): an import whose sidecar says 10.1.0 or later into an older server is refused at plan time; `import --force-version` overrides with a warning and an audit row.*
"Resources exported from version 10.1.0 cannot be imported into older versions" (RN 10.1 p.6). The point-B
export is pre-upgrade so rollback is unaffected, but `import` should refuse a catalog whose sidecar records a
source version ≥10.1 into a server <10.1 (exit 2) instead of letting js-import fail half way.

## P3 — detection and diagnostics

### 3.1 Capability probes the API actually offers
*Fixed 2026-09-19 (issue #112): `GET /rest_v2/keys/` probes keystore encryption for a version the matrix does not list; `GET /rest_v2/licenseFeatures` gives the `CLUSTERING` capability; the `auth` item names the `pp` header as jrsctl's choice (ADR-0018). `application.wadl` is not used.*
- `GET /rest_v2/licenseFeatures` (REST pp.20-22) returns `{"mt":…, "cl":…, "aud":…}`; `mt` is the
  tenancy answer and `cl` says the licence is clustered. jrsctl derives tenancy from the `MT` token in
  `serverInfo.features` and has no cluster signal.
- `GET /rest_v2/keys/` (REST p.128) answers 200 or 204 on any 7.5+ server; `KEYSTORE_ENCRYPTION` is
  currently assumed from the version and marked "not probeable".
- `GET /rest_v2/application.wadl` (REST p.13) lists every service; a cheaper and safer probe than
  guessing paths.
- `TOKEN_AUTH` has no documented endpoint in 10.1; only the `pp` pre-auth argument and the SSO `ticket`
  argument exist (REST pp.26-31), and `pp` is documented as a URL parameter only. Keep the header mode
  (ADR-0018) but say in `doctor` that it is jrsctl's choice, not the vendor's.

### 3.2 Cluster awareness
*Fixed 2026-09-19 (issue #112): `doctor cluster` WARNs on a clustered licence, and the hotfix apply and upgrade plan summaries say the change reaches this node only. The quartz `isClustered`/`clusterCheckinInterval` keys are not used as a signal: the bundled single-node installer sets both.*
The clustering deck and every release note: nodes share one repository database, must have identical
`.jrsks`/`.jrsksp` (login fails otherwise, JS-57772), replicate the repository cache over JMS, and run the
same webapp. jrsctl runs on one host. Recommend: when `licenseFeatures.cl` is true or
`org.quartz.jobStore.clusterCheckinInterval` is set in `js.quartz.base.properties`, `doctor` WARNs, and
`hotfix apply` / `upgrade` plan summaries state that the other nodes must receive the same WEB-INF changes
before the load balancer sends them traffic.

### 3.3 More `doctor` items with vendor backing
- Tomcat version vs JRS version (§2.1) and JDK vs the allowed set (§1.2).
- `heartbeat.enabled` in `js.config.properties` (telemetry upload; the telemetry programme arrives via a
  cumulative hotfix, so report it after `hotfix apply` as well). Relevant to isolated mode.
- `feature.audit_monitoring.enabled`: warns that event exports can be very large (AG pp.250, 416).
- Bundled PostgreSQL: the installer registers `jasperreportsPostgreSQL` beside `jasperreportsTomcat` and
  the start order is database then Tomcat (IG p.51). `StartService` should check the database service
  first when both exist.
- Stale `<tomcat>/temp/catalina.pid` blocks a start (IG p.237).
- Basic auth with non-ASCII credentials "will always return an error" (REST p.24): WARN and prefer the
  login form.
- AWS images control Tomcat through `tomcat.socket` (AWS guide p.30); `LinuxInit` should accept a socket
  unit name.

*Fixed 2026-09-19 (#113, ADR-0032): `doctor` items `telemetry`, `audit`, `database-service` and `pid-file`, the `auth` item's non-ASCII warning, the start-service step starting the bundled database first, the script kinds removing a stale `catalina.pid`, `SystemdServiceController` driving `X.socket` with `X.service`, and `init` listing socket units and preferring them. The Tomcat and JDK version items of the first bullet were done under #109 and the existing `vendor-java` item.*

### 3.4 Support bundle and vendor logs
*Fixed 2026-09-19 (issue #111): the bundle carries the newest buildomatic script log, `jasperserver.log`, the Tomcat catalina log, `installation.log` and a password-blanked `default_master.properties`, tail-capped and redacted, and every vendor run logs the buildomatic log it wrote.*
The vendor's first troubleshooting instruction is the script log
`buildomatic/logs/js-upgrade-<date>-<n>.log` / `js-install-…log`, then `WEB-INF/logs/jasperserver.log`
and `<tomcat>/logs/catalina.out`. The support bundle (`app`) collects none of these today. Add them
(redacted, size-capped), plus `installation.log` and a redacted `default_master.properties`.

### 3.5 Session hygiene
*Fixed 2026-09-19 (issue #114): `JrsAdapter.close()` sends `GET /logout.html` with the session cookie after a form login; `Bootstrap` closes the adapter at the end of every command. `smoke` gets no separate item.*
Report output is held in the HTTP session and the reference recommends an explicit logout
(`GET /logout.html` with the cookie; there is no `/rest_v2/logout`). `RestJrsAdapter` never logs out;
`smoke` and `doctor` should.

## P4 — export/import model

### 4.1 A portable export needs no keystore copying
*Fixed 2026-09-18 (field test 2, Task 11, ADR-0028): `export --portable` (the shared alias) or `--key-alias`, and `import --key-alias`, on both strategies; the sidecar records the alias and an import with one skips the fingerprint comparison.*
Security guide pp.28-30 and the encryption deck: an export made with `--keyalias
deprecatedImportExportEncSecret` (CLI) or `"keyAlias": "deprecatedImportExportEncSecret"` (REST) decrypts on
any 7.5+ server with the same alias on import. That is the vendor's documented cross-server path; copying
`.jrsks` and passing `--storepass` on the command line (ADR-0020) is jrsctl's own. Recommend
`export --portable` and `import --key-alias <alias>` on both strategies; the fingerprint sidecar then only
matters for exports made with the server key. The REST import today has no key parameter at all
(`RestJrsAdapter.startImport`), so any cross-server REST import currently fails inside the server.

### 4.2 Parameter names to verify against a live server
*Settled 2026-09-19 (issue #110): the 10.0.0 server's `ImportJaxrsService` (`jasperserver-jax-rs-rest-10.0.0.jar`, read from the installed webapp) declares only `includeServerSettings` as the query parameter and `include-server-settings` as the multipart field; no singular form exists. jrsctl's spelling stands and the reference's is a documentation error. `mergeOrganization`/`organization` were added for Task 12; `skipDependentResources` and `skip-favorite-resources` on export remain unexposed.*

The reference spells the import setting switch `includeServerSetting` (singular, p.117) while jrsctl sends
`includeServerSettings`; the multipart form uses `include-server-settings`. The recorded-server harness
(ADR-0011) should settle which the server honours. Likewise `skipDependentResources` and
`skip-favorite-resources` exist on export and `mergeOrganization` / `organization` on both; jrsctl's request
records have none of them, which blocks organisation-scoped work on multi-tenant servers.

### 4.3 Size and theme guards
*Fixed 2026-09-19 (issue #115, ADR-0033): an import above 2 GB leaves REST for the vendor tools (refused when there are none here, a warning under `--strategy rest`); themes are skipped across a major version unless `--themes` is given.*
"Jaspersoft does not recommend uploading files greater than 2 gigabytes" over REST (p.118): choose the
vendor strategy automatically above that size. Themes are repository resources (Themes guide §2-3); old
themes may be incompatible, so `import` should default `skipThemes` on when the sidecar's source version
differs from the target's major.

### 4.4 `--everything` already covers users, roles, permissions, jobs and settings
AG p.266: `--everything` equals `--uris --repository-permissions --report-jobs --calendars --users --roles`
plus UI-modified settings, minus events. The CLI help text and plan summary should say so; today the
`--users-roles` flag looks additive to `--full-server`.

## P5 — customisations

### 5.1 Scanner list
The upgrade guides name what customers edit and must migrate by hand (10.1 pp.35, 47, 60, 76 and the
version-specific chapters). `ops/customizations/WebappScanner` knows five paths. Add:
`WEB-INF/applicationContext-externalAuth-*.xml`, `applicationContext-jdbc-metadata.xml`,
`applicationContext-events-logging.xml`, `applicationContext-adhoc-dataStrategy.xml`,
`applicationContext-security*.xml`, `applicationContext-diagnostic*.xml`, `applicationContext-audit.xml`,
`applicationContext-report-scheduling.xml`, `js.config.properties`, `js.diagnostic.properties`,
`js.password-storage-config.properties`, `js.quartz.base.properties`, `js.externalAuth.properties`,
`web.xml`, `log4j2.properties`, `adhoc-ehcache.xml`, `WEB-INF/classes/hibernate.properties` (the path since
8.2; the scanner lists the pre-8.2 `WEB-INF/hibernate.properties`), `WEB-INF/tags/templates/` (10.1 removed
Tiles), and Tomcat-side `bin/setenv.*`, `conf/server.xml`, `conf/Catalina/localhost/*.xml`, `lib/*.jar`.

### 5.2 JavaScript is an overlay, not files
Since 8.0 the front end is the `jasperserver-ui` webpack project; a customised `scripts/` tree is rebuilt
and copied whole, file names are hashed bundles, and "your JavaScript changes made in earlier versions need
to be added to your new jasperserver-ui project" (JS deck). A per-file 3-way compare under `scripts/` will
always report CONFLICT. Recommend `customizations register` refuses or warns for paths under `scripts/` and
documents the rebuild-and-copy procedure instead.

## P6 — platform notes

- Vendor 10.x certifies JBoss EAP 8 and WildFly 36 alongside Tomcat, and Docker/Kubernetes on ECS, EKS,
  AKS and OpenShift (PS 10.1 pp.5, 18). Q2 in spec §19 (Tomcat only) now has a vendor answer: other
  containers are a real share of 10.x installs; record the decision either way as an ADR.
- The binary installer is "intended for evaluation purposes only" (PS 10.1 p.13); production installs are
  WAR + buildomatic, where no Windows service is registered by the vendor. `init`'s service probing should
  expect a customer-named service or `startup.bat`.
- `Checking Ant return code: BAD`, `Done`, `Processing started` and `ERROR BaseExportImportCommand` are
  observed markers; only `BUILD SUCCESSFUL` / `BUILD FAILED` and the `buildomatic/logs/js-*.log` file are
  documented. Keep the markers, but also capture and reference the log file in the run record.
- 8.2 removed REST v1 and SOAP, so the `>=7.1.0 <7.5.0` matrix row is the only one that could still meet
  a v1-only server; nothing in jrsctl uses v1, so this is fine to leave.

## Suggested order

1. §1.1 pending phase, §1.4 keystore init copy, §1.6 newdb export ordering — data-safety.
2. §1.2 and §1.3 matrix corrections with the mode dimension, §2.1 Tomcat handling — without these an
   operator cannot run the 9→10 or 10.0→10.1 upgrades the vendor documents.
3. §2.2–2.5 post-upgrade steps, §2.4 vendor test mode, §3.1 probes, §3.2 cluster warning.
4. §4.1 portable exports, §4.2 parameter verification, §3.4 support bundle, §5.x scanner and JS.
