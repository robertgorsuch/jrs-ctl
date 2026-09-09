package com.jaspersoft.jrsctl.core.platform;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link ServiceController} for a systemd unit driven by {@code systemctl}. Invariants: state is
 * the first line of {@code systemctl is-active <unit>} ({@code active}, {@code inactive}, {@code
 * failed}, {@code activating}, {@code deactivating}); {@code stop}/{@code start} block for at most
 * the given timeout and then poll for the remainder, so a unit with a long {@code TimeoutStopSec}
 * is still reported truthfully.
 */
public final class SystemdServiceController extends PollingServiceController {

  private final String unit;

  public SystemdServiceController(ProcessRunner runner, String unit) {
    this(runner, unit, DEFAULT_POLL_INTERVAL);
  }

  SystemdServiceController(ProcessRunner runner, String unit, Duration pollInterval) {
    super(runner, pollInterval);
    this.unit = requireNonNull(unit, "unit");
  }

  @Override
  public State state() {
    Optional<Invocation> query = invoke(List.of("systemctl", "is-active", unit), QUERY_TIMEOUT);
    if (query.isEmpty()) {
      return State.UNKNOWN;
    }
    // is-active exits non-zero for every state but active; the text is what matters
    return parseState(query.get().text());
  }

  static State parseState(List<String> lines) {
    for (String line : lines) {
      String word = line.trim().toLowerCase(Locale.ROOT);
      if (word.isEmpty()) {
        continue;
      }
      return switch (word) {
        case "active", "reloading" -> State.RUNNING;
        case "inactive", "failed" -> State.STOPPED;
        case "activating" -> State.STARTING;
        case "deactivating" -> State.STOPPING;
        default -> State.UNKNOWN;
      };
    }
    return State.UNKNOWN;
  }

  @Override
  public State stop(Duration timeout) {
    long start = System.nanoTime();
    if (state() == State.STOPPED) {
      return State.STOPPED;
    }
    invoke(List.of("systemctl", "stop", unit), timeout);
    return await(State.STOPPED, remaining(start, timeout));
  }

  @Override
  public State start(Duration timeout) {
    long start = System.nanoTime();
    if (state() == State.RUNNING) {
      return State.RUNNING;
    }
    invoke(List.of("systemctl", "start", unit), timeout);
    return await(State.RUNNING, remaining(start, timeout));
  }

  @Override
  public String describe() {
    return "systemd unit " + unit;
  }
}
