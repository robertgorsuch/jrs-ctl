# ADR-0016: Opt-in forced stop of the watched Tomcat JVM for the script kinds

Status: accepted, 2026-09-14. Amends spec §5.1 (the `service:` block) and §5.3 (service control).
Issue #42.

## Context

With `service.kind: catalina`, `catalina stop` returns within a second and Tomcat logs that its
protocol handlers are destroyed, but the JVM does not exit. A thread dump of a JasperReports Server
10.0.0 PRO JVM on Windows showed seven non-daemon threads left behind by the webapp, among them
`reportQueueTransfer1` and several `pool-N-thread-1` threads. `ctlscript` can do the same where it
delegates to `catalina`.

jrsctl decides whether Tomcat runs from the process list (ADR-0014), so it correctly keeps seeing
the JVM as running. Every stop therefore waits `service.stopTimeoutSeconds` and fails, and vendor
export and import, `WEB-INF/lib` hotfixes and upgrades cannot proceed. `windows-service` and
`systemd` are not affected, because the service manager ends the JVM with the service.

The workaround used while running #31 was a small stop script: a graceful `catalina stop`, then
ending only this Tomcat's JVM after sixty seconds.

## Decision

1. A new optional key, `service.forceStopAfterSeconds`, applies to the `catalina` and `ctlscript`
   kinds only. It is off when absent. `Config.toServiceConfig()` refuses it for any other kind,
   and refuses a value that is not below `service.stopTimeoutSeconds`.
2. When it is set and the watched Tomcat is still running that many seconds after the stop script
   returned, `ScriptServiceController` ends, once and forcibly, every JVM the process scan
   attributes to the watched directory, then keeps polling for the remainder of the stop timeout.
3. It never ends a JVM whose command line it cannot read, a Tomcat of another directory, or
   anything when the stop was cancelled. Each process it ends, or fails to end, is logged at WARN
   with its pid and the key that caused it.
4. The operator guide also documents the stop-script pattern, for operators who prefer to keep
   that decision in their own script.

## Alternatives

- **Always force.** Rejected: ending an operator's JVM must be a decision the operator took, and
  a JVM that is still flushing to the repository database should get the grace period the operator
  chose.
- **Documentation only.** Rejected: every `catalina` operator would write the same script, and the
  script cannot tell a lingering JVM from a slow shutdown any better than jrsctl can.

## Consequences

- A forced stop skips whatever the lingering threads were doing. The grace period is the
  operator's to size; the default is off.
- On Windows a JVM run by another account cannot be ended without elevation. The request is
  refused, the refusal is logged, and the stop times out as before.
- `ServiceConfig` and `Config.Service` gain an optional component; their previous constructors
  remain and mean "off".
