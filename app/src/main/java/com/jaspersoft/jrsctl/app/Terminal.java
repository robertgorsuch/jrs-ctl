package com.jaspersoft.jrsctl.app;

import java.io.Console;
import java.util.Optional;

/**
 * The process console, if any. Invariant: on the JDK 21 runtime jrsctl ships with, a present
 * console means stdin and stdout are a terminal, which is what interactivity and colour decisions
 * are based on; there is no other source of that fact.
 */
final class Terminal {

  private Terminal() {}

  static Optional<Console> console() {
    return Optional.ofNullable(System.console());
  }

  static boolean present() {
    return console().isPresent();
  }
}
