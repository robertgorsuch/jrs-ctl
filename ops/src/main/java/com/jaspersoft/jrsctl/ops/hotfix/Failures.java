package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import java.nio.file.Path;
import java.util.List;

/**
 * Builders for the {@link StepFailure} shapes hotfix steps report. Invariants: every failure names
 * the affected paths and backups it knows about, and its message never carries a secret (only
 * paths, ids, hashes and driver messages).
 */
final class Failures {

  private Failures() {}

  static StepResult recoverable(String cause, String nextAction) {
    return StepResult.failed(StepFailure.recoverable(cause, nextAction));
  }

  static StepResult recoverable(
      String cause, String nextAction, List<Path> affected, List<Path> backups) {
    return StepResult.failed(
        new StepFailure.Recoverable(cause, affected, List.of(), backups, nextAction));
  }

  static StepResult fatal(
      String cause, String nextAction, List<Path> affected, List<Path> backups) {
    return StepResult.failed(
        new StepFailure.Fatal(cause, affected, List.of(), backups, nextAction));
  }

  static String describe(Exception e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }
}
