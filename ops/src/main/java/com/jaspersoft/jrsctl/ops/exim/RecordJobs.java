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
import java.util.TreeSet;

/**
 * The report jobs the server holds before an import (issue #264, ADR-0050), so that {@link
 * PauseImportedJobs} can tell afterwards which ones the import added. Invariants: read-only on the
 * server; writes the sorted id list to {@link #recordFile} in the run directory, the same content
 * however often it runs; nothing to compensate; a listing that cannot be taken fails the step
 * before the import, because without it the pause could only pause every job or none.
 */
final class RecordJobs implements Step {

  static final String ID = "backup.jobs-listing";
  static final String FILE = "jobs-before.txt";

  static Path recordFile(Context ctx) {
    return ctx.home().runDir(ctx.runId()).resolve(FILE);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "List report jobs before the import";
  }

  @Override
  public String phase() {
    return DefaultExportImportOperations.BACKUP_PHASE;
  }

  @Override
  public String detail() {
    return "GET /rest_v2/jobs, so the pause after the import touches only the jobs it added";
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    List<String> ids;
    try {
      ids = List.copyOf(new TreeSet<>(ctx.service(JrsAdapter.class).listJobIds()));
    } catch (RuntimeException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot list the server's report jobs: "
                  + e.getMessage()
                  + "; without the listing the pause after the import could not tell the new jobs"
                  + " from the old",
              "check that the scheduler answers GET /rest_v2/jobs, or run without --pause-jobs"));
    }
    Path file = recordFile(ctx);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, ids, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot write " + file + ": " + e.getMessage(),
              "free the run directory, then run again"));
    }
    EximLogs.info(out, ctx, this, ids.size() + " report job(s) on the server before the import");
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
