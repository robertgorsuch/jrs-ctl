package com.jaspersoft.jrsctl.app;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * A yes/no question for the operator. Invariants: the answer is read from the console when there is
 * one, otherwise from stdin; end of input or anything but {@code y}/{@code yes} means no, so an
 * unattended run can never confirm by accident.
 */
final class Confirm {

  private Confirm() {}

  static boolean ask(PrintWriter out, String question) {
    Optional<Console> console = Terminal.console();
    String answer;
    if (console.isPresent()) {
      console.get().printf("%s", question);
      answer = console.get().readLine();
    } else {
      out.print(question);
      out.flush();
      try {
        BufferedReader in =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        answer = in.readLine();
      } catch (IOException e) {
        answer = null;
      }
    }
    if (answer == null) {
      out.println();
      return false;
    }
    String a = answer.strip().toLowerCase(Locale.ROOT);
    return a.equals("y") || a.equals("yes");
  }
}
