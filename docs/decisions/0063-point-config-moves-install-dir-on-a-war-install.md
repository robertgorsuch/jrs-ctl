# ADR-0063: after a run with `--tomcat-dir`, point-config-at-target moves `server.installDir` on a WAR install only

Status: accepted · Date: 2026-11-18 · Spec: §10.1 · Issue #343 · Follows ADR-0062

## Context

`point-config-at-target` set `server.buildomaticDir` and, with `--tomcat-dir`, `server.tomcatDir`. It never touched `server.installDir`. Two things in later jrsctl processes read `installDir`:

- **The service controller:** `Bootstrap` binds the platform with `withInstallDir(server.installDir)`. The `manual` prompt, the process scan and the lingering-JVM check therefore keep judging the old Tomcat.
- **doctor:** it finds the layout, the webapp and the files it judges through `server.installDir`.

The field run of 2026-11-18 (8.0.4 WAR install, `installDir` = `tomcatDir` = the Tomcat 9 directory) showed it. After the upgrade, doctor reported `running-tomcat` "no Tomcat under C:\Tomcat9", and a false `analytics-jndi` WARN from the old webapp. Everything passed once `server.installDir` was set by hand.

There are two layouts in the field:

| Layout | `server.installDir` | Its value after a side-by-side upgrade |
|---|---|---|
| WAR install | the Tomcat directory itself (`installDir == tomcatDir`) | the new Tomcat, by the same reasoning as `tomcatDir` |
| Bundled installer | the directory that holds `apache-tomcat` (and often `buildomatic`, the bundled database) | unknown: the new Tomcat is wherever the operator unpacked it, with no installation directory around it |

## Decision

- **WAR install:** when the planning-time `installDir` equals `tomcatDir`, `point-config-at-target` sets `server.installDir` to the `--tomcat-dir` Tomcat, in the same atomic rewrite as the other keys. The step's detail names it, and the "already done" check includes it, so re-execution changes nothing.
- **Bundled installer:** otherwise the key is left alone (the safer choice, since a wrong `installDir` misleads more than a stale one), and the plan warns with the current value, the old Tomcat, the new one and the command to set it (`INSTALL_DIR_WARNING`).
- **Rollback:** nothing new. `restore-jrsctl-config` already puts back the whole `config.yaml` saved before the run, `installDir` included. A test now asserts it.

## Consequences

- After a side-by-side upgrade of a WAR install, the next `doctor` and the next service step judge the new Tomcat with no manual config change. This removes one step from the demo script for jrsctl releases that include this.
- On the bundled layout, the operator still sets `server.installDir` by hand, and is now told so in the plan.
- Tested against the fakes only (`UpgradeRunTest`: the WAR layout forward run and rollback, and the bundled layout's warning and unchanged key).
