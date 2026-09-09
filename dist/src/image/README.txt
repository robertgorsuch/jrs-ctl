jrsctl ${project.version} (${dist.platform})
JasperReports Server lifecycle tool - Actian Jaspersoft

WHAT THIS IS
  A self-contained, portable installation of jrsctl: the application
  (lib/jrsctl.jar) plus a trimmed Java 21 runtime built with jlink
  (runtime/). Nothing else is required on the host: no JDK, no JAVA_HOME,
  no PowerShell or Python. Unpack it anywhere and run it from there.

LAYOUT
  bin/jrsctl.cmd            launcher for Windows
  bin/jrsctl                launcher for Linux
  lib/jrsctl.jar            the application
  runtime/                  bundled Java runtime (used only by jrsctl)
  README.txt                this file
  LICENSE-THIRD-PARTY.txt   licences of the bundled libraries and runtime
  MANIFEST.sha256           SHA-256 of every file above (integrity check)

QUICK START
  Windows:  bin\jrsctl.cmd --version
            bin\jrsctl.cmd selfcheck
            bin\jrsctl.cmd init --install-dir "C:\Jaspersoft\jasperreports-server-9.0"
            bin\jrsctl.cmd doctor
  Linux:    bin/jrsctl --version
            bin/jrsctl selfcheck
            bin/jrsctl init --install-dir /opt/jasperreports-server-9.0
            bin/jrsctl doctor

  jrsctl keeps its configuration, run journal and snapshots in its home
  directory: --home <dir>, else $JRSCTL_HOME, else %ProgramData%\jrsctl
  (Windows) or /var/lib/jrsctl (Linux), falling back to ~/.jrsctl.
  Run it on the JasperReports Server host as a user that may stop and
  start the server service.

VERIFYING THE DOWNLOAD
  Compare the archive with its .sha256 sidecar before unpacking:
    Windows:  certutil -hashfile jrsctl-${project.version}-${dist.platform}.${dist.archive.ext} SHA256
    Linux:    sha256sum -c jrsctl-${project.version}-${dist.platform}.${dist.archive.ext}.sha256
  After unpacking, MANIFEST.sha256 lists every file of this directory;
  `sha256sum -c MANIFEST.sha256` (Linux) verifies them all.

  Set JRSCTL_JAVA_OPTS to pass extra JVM options (for example a proxy or
  a truststore) to the bundled runtime.

DOCUMENTATION
  docs/operator-guide.md in the source repository, or `jrsctl help`.
