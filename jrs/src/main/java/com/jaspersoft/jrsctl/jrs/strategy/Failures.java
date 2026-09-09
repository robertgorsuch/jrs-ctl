package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** Shorthand for the {@link StepFailure}s the strategy steps produce. */
final class Failures {

  private Failures() {}

  static StepResult recoverable(String cause, List<Path> paths, String nextAction) {
    return StepResult.failed(
        new StepFailure.Recoverable(cause, List.copyOf(paths), List.of(), List.of(), nextAction));
  }

  static StepResult recoverable(String cause, List<Path> paths, List<URI> uris, String nextAction) {
    return StepResult.failed(
        new StepFailure.Recoverable(
            cause, List.copyOf(paths), List.copyOf(uris), List.of(), nextAction));
  }

  static StepResult recoverableWithBackups(
      String cause, List<Path> paths, List<Path> backups, String nextAction) {
    return StepResult.failed(
        new StepFailure.Recoverable(
            cause, List.copyOf(paths), List.of(), List.copyOf(backups), nextAction));
  }

  static StepResult retryable(String cause, List<URI> uris, String nextAction) {
    return StepResult.failed(
        new StepFailure.Retryable(cause, List.of(), List.copyOf(uris), List.of(), nextAction));
  }

  static String describe(RuntimeException e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }
}
