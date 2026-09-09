package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.Handles;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Polls {@code GET /rest_v2/import/{id}/state} until the task finishes (spec §7.3). Non-mutating; a
 * {@code FAILED} phase becomes a {@code Recoverable} failure with the server's message, which makes
 * the Runner compensate the phase (i.e. re-import the pre-import snapshot).
 */
final class PollImport implements Step {

  static final String ID = "import.poll";

  private final Polling polling;

  PollImport(Polling polling) {
    this.polling = Objects.requireNonNull(polling, "polling");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Wait for import task";
  }

  @Override
  public String phase() {
    return RestStrategy.IMPORT_PHASE;
  }

  @Override
  public String detail() {
    return "timeout " + polling.timeout().toMinutes() + "m";
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    try {
      if (RunFiles.read(handleFile).isEmpty()) {
        return CheckResult.fail(
            "no import task handle recorded in " + handleFile, "start the import again");
      }
    } catch (IOException e) {
      return CheckResult.fail(
          "cannot read " + handleFile + ": " + e.getMessage(), "check the run directory");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    String id;
    try {
      id = RunFiles.read(handleFile).orElseThrow();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot read " + handleFile + ": " + e.getMessage(),
          List.of(handleFile),
          "check the run directory");
    }
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    Handles.ImportHandle handle = new Handles.ImportHandle(id);
    Polling.Outcome outcome =
        polling.until(
            ctx,
            out,
            this,
            "import " + id,
            () -> {
              Handles.ImportStatus s = adapter.pollImport(handle);
              return switch (s.phase()) {
                case INPROGRESS -> new Polling.Tick.Continue(s.message().orElse(""));
                case READY -> new Polling.Tick.Done();
                case FAILED ->
                    new Polling.Tick.Failed(
                        s.message().orElse("import failed without a message")
                            + s.errorCode().map(c -> " (" + c + ")").orElse(""));
              };
            });
    return switch (outcome) {
      case Polling.Outcome.Completed c -> {
        Logs.info(out, ctx, this, "import " + id + " ready after " + c.attempts() + " poll(s)");
        yield StepResult.ok();
      }
      case Polling.Outcome.Failed f ->
          Failures.recoverable(
              "server reported import " + id + " failed: " + f.message(),
              List.of(),
              "check the jasperserver log for the import task; the pre-import snapshot is"
                  + " re-imported by rollback");
      case Polling.Outcome.TimedOut t ->
          Failures.recoverable(
              "import " + id + " still in progress after " + t.after().toMinutes() + " minutes",
              List.of(),
              "wait for the task to finish on the server, then verify the repository");
    };
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
