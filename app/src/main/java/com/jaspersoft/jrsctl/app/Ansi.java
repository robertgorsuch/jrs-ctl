package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.ops.ReportItem;
import java.util.Map;

/**
 * Text-output colouring. Invariants: colour is used only when stdout is a console, {@code NO_COLOR}
 * is unset and {@code --no-color} was not given; a status is always rendered as an ASCII icon plus
 * the word ({@code x FAIL}), so the meaning survives without colour and in any code page.
 */
final class Ansi {

  static final String ESC = String.valueOf((char) 27);
  static final String RESET = ESC + "[0m";
  static final String RED = ESC + "[31m";
  static final String YELLOW = ESC + "[33m";
  static final String GREEN = ESC + "[32m";
  static final String DIM = ESC + "[2m";

  private final boolean enabled;

  Ansi(boolean enabled) {
    this.enabled = enabled;
  }

  static Ansi forStdout(boolean noColor, Map<String, String> env) {
    boolean console = Terminal.present();
    boolean noColorEnv = env.containsKey("NO_COLOR");
    return new Ansi(console && !noColorEnv && !noColor);
  }

  boolean enabled() {
    return enabled;
  }

  /** {@code + PASS}, {@code ! WARN}, {@code x FAIL}, {@code - SKIP}; coloured when enabled. */
  String status(ReportItem.Status status) {
    String word =
        switch (status) {
          case PASS -> "+ PASS";
          case WARN -> "! WARN";
          case FAIL -> "x FAIL";
          case SKIP -> "- SKIP";
        };
    if (!enabled) {
      return word;
    }
    String colour =
        switch (status) {
          case PASS -> GREEN;
          case WARN -> YELLOW;
          case FAIL -> RED;
          case SKIP -> DIM;
        };
    return colour + word + RESET;
  }

  String dim(String text) {
    return enabled ? DIM + text + RESET : text;
  }
}
