package com.jaspersoft.jrsctl.core.platform;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link ServiceController} for installs controlled by a script: the bundled {@code ctlscript}
 * ({@code ctlscript stop tomcat}) or Tomcat's own {@code catalina} script ({@code catalina stop}).
 * Invariants: state is derived from running processes, not from the script, because neither script
 * reports status reliably; a Tomcat counts as running when a JVM with {@code catalina} in its
 * command line belongs to the watched directory, which is the install dir for {@code ctlscript}
 * (the script's parent) and the Tomcat dir for {@code catalina} (the parent of its {@code bin}), so
 * a Tomcat elsewhere on the machine is never mistaken for this one; the script is invoked with the
 * operation timeout and the remainder is spent polling.
 */
public final class ScriptServiceController extends PollingServiceController {

  private final ServiceConfig.Kind kind;
  private final Path script;
  private final Path watchedDir;
  private final TomcatProcessFinder processes;

  public ScriptServiceController(ProcessRunner runner, ServiceConfig.Kind kind, Path script) {
    this(runner, kind, script, TomcatProcesses.INSTANCE, DEFAULT_POLL_INTERVAL);
  }

  ScriptServiceController(
      ProcessRunner runner,
      ServiceConfig.Kind kind,
      Path script,
      TomcatProcessFinder processes,
      Duration pollInterval) {
    super(runner, pollInterval);
    this.kind = requireNonNull(kind, "kind");
    if (kind != ServiceConfig.Kind.CTLSCRIPT && kind != ServiceConfig.Kind.CATALINA) {
      throw new IllegalArgumentException("not a script kind: " + kind);
    }
    this.script = requireNonNull(script, "script").toAbsolutePath().normalize();
    this.watchedDir = watchedDirOf(kind, this.script);
    this.processes = requireNonNull(processes, "processes");
  }

  /** {@code <install>/ctlscript.sh} or {@code <tomcat>/bin/catalina.sh}. */
  static Path watchedDirOf(ServiceConfig.Kind kind, Path script) {
    Path parent = Optional.ofNullable(script.getParent()).orElse(script);
    return switch (kind) {
      case CTLSCRIPT -> parent;
      case CATALINA -> Optional.ofNullable(parent.getParent()).orElse(parent);
      case WINDOWS_SERVICE, SYSTEMD, MANUAL ->
          throw new IllegalArgumentException("not a script kind: " + kind);
    };
  }

  /** The directory whose Tomcat this controller watches. */
  public Path watchedDir() {
    return watchedDir;
  }

  @Override
  public State state() {
    return TomcatState.of(processes, Optional.of(watchedDir));
  }

  @Override
  public State stop(Duration timeout) {
    long start = System.nanoTime();
    if (state() == State.STOPPED) {
      return State.STOPPED;
    }
    invoke(command("stop"), timeout);
    return await(State.STOPPED, remaining(start, timeout));
  }

  @Override
  public State start(Duration timeout) {
    long start = System.nanoTime();
    if (state() == State.RUNNING) {
      return State.RUNNING;
    }
    invoke(command("start"), timeout);
    return await(State.RUNNING, remaining(start, timeout));
  }

  List<String> command(String operation) {
    return switch (kind) {
      case CTLSCRIPT -> List.of(script.toString(), operation, "tomcat");
      case CATALINA -> List.of(script.toString(), operation);
      case WINDOWS_SERVICE, SYSTEMD, MANUAL -> throw new IllegalStateException(kind.name());
    };
  }

  @Override
  public String describe() {
    return switch (kind) {
      case CTLSCRIPT -> "ctlscript " + script;
      case CATALINA -> "catalina script " + script;
      case WINDOWS_SERVICE, SYSTEMD, MANUAL -> throw new IllegalStateException(kind.name());
    };
  }
}
