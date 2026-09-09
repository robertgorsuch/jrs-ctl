package com.jaspersoft.jrsctl.core.platform;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for every {@link ServiceController}: issues the platform command, then polls {@link
 * #state()} until the wanted state appears or the deadline passes. Invariants: the returned state
 * is always the last observed one, never an assumption; polling interval defaults to one second and
 * is shortened for the final wait so the timeout is honoured to within one interval; a runner that
 * cannot start the command yields {@link ServiceController.State#UNKNOWN} instead of an exception.
 */
abstract class PollingServiceController implements ServiceController {

  private static final Logger LOG = LoggerFactory.getLogger(PollingServiceController.class);
  static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

  protected final ProcessRunner runner;
  private final Duration pollInterval;

  PollingServiceController(ProcessRunner runner, Duration pollInterval) {
    this.runner = requireNonNull(runner, "runner");
    this.pollInterval = requireNonNull(pollInterval, "pollInterval");
  }

  /** Polls until {@code wanted} is observed or {@code timeout} elapses; returns the last state. */
  protected final State await(State wanted, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      State current = state();
      long remaining = deadline - System.nanoTime();
      if (current == wanted || remaining <= 0) {
        return current;
      }
      long nap = Math.min(pollInterval.toNanos(), remaining);
      try {
        Thread.sleep(Duration.ofNanos(nap));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return current;
      }
    }
  }

  /**
   * Runs {@code command} with the given timeout, collecting every output line; empty when the
   * program could not be started at all.
   */
  protected final Optional<Invocation> invoke(List<String> command, Duration timeout) {
    List<ProcessRunner.OutputLine> lines = new ArrayList<>();
    Consumer<ProcessRunner.OutputLine> collect = lines::add;
    try {
      ProcessRunner.Result result =
          runner.run(
              new ProcessRunner.Request(command, Optional.empty(), java.util.Map.of(), timeout),
              collect);
      return Optional.of(new Invocation(result, List.copyOf(lines)));
    } catch (RuntimeException e) {
      LOG.warn("cannot run {}: {}", command.get(0), e.toString());
      return Optional.empty();
    }
  }

  static Duration remaining(long startNanos, Duration budget) {
    Duration used = Duration.ofNanos(System.nanoTime() - startNanos);
    Duration left = budget.minus(used);
    return left.isNegative() ? Duration.ZERO : left;
  }

  /** Result and captured output of one command. */
  record Invocation(ProcessRunner.Result result, List<ProcessRunner.OutputLine> lines) {
    List<String> text() {
      return lines.stream().map(ProcessRunner.OutputLine::text).toList();
    }
  }
}
