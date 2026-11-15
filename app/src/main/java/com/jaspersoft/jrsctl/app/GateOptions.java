package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Option;

/**
 * The CI gating options of the report commands {@code doctor}, {@code smoke} and {@code import
 * verify} (issue #308, ADR-0058). Invariants: {@link #exit} never lowers the report's own exit
 * code, so an exit 6 (unsupported) stays 6 and a failure stays 2; with {@code --fail-on warn} a
 * report that would exit 0 exits 2 when any item is a WARN; the default ({@code fail}) leaves every
 * exit code as the report computed it.
 */
final class GateOptions {

  /** The lowest status that fails the command. */
  enum FailOn {
    WARN,
    FAIL
  }

  @Option(
      names = "--fail-on",
      paramLabel = "<status>",
      description =
          "The lowest item status that fails the command: fail (default) or warn. With warn, a"
              + " WARN item exits 2, for a CI gate.")
  FailOn failOn = FailOn.FAIL;

  @Option(
      names = "--junit",
      paramLabel = "<file>",
      description =
          "Also write the report as JUnit XML to <file>, one testcase per item, for a"
              + " pipeline's test view. Replaces the file.")
  Path junit;

  /** The command's exit code: the report's own, or 2 when {@code --fail-on warn} meets a WARN. */
  int exit(int reportExit, List<ReportItem> items) {
    if (reportExit != ExitCodes.SUCCESS) {
      return reportExit;
    }
    if (failOn == FailOn.WARN
        && items.stream().anyMatch(i -> i.status() == ReportItem.Status.WARN)) {
      return ExitCodes.PRECHECK_FAILED;
    }
    return reportExit;
  }

  Optional<Path> junitFile() {
    return Optional.ofNullable(junit);
  }

  boolean warnFails() {
    return failOn == FailOn.WARN;
  }

  /**
   * Writes the JUnit file when {@code --junit} was given and returns the exit code to use: {@code
   * exit} itself, or 2 when the file could not be written and {@code exit} was 0, so a pipeline
   * never reads a missing test file as a pass.
   */
  int writeJunit(
      String command, List<ReportItem> items, int exit, PrintWriter err, Redactor redactor) {
    if (junit == null) {
      return exit;
    }
    try {
      JunitReport.write(junit, command, items, warnFails(), redactor);
      return exit;
    } catch (IOException | RuntimeException e) {
      err.println(
          redactor.redact(
              "jrsctl: cannot write the JUnit report " + junit + ": " + e.getMessage()));
      err.flush();
      return exit == ExitCodes.SUCCESS ? ExitCodes.PRECHECK_FAILED : exit;
    }
  }
}
