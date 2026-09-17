package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #57: Ctrl-C on jrsctl console ended the JVM with the signal's 130 once the shutdown hook
 * returned; the documented code for a stop is 0.
 */
class ConsoleShutdownTest {

  @Test
  void should_close_everything_in_order_then_halt_with_zero() {
    List<String> events = new ArrayList<>();

    new ConsoleShutdown(
            List.of(() -> events.add("server"), () -> events.add("bootstrap")),
            code -> events.add("halt " + code))
        .run();

    assertThat(events).containsExactly("server", "bootstrap", "halt 0");
  }

  @Test
  void should_still_close_the_rest_and_halt_when_one_close_fails() {
    List<String> events = new ArrayList<>();

    new ConsoleShutdown(
            List.of(
                () -> {
                  throw new IllegalStateException("port already closed");
                },
                () -> events.add("bootstrap")),
            code -> events.add("halt " + code))
        .run();

    assertThat(events).containsExactly("bootstrap", "halt 0");
  }
}
