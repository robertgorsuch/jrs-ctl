# jrsctl compatibility matrix

Rendered from `core/src/main/resources/compat/matrix.yaml` (matrix version 1, unsigned because it
ships inside the same artifact as any key that could verify it). The YAML is the source of truth
and is what `doctor`, `upgrade` and the adapter read; this page exists so the matrix can be read
without opening the jar. Version ranges use npm semver syntax; a two-component server version
such as `8.2` is coerced to `8.2.0` before matching.

## Supported servers

| Server versions | Editions | Application server | Java for buildomatic | Databases | Expected capabilities (all editions) | PRO only |
|---|---|---|---|---|---|---|
| 7.1 to 7.4 (`>=7.1.0 <7.5.0`) | CE, PRO | Tomcat | 8 | PostgreSQL, MySQL, Oracle, SQL Server, DB2 | `EXPORT_ASYNC`, `IMPORT_ASYNC`, `REST_LOGIN` | `ORGS` |
| 7.5 to 7.9 (`>=7.5.0 <8.0.0`) | CE, PRO | Tomcat | 8 | PostgreSQL, MySQL, Oracle, SQL Server, DB2 | `EXPORT_ASYNC`, `IMPORT_ASYNC`, `REST_LOGIN`, `KEYSTORE_ENCRYPTION` | `ORGS` |
| 8.x (`>=8.0.0 <9.0.0`) | CE, PRO | Tomcat | 11 | PostgreSQL, MySQL, Oracle, SQL Server, DB2 | `EXPORT_ASYNC`, `IMPORT_ASYNC`, `REST_LOGIN`, `KEYSTORE_ENCRYPTION`, `TOKEN_AUTH`, `PREAUTH` | `ORGS` |
| 9.x (`>=9.0.0 <10.0.0`) | CE, PRO | Tomcat | 17 | PostgreSQL, MySQL, Oracle, SQL Server, DB2 | `EXPORT_ASYNC`, `IMPORT_ASYNC`, `REST_LOGIN`, `KEYSTORE_ENCRYPTION`, `TOKEN_AUTH`, `PREAUTH` | `ORGS` |
| 10.x (`>=10.0.0 <11.0.0`) | CE, PRO | Tomcat | 17 | PostgreSQL, MySQL, Oracle, SQL Server, DB2 | `EXPORT_ASYNC`, `IMPORT_ASYNC`, `REST_LOGIN`, `KEYSTORE_ENCRYPTION`, `TOKEN_AUTH`, `PREAUTH` | `ORGS` |

What the capabilities mean, and how each is decided:

| Capability | Meaning | Decided by |
|---|---|---|
| `EXPORT_ASYNC` | `POST /rest_v2/export` with a polled task, so an export runs with the service up | probed: `GET /rest_v2/export/<probe>/state` answers 200 or 404 |
| `IMPORT_ASYNC` | `POST /rest_v2/import` with a polled task | probed: `GET /rest_v2/import/<probe>/state` answers 200 or 404 |
| `ORGS` | multi-tenant organisations exist | probed: `GET /rest_v2/organizations` answers 200 or 204 |
| `REST_LOGIN` | `POST /rest_v2/login` exists | probed: an empty POST answers anything but 404 or a 5xx |
| `KEYSTORE_ENCRYPTION` | exported passwords are encrypted with the server keystore (`.jrsks`) | taken from this matrix; not probeable without server configuration |
| `TOKEN_AUTH` | token authentication is available | taken from this matrix |
| `PREAUTH` | pre-authentication (`pp`) is available | taken from this matrix |

A probe the server refuses with HTTP 401 or 403 is not a failed probe: the command stops with exit
2 naming `server.auth`, because falling back to the vendor tools would stop the service over a
wrong password.

## Upgrade paths

The target must be strictly newer than the source and the pair must appear here; anything else is
refused with exit 6 before any backup is taken.

| From | To |
|---|---|
| 7.x | 7.x, 8.x |
| 8.x | 8.x, 9.x, 10.x |
| 9.x | 9.x, 10.x |
| 10.x | 10.x |

`upgrade` also checks that `vendor.javaHome` is the Java major the target's buildomatic needs (the
"Java for buildomatic" column) and refuses with exit 6 when it is not.

## Platforms

jrsctl itself runs on Windows and Linux, x86-64 only (ADR-0002), as a Windows service, a systemd
unit, a `ctlscript`, a bare `catalina` script or a manually managed process (`service.kind`). The
server's own platform support is Actian Jaspersoft's; this matrix only says what jrsctl has been
built to drive.

## Keeping this page honest

`RestJrsAdapterCapabilitiesTest` and `CompatMatrixTest` read the YAML directly; when the YAML
changes, change this page in the same commit. A matrix entry naming a capability this build does
not know is dropped silently by the adapter today (assessment item N10); a load-time check is on
the list.
