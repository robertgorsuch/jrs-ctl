# ADR-0014: Windows reads Tomcat processes through CIM, and a blind scan is UNKNOWN

Status: accepted, 2026-09-14. Amends spec §5.3 (service control) for Windows. Issue #38.

## Context

The `catalina`, `ctlscript` and `manual` service kinds decide whether Tomcat runs by looking for
a JVM whose command line mentions `catalina` and the watched directory (`TomcatProcesses`, over
`ProcessHandle.allProcesses()`). On Linux the JDK reads `/proc/<pid>/cmdline`. On Windows it
returns no command line and no arguments for any process: on the development host (JDK 21.0.9,
Windows 11) 0 of 466 processes had one, including a Tomcat started by the same user.

`TomcatState` then found no Tomcat and returned `STOPPED`. The service steps skip a stop when the
state is already `STOPPED`, so on Windows with those kinds the stop before `js-export`/`js-import`
and before a `WEB-INF/lib` or `WEB-INF/classes` hotfix never happened, and the start afterwards
launched a second Tomcat and waited for a state it could not see. It surfaced while running #31
against a throwaway Tomcat. `windows-service` asks the service control manager and was not
affected, which is why the installer-bundle host never showed it.

The ways to read another process's command line on Windows without a new dependency are CIM
(`Win32_Process.CommandLine`), `wmic` (deprecated and removed from current Windows 11 images), or
reading the target's PEB through the native API (needs JNA or JNI; JNA is not approved, spec
§13.3).

## Decision

1. On Windows the process finder is `WindowsTomcatProcesses`: Windows PowerShell, run by absolute
   path (`%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`) with a fixed script passed
   as `-EncodedCommand`, as an argument list through `ProcessRunner`, lists `Win32_Process` rows
   for `java.exe`, `javaw.exe` and `tomcat*.exe`, each with the TCP ports the process listens on
   (`Get-NetTCPConnection -State Listen`). No operator input reaches the script. Paths and command
   lines are printed Base64-encoded UTF-8, so the console code page cannot corrupt them, and the
   output ends with a marker. Recognition is the same rule as on Linux
   (`TomcatProcesses.describe`). Linux keeps the JDK scan.
2. A scan that exits non-zero, times out, lacks the end marker or cannot list listening ports
   throws `TomcatScanException`. `TomcatState` turns that into `UNKNOWN`, never `STOPPED`; the
   service steps' precheck refuses on `UNKNOWN`, so nothing is mutated on a blind scan.
3. Without elevation CIM returns no command line for another account's processes, but listening
   ports and their owning process ids stay readable. A `java.exe` or `javaw.exe` that cannot be read
   is reported as opaque, with its ports. When no readable process belongs to the watched
   directory, an opaque JVM makes the state `UNKNOWN` only if it listens on one of the ports the
   watched Tomcat's `conf/server.xml` declares (the `Server` shutdown port and every connector), or
   if those ports cannot be determined; otherwise the state is `STOPPED`. A Tomcat started from the
   watched directory binds exactly those ports, so an unrelated Java service is not mistaken for it:
   the development host runs one under another account on port 8080, and an earlier draft of this
   rule, which counted every opaque JVM, made every stop time out there. An unreadable
   `tomcat*.exe` is left out: service wrappers belong to `windows-service`, which does not use this
   scan.
4. `lockHolder` and install-dir candidates from running Tomcats treat a failed scan as "nothing
   found"; they only name or suggest, and never decide whether to stop.

## Consequences

- `catalina`, `ctlscript` and `manual` work on Windows when jrsctl runs as the account that runs
  Tomcat, and unrelated Java processes of other accounts do not get in the way unless they hold
  the watched Tomcat's ports.
- If the watched Tomcat itself runs under another account, the state is `UNKNOWN` while it holds
  its ports, and every plan that stops the service refuses in precheck with "service state cannot
  be determined". Run jrsctl as that account, elevate, or use `windows-service`.
- Each state query starts PowerShell and lists listening ports, about 1 to 2 s. Polling runs once
  a second, so a wait simply polls less often.
- JasperReports Server 10.0.0 leaves non-daemon threads (`reportQueueTransfer1` and several
  executor pools) after its webapp stops, so a JVM started with `catalina.bat start` does not exit
  after `catalina.bat stop`; the Windows service survives this because procrun runs the JVM
  in-process. jrsctl correctly reports such a JVM as running and the stop times out and refuses.
  Operators of the `catalina` kind need a stop script that ends the JVM after a grace period; the
  #31 throwaway used one. This is recorded here, not solved.
- The rule "no shell" (spec §0) is kept in the sense it was written for: PowerShell receives a
  constant script, never a string built from input, and is invoked as a program with arguments.
