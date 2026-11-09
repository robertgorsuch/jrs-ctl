package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pauses the report jobs an import added (issue #264, ADR-0050): the jobs present after the import
 * that {@link RecordJobs} did not list before it. A full export carries report jobs, and imported
 * into a server that runs beside the source they fire on both and deliver every report twice; the
 * vendor's remedy is {@code POST /rest_v2/jobs/pause} before the scheduler runs them. Invariants:
 * the step sits in its own phase after the import, so what it does never rolls a finished import
 * back; it is best effort: a pause the server refuses is a WARN naming the ids and the call to make
 * by hand, and the step still succeeds, because the import is done either way; pausing a paused job
 * is a no-op on the server, so a second execution pauses the same ids again without harm and
 * rewrites the same record; the ids it paused go to {@link #recordFile}, and compensation resumes
 * exactly those, never the jobs that were there before, as often as it is asked (resuming a running
 * job is a no-op too); no record means nothing was paused, so there is nothing to resume.
 */
final class PauseImportedJobs implements Step {

  static final String ID = "jobs.pause-imported";
  static final String PHASE = "jobs";
  static final String FILE = "jobs-paused.txt";

  static Path recordFile(Context ctx) {
    return ctx.home().runDir(ctx.runId()).resolve(FILE);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Pause the report jobs the import added";
  }

  @Override
  public String phase() {
    return PHASE;
  }

  @Override
  public String detail() {
    return "POST /rest_v2/jobs/pause for the jobs present now that were not before the import;"
        + " a refused pause is a warning, not a rollback";
  }

  @Override
  public boolean mutating() {
    return true;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (!Files.isRegularFile(RecordJobs.recordFile(ctx))) {
      return CheckResult.fail(
          "no listing of the jobs before the import (" + RecordJobs.recordFile(ctx) + ")",
          "run the import again; the listing step writes it before the import");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Set<String> before;
    try {
      before =
          new TreeSet<>(Files.readAllLines(RecordJobs.recordFile(ctx), StandardCharsets.UTF_8));
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot read " + RecordJobs.recordFile(ctx) + ": " + e.getMessage(),
              "run the import again"));
    }
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    List<String> added;
    try {
      Set<String> after = new TreeSet<>(adapter.listJobIds());
      after.removeAll(before);
      added = List.copyOf(after);
    } catch (RuntimeException e) {
      EximLogs.warn(
          out,
          ctx,
          this,
          "cannot list the report jobs after the import ("
              + e.getMessage()
              + "); nothing was paused: compare the scheduler with the "
              + before.size()
              + " job(s) listed before and pause the new ones with POST /rest_v2/jobs/pause");
      return StepResult.ok();
    }
    if (added.isEmpty()) {
      EximLogs.info(out, ctx, this, "the import added no report jobs; nothing to pause");
      return write(ctx, List.of());
    }
    try {
      adapter.pauseJobs(added);
    } catch (RuntimeException e) {
      EximLogs.warn(
          out,
          ctx,
          this,
          "could not pause the "
              + added.size()
              + " report job(s) the import added ("
              + String.join(", ", added)
              + "): "
              + e.getMessage()
              + "; pause them by hand with POST /rest_v2/jobs/pause and that id list, or in the"
              + " scheduler UI, before the scheduler runs them");
      return StepResult.ok();
    }
    EximLogs.info(
        out,
        ctx,
        this,
        "paused "
            + added.size()
            + " report job(s) the import added: "
            + String.join(", ", added)
            + "; resume them with POST /rest_v2/jobs/resume once this server should run them");
    return write(ctx, added);
  }

  private StepResult write(Context ctx, List<String> paused) {
    Path file = recordFile(ctx);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, paused, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot write " + file + ": " + e.getMessage(),
              "free the run directory; the jobs "
                  + String.join(", ", paused)
                  + " are paused and are resumed with POST /rest_v2/jobs/resume"));
    }
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Path file = recordFile(ctx);
    if (!Files.isRegularFile(file)) {
      EximLogs.info(out, ctx, this, "no record of paused jobs: nothing to resume");
      return StepResult.ok();
    }
    List<String> paused;
    try {
      paused = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot read " + file + ": " + e.getMessage(),
              "resume the jobs the run paused with POST /rest_v2/jobs/resume"));
    }
    if (paused.isEmpty()) {
      return StepResult.ok();
    }
    try {
      ctx.service(JrsAdapter.class).resumeJobs(paused);
    } catch (RuntimeException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot resume the paused jobs " + String.join(", ", paused) + ": " + e.getMessage(),
              "resume them with POST /rest_v2/jobs/resume and that id list, or in the scheduler"
                  + " UI"));
    }
    EximLogs.info(
        out, ctx, this, "resumed the job(s) this run paused: " + String.join(", ", paused));
    return StepResult.ok();
  }
}
