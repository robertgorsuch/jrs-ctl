# ADR-0013: the buildomatic directory is configured or discovered, not assumed under `installDir`

Status: accepted, 2026-09-14. Amends spec §5.1, §7.4, §10.2 step 6, §12.0 and §12.1 (Draft 1.1).

## Context

Spec §7.4 said "locate `buildomatic/` under `installDir`", and every consumer did exactly that:
the doctor `vendor` and `permissions` checks (through `TomcatLayout.buildomaticDir`), `init`
reading `default_master.properties`, the default JDBC driver directory, the keystore location
from `keystore.init.properties`, the vendor export/import strategy, and the upgrade's point-B
backup and rollback of the installed tree.

In the field that layout is the exception. The installer bundle puts `buildomatic/` under the
install root, but WAR-file installations keep the unpacked distribution wherever it was
extracted: on another disk, under a mount point, or on a network share that several servers
use. On such hosts `doctor` failed `vendor` with "no buildomatic directory under
<installDir>", which was true and unhelpful, and there was no key to fix it with.

## Decision

1. New optional key `server.buildomaticDir` (overridable as `JRSCTL_SERVER_BUILDOMATIC_DIR` and
   `--set server.buildomaticDir=...` like every key). Any path the JVM can open is accepted: a
   drive letter, a Linux mount point, a UNC path.
2. One resolver, `BuildomaticLocator.resolve(Config)`, used by every consumer above, in this
   order:
   1. `server.buildomaticDir` when set. It is authoritative: if it is not a reachable directory
      the result is "not found, configured but unreachable", and no discovered tree is used in its
      place. An unmounted share must stop the operation, not redirect it to another tree.
   2. `<installDir>/buildomatic`, as before, with no further test.
   3. Neighbours: `buildomatic` beside `server.tomcatDir`, beside `installDir`, and inside any
      `jasperreports-server*` directory under or beside `installDir`. A neighbour counts only when
      it holds this platform's `js-ant` script **and** `default_master.properties`, which only a
      configured installation has. An upgrade package unpacked next to the server has neither
      configured, so it is not mistaken for the installed tree.
   4. Two matching neighbours are refused as ambiguous, with both paths named.
3. Discovery lists at most two directories and never walks a tree, so a slow or dead share costs a
   bounded number of file-system calls.
4. `init` reports the directory with its source and writes it into `config.yaml`, which makes the
   choice explicit from then on. `--buildomatic-dir <dir>` supplies it; a hint that cannot be
   reached is reported, not written.
5. Upgrade: the plan resolves the tree once. An unreachable configured directory or an ambiguous
   search refuses the plan (exit 2), because backing up and later restoring a guessed tree is worse
   than not starting. A tree that resolves inside the target package is refused too. Rollback uses
   the path the upgrade run recorded in its point-B manifest, so the archive goes back where it came
   from even if the configuration changed since; runs without a manifest resolve from the
   configuration. `PointB.moveTree` already falls back to a streaming copy across file stores, so a
   restore onto another volume or a share needs no new mechanism.
6. On Windows, `doctor` warns when the directory is a UNC path: `cmd.exe` refuses a UNC working
   directory and switches to the Windows directory, and the vendor batch wrappers run with the
   buildomatic directory as their working directory. The remediation is a mapped drive letter or a
   `mklink /D` link (creating one needs Administrator or Developer Mode), with
   `server.buildomaticDir` pointing at it. jrsctl does not rewrite the invocation itself (spec §0:
   never modify or second-guess vendor scripts).

## Consequences

- A host whose buildomatic sits anywhere reachable passes `doctor` once the key is set, and usually
  without it, because `init` finds the common layouts.
- `TomcatLayout.buildomaticDir` stays as a fact about the Tomcat layout but no longer drives any
  decision.
- `doctor`'s `vendor` item names the rule that found the directory (`server.buildomaticDir`,
  `inside the installation directory (server.installDir)`, `next to the installation directory (server.installDir)`, ...; the wording was made plainer in #69, after a field tester read "beside server.tomcatDir" as "inside the Tomcat folder"), so an operator can see a discovered
  choice before relying on it.
- Verified read-only on 2026-09-14 against real shares on both operating systems (#31). Not
  verified: vendor export/import and upgrade with the tree on a share, and whether the 10.0.0
  batch wrappers survive a UNC working directory at all; the warning assumes they do not.
- Found on the #31 throwaway: a relocated buildomatic loses what the installer puts next to
  it. `bin\do-js-setup.bat` looks for Ant only at `..\apache-ant` and the export/import
  wrappers for Java only at `..\java`, both relative to buildomatic; without them the tools run
  whatever `ant` and `java` are on `PATH` (no Ant at all, or the machine's default JDK, which
  failed with `UnsupportedClassVersionError` while the wrapper still exited 0). jrsctl now puts
  `vendor.javaHome/bin` first on `PATH` for vendor tools, and `doctor` warns when no Ant can be
  found. Raw UNC paths fail at once, as the warning assumed (#32).
