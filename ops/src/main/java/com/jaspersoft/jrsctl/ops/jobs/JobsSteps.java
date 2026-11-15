package com.jaspersoft.jrsctl.ops.jobs;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The two mutating steps of the {@code jobs} commands (issue #304, ADR-0056). Each lists the
 * server's jobs at execution time, computes its {@link JobSelection}, adds the ids it is about to
 * change to a record in the run directory, then makes one call. Invariants: the record is written
 * before the call, so a call that half-applied is still undone; the record only grows, because a
 * second execution finds the first one's jobs already changed and selects nothing new; pausing a
 * paused job and resuming a running one are no-ops on the server, so compensation, which changes
 * back exactly the recorded ids, is safe to repeat; a refused listing or call fails the step, since
 * the change is the whole operation (unlike the best-effort pause after an import, ADR-0050).
 */
public final class JobsSteps {

  /** The only phase of a jobs plan. */
  public static final String PHASE = "jobs";

  private JobsSteps() {}

  /** {@code jobs.pause}: pauses every job, or the recorded ones, that is not paused already. */
  public static final class Pause extends Base {

    public static final String ID = "jobs.pause";
    public static final String FILE = "jobs-paused.txt";

    public Pause(JobsInput input) {
      super(input);
    }

    @Override
    public String id() {
      return ID;
    }

    @Override
    public String title() {
      return input.from().isPresent()
          ? "Pause the recorded report jobs"
          : "Pause every report job on the server";
    }

    @Override
    public String detail() {
      return "POST /rest_v2/jobs/pause for the jobs not paused already; rollback resumes exactly"
          + " those";
    }

    @Override
    String file() {
      return FILE;
    }

    @Override
    JobSelection select(List<JobSummary> current, Optional<JobsRecord> record) {
      return record.isPresent()
          ? JobSelection.pauseFrom(record.get().jobs(), current)
          : JobSelection.pauseAll(current);
    }

    @Override
    void apply(JrsAdapter adapter, List<String> ids) {
      adapter.pauseJobs(ids);
    }

    @Override
    void undo(JrsAdapter adapter, List<String> ids) {
      adapter.resumeJobs(ids);
    }

    @Override
    String verb() {
      return "pause";
    }

    @Override
    String undoVerb() {
      return "resume";
    }
  }

  /** {@code jobs.resume}: resumes the jobs a record shows as active that are paused now. */
  public static final class Resume extends Base {

    public static final String ID = "jobs.resume";
    public static final String FILE = "jobs-resumed.txt";

    public Resume(JobsInput input) {
      super(input);
      if (input.from().isEmpty()) {
        throw new IllegalArgumentException("resume needs a record");
      }
    }

    @Override
    public String id() {
      return ID;
    }

    @Override
    public String title() {
      return "Resume the report jobs the record shows as active";
    }

    @Override
    public String detail() {
      return "POST /rest_v2/jobs/resume for the recorded active jobs paused now; jobs recorded as"
          + " paused stay paused; rollback pauses exactly those it resumed";
    }

    @Override
    String file() {
      return FILE;
    }

    @Override
    JobSelection select(List<JobSummary> current, Optional<JobsRecord> record) {
      return JobSelection.resumeFrom(record.orElseThrow().jobs(), current);
    }

    @Override
    void apply(JrsAdapter adapter, List<String> ids) {
      adapter.resumeJobs(ids);
    }

    @Override
    void undo(JrsAdapter adapter, List<String> ids) {
      adapter.pauseJobs(ids);
    }

    @Override
    String verb() {
      return "resume";
    }

    @Override
    String undoVerb() {
      return "pause";
    }
  }

  /** What the two steps share: select, record, call; compensate from the record. */
  abstract static sealed class Base implements Step permits Pause, Resume {

    final JobsInput input;

    Base(JobsInput input) {
      this.input = Objects.requireNonNull(input, "input");
    }

    abstract String file();

    abstract JobSelection select(List<JobSummary> current, Optional<JobsRecord> record);

    abstract void apply(JrsAdapter adapter, List<String> ids);

    abstract void undo(JrsAdapter adapter, List<String> ids);

    abstract String verb();

    abstract String undoVerb();

    Path recordFile(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(file());
    }

    @Override
    public final String phase() {
      return PHASE;
    }

    @Override
    public final CheckResult precheck(Context ctx) {
      if (input.from().isPresent()) {
        try {
          JobsRecord.read(input.from().get());
        } catch (JobsException e) {
          return CheckResult.fail(e.getMessage(), e.remediation());
        }
      }
      return CheckResult.pass();
    }

    @Override
    public final StepResult execute(Context ctx, EventSink out) {
      Optional<JobsRecord> record;
      try {
        record = input.from().map(JobsRecord::read);
      } catch (JobsException e) {
        return StepResult.failed(StepFailure.recoverable(e.getMessage(), e.remediation()));
      }
      JrsAdapter adapter = ctx.service(JrsAdapter.class);
      JobSelection selection;
      try {
        selection = select(adapter.listJobs(), record);
      } catch (RuntimeException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot list the server's report jobs: " + e.getMessage(),
                "check that the scheduler answers GET /rest_v2/jobs, then run again"));
      }
      for (JobSelection.Unmatched u : selection.unmatched()) {
        log(out, ctx, Event.Log.Level.WARN, "not matched, left alone: " + u.describe());
      }
      for (JobSummary j : selection.errorState()) {
        log(
            out,
            ctx,
            Event.Log.Level.WARN,
            "recorded in a state that is neither active nor paused, left alone: "
                + JobSelection.describe(j));
      }
      Path file = recordFile(ctx);
      Set<String> recorded = new LinkedHashSet<>();
      try {
        recorded.addAll(readIds(file));
        recorded.addAll(selection.change());
        Files.createDirectories(file.getParent());
        Files.write(file, List.copyOf(recorded), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot write " + file + ": " + e.getMessage(),
                "free the run directory, then run again; nothing was changed on the server"));
      }
      if (selection.change().isEmpty()) {
        log(out, ctx, Event.Log.Level.INFO, "no report job to " + verb());
        return StepResult.ok();
      }
      try {
        apply(adapter, selection.change());
      } catch (RuntimeException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "the server refused to "
                    + verb()
                    + " the report jobs "
                    + String.join(", ", selection.change())
                    + ": "
                    + e.getMessage(),
                "check the scheduler (POST /rest_v2/jobs/" + verb() + "), then run again"));
      }
      log(
          out,
          ctx,
          Event.Log.Level.INFO,
          verb()
              + "d "
              + selection.change().size()
              + " report job(s): "
              + String.join(", ", selection.change()));
      return StepResult.ok();
    }

    @Override
    public final StepResult compensate(Context ctx, EventSink out) {
      Path file = recordFile(ctx);
      List<String> ids;
      try {
        ids = readIds(file);
      } catch (IOException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot read " + file + ": " + e.getMessage(),
                undoVerb() + " the jobs this run changed with POST /rest_v2/jobs/" + undoVerb()));
      }
      if (ids.isEmpty()) {
        log(out, ctx, Event.Log.Level.INFO, "no record of changed jobs: nothing to " + undoVerb());
        return StepResult.ok();
      }
      try {
        undo(ctx.service(JrsAdapter.class), ids);
      } catch (RuntimeException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot "
                    + undoVerb()
                    + " the report jobs "
                    + String.join(", ", ids)
                    + ": "
                    + e.getMessage(),
                undoVerb()
                    + " them with POST /rest_v2/jobs/"
                    + undoVerb()
                    + " and that id list, or in the scheduler UI"));
      }
      log(
          out,
          ctx,
          Event.Log.Level.INFO,
          undoVerb() + "d the report job(s) this run changed: " + String.join(", ", ids));
      return StepResult.ok();
    }

    private static List<String> readIds(Path file) throws IOException {
      if (!Files.isRegularFile(file)) {
        return List.of();
      }
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .toList();
    }

    private void log(EventSink out, Context ctx, Event.Log.Level level, String message) {
      String text = ctx.has(Redactor.class) ? ctx.service(Redactor.class).redact(message) : message;
      out.emit(new Event.Log(Instant.now(), ctx.runId(), Optional.of(id()), phase(), level, text));
    }
  }
}
