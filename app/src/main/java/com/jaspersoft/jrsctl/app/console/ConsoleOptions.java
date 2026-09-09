package com.jaspersoft.jrsctl.app.console;

import java.util.Objects;
import java.util.Optional;

/**
 * Flag overrides for the console listener (spec §13, §11.2). Invariant: an empty value means "use
 * {@code console.bind} / {@code console.port} from the configuration"; the options never carry the
 * token or any secret.
 */
public record ConsoleOptions(Optional<String> bind, Optional<Integer> port) {

  public ConsoleOptions {
    Objects.requireNonNull(bind, "bind");
    Objects.requireNonNull(port, "port");
    port.ifPresent(
        p -> {
          if (p < 0 || p > 65535) {
            throw new IllegalArgumentException("port must be within 0..65535");
          }
        });
  }

  public static ConsoleOptions fromConfig() {
    return new ConsoleOptions(Optional.empty(), Optional.empty());
  }
}
