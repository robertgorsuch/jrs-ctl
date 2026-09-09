package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.Handles;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code POST /rest_v2/import} streaming the archive (spec §7.3, §9.4). Repository-mutating and
 * idempotent through the run-scoped {@code import-handle.txt}: a recorded task id is reused instead
 * of uploading again. Compensation is intentionally a no-op that only logs: the repository is
 * restored by the ops layer's {@code PreImportSnapshot} step, which re-imports the snapshot taken
 * before this phase (best effort, spec §9.4); this step cannot undo a server-side import itself.
 */
final class StartImport implements Step {

  static final String ID = "import.start";
  static final String ROLLBACK_NOTE =
      "repository rollback is handled by the pre-import snapshot step";

  private final ImportRequest request;

  StartImport(ImportRequest request) {
    this.request = Objects.requireNonNull(request, "request");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Start import task";
  }

  @Override
  public String phase() {
    return RestStrategy.IMPORT_PHASE;
  }

  @Override
  public String detail() {
    return request.archive() + (request.update() ? " (update)" : "");
  }

  @Override
  public RetryPolicy retryPolicy() {
    return RetryPolicy.HTTP_DEFAULT;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Path archive = request.archive();
    try {
      if (!Files.isRegularFile(archive) || Files.size(archive) <= 0) {
        return CheckResult.fail(
            "archive " + archive + " is missing or empty", "pass an export archive to import");
      }
    } catch (IOException e) {
      return CheckResult.fail("cannot inspect " + archive + ": " + e.getMessage(), "check path");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    try {
      Optional<String> existing = RunFiles.read(handleFile);
      if (existing.isPresent()) {
        Logs.info(out, ctx, this, "reusing import task " + existing.get());
        return StepResult.ok();
      }
      Handles.ImportHandle handle;
      try {
        handle = ctx.service(JrsAdapter.class).startImport(request, request.archive());
      } catch (JrsUnreachableException e) {
        return Failures.retryable(
            "server unreachable: " + e.getMessage(), List.of(e.url()), e.remediation());
      }
      RunFiles.write(handleFile, handle.id());
      Logs.info(out, ctx, this, "import task " + handle.id() + " started");
      return StepResult.ok();
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot record the import task handle: " + e.getMessage(),
          List.of(handleFile),
          "check that " + handleFile.getParent() + " is writable");
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Logs.info(out, ctx, this, ROLLBACK_NOTE);
    return StepResult.ok();
  }
}
