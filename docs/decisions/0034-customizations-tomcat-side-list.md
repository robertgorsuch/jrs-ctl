# ADR-0034: the Tomcat-side customisation scan lists what to carry over

Date: 2026-09-19. Status: accepted. Vendor documentation review §5.1 and §5.2, issue #117.

## Context

`customizations scan --vendor` compares the installed webapp with the vendor's untouched copy. The
upgrade guides (10.1 pp.35, 47, 60, 76) also name files outside the webapp that a customer edits and
must carry over by hand: `bin/setenv.*`, `conf/server.xml`, `conf/Catalina/localhost/*.xml` and the
`lib/*.jar` files added to Tomcat. jrsctl has no pristine Tomcat to compare them with (the vendor
ships the webapp, not a Tomcat, and the customer chooses the Tomcat version), so the webapp method
does not extend to them.

## Decision

1. `scan --tomcat` adds a second, separate section. It **lists what to carry over** and never says a
   file changed: `setenv.sh`/`setenv.bat` and `conf/Catalina/localhost/*.xml` when present,
   `conf/server.xml` always, and a `lib/*.jar` unless its name is one a Tomcat 9, 10 or 11
   distribution ships (`TomcatScanner`, a name pattern). A name list can only err towards showing a
   jar Tomcat also ships under a name not on the list, which costs the operator one line; it cannot
   hide a JDBC driver or a site jar.
2. A file there can be registered with `customizations register`, which already accepts anything under
   `server.tomcatDir`; the upgrade's reconcile step then checks it at the same path. Nothing copies it
   to a different Tomcat (`upgrade --tomcat-dir`, ADR-0026): the scan says so.
3. `scripts/` is not scanned differently, only annotated. Registering a file there is allowed (the
   operator may know better) with a warning on stderr, and the scan prints the same advice once when
   it lists a file under `scripts/`. The alternative, refusing the registration, would break the one
   customer who kept a hand-edited file and only needs it noticed.
4. The Hibernate path is fixed by listing both spellings as installer-written; a server has one of
   them, so no version test is needed.

## Consequences

- The `--json` document gains an optional `tomcat` array; without `--tomcat` it is unchanged.
- A change to what Tomcat ships (a new library name) is a one-line edit to the pattern.
- The generated theme CSS the issue mentions is not special-cased: no path for it is documented in
  the vendor material read for the review.
