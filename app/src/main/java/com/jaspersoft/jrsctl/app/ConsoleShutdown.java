package com.jaspersoft.jrsctl.app;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the shutdown hook of {@code jrsctl console} does when the operator presses Ctrl-C (#57).
 * Invariants: every closer runs, in order, even when an earlier one throws (the failure is logged),
 * so the listener stops, the token file is deleted and the state store and run lock are released;
 * the process then ends through {@code halt} with {@link ExitCodes#SUCCESS}, the documented code of
 * a console stop, instead of the JVM's 130 or 143 after the hook returns; {@code halt} is the way
 * out because {@code System.exit} blocks once shutdown has begun, as {@link RunGuard} does for
 * runs.
 */
final class ConsoleShutdown implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(ConsoleShutdown.class);

  private final List<Runnable> closers;
  private final IntConsumer halt;

  ConsoleShutdown(List<Runnable> closers, IntConsumer halt) {
    this.closers = List.copyOf(closers);
    this.halt = Objects.requireNonNull(halt, "halt");
  }

  @Override
  public void run() {
    for (Runnable closer : closers) {
      try {
        closer.run();
      } catch (RuntimeException e) {
        LOG.warn("error while stopping the console: {}", e.toString());
      }
    }
    halt.accept(ExitCodes.SUCCESS);
  }
}
