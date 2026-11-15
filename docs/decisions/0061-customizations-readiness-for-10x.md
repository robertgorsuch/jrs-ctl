# ADR-0061: customizations readiness reports likely porting work for a move to 10.x

Status: accepted · Date: 2026-11-15 · Spec: §10.3 · Issue #306

## Context

10.0 moves JasperReports Server to Jakarta EE and Java 17. A customer porting about 50 Java files across 5 modules, plus overlay files, asked for a readiness report, even a report-only one (November 2026). The issue lists the target's library lines as Spring 6, Hibernate 6, Spring Security 6, Jersey 3, Quartz and Ehcache 3. The 10.0.0 PRO webapp on the development machine (`WEB-INF/lib`) actually ships Spring 6.2, Spring Security 6.5, Hibernate 6.6, Ehcache 3.10, Quartz 2.5, Jersey **4.0** and `jakarta.servlet-api` 6.0. It also still ships `jaxb-api` 2.3.1 and `javax.activation-api` 1.2.0, so not every renamed `javax` package is gone from it.

## Decision

- `jrsctl customizations readiness --target <version> [--vendor <path> [--installed <dir>]] [--target-vendor <path>] [--jar <file>]... [--json]` is read-only and needs no server. The files checked are the site's changed and added classes (`WEB-INF/classes/**.class`), jars (`WEB-INF/lib/*.jar`) and XML, JSP, tag and TLD files, from the same comparison as `customizations scan` (`--vendor`), plus any jar, class file or class directory named with `--jar`. `--installed` without `--vendor` is a usage error (exit 1), because every file would then count as the site's. A `--target` below 10.0 is refused with exit 6.
- **Classes are read from the constant pool only**, by a small reader in jrsctl: `CONSTANT_Class` entries, and every `L<name>;` in a `CONSTANT_Utf8` entry (descriptors, signatures, annotation types). No class-file library is approved (spec §13.3), and the constant pool is all this check needs. Jars, and jars inside a war, are read as streams.
- **Findings, per file and kind:**
  - `JAKARTA`: names a `javax.*` class that Jakarta EE 9 renamed to `jakarta.*`. The list is fixed in `ReadinessRules`: servlet (with JSP), EL, WebSocket, persistence, validation, inject, JAX-RS, JAXB, JAX-WS, SOAP, JWS, activation, mail, JMS, Faces, EJB, CDI, JSON-P/B, interceptors, decorators, batch, connectors, Jakarta Security, JASPIC, JACC, transaction (not `javax.transaction.xa`), and the Common Annotations classes. The JDK's own `javax.*` packages (`javax.sql`, `javax.naming`, `javax.crypto`, `javax.xml.parsers` and the rest) are never reported. In text files, the dotted names are matched.
  - `JAKARTA_SHIPPED`: such a class, which the target's webapp still ships (with `--target-vendor`). It may still resolve, so it is a check, not a port.
  - `NAMESPACE`: a Java EE descriptor or schema namespace (`java.sun.com/xml/ns/j2ee|javaee`, `xmlns.jcp.org/xml/ns/javaee`). Reported for a check, not as an error, since whether the target accepts it was not established here.
  - `NOT_IN_TARGET` (with `--target-vendor` only): a class under the Spring, Hibernate, Quartz, Ehcache, Jersey, JasperReports or `com.jaspersoft` packages that the target's `WEB-INF/classes` and `WEB-INF/lib` do not hold, and that the site does not define itself.
  - `BYTECODE`: compiled for a Java newer than 17 (class file version above 61), the runtime the 10.0.0 distribution bundles.
  - `UNREADABLE`: a file that could not be read, never skipped silently.
- **No hand-written list of removed library APIs.** The issue proposed one. Looking classes up in the target's own webapp gives the same answer from the target itself, for whichever library versions it ships, with nothing to keep up to date. Without `--target-vendor`, the report says that the lookup was not made.
- A jar's `META-INF/maven/` (its build metadata) is not read: a pom names `javax.*` properties and comments, not code.
- Exit 0 whenever the check ran, whatever it found; the report is the product. The text and the operator guide say that a file without findings is not shown to be ready. JDK-internal APIs are not checked; `jdeps --jdk-internals` does that.

## Consequences

- A porting team gets a per-file list of renamed APIs and missing classes, with the scale first.
- For a third-party library jar the site added, `NOT_IN_TARGET` also lists that library's optional dependencies which the target never had. On the 10.0.0 server's own hotfixed jars these were, for example, `spring-oxm` and the reactive web client. The guide says to read `NOT_IN_TARGET` for the site's own code first.
- On the development machine, a run over the hotfixed 10.0.0 webapp against the pristine 10.0.0 war read 52,270 classes with none unreadable. That run is what found the Maven-metadata false positives (`javax.activation.version`) that are now skipped. A small site jar compiled against stubs gave exactly the expected findings against the real 10.0.0 webapp. Not run against a real 8.2 site.
