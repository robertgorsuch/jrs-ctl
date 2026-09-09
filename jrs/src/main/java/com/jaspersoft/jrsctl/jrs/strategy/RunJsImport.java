package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs {@code js-import} with the service stopped (spec §7.4, §9.2). Repository-mutating; the
 * request's keystore options are handled by the preceding {@code ImportSourceKeystore} step, so
 * this invocation passes only the import switches. Compensation is a logged no-op: the repository
 * is restored by the ops layer's pre-import snapshot (spec §9.4), which this step cannot do itself.
 */
final class RunJsImport implements Step {

  static final String ID = "import.js-import";

  private final ImportRequest request;
  private final VendorAccess vendor;

  RunJsImport(ImportRequest request, VendorAccess vendor) {
    this.request = Objects.requireNonNull(request, "request");
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Run js-import";
  }

  @Override
  public String phase() {
    return VendorCliStrategy.IMPORT_PHASE;
  }

  @Override
  public String detail() {
    return request.archive() + (request.update() ? " (update)" : "");
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
    Optional<Buildomatic> b = vendor.locate(ctx);
    if (b.isEmpty()) {
      return Failures.recoverable(
          "buildomatic directory not found", List.of(), "set server.installDir");
    }
    Config config = ctx.service(Config.class);
    ImportRequest plain =
        new ImportRequest(
            request.archive(),
            request.update(),
            request.skipUserUpdate(),
            request.includeAccessEvents(),
            request.includeAuditEvents(),
            request.includeMonitoring(),
            request.includeSettings(),
            request.skipThemes(),
            Optional.empty(),
            Optional.empty());
    VendorRun run =
        vendor
            .tools()
            .apply(ctx)
            .importArchive(
                b.get(),
                plain,
                Optional.empty(),
                config.vendor().javaHome(),
                out,
                Logs.scope(ctx, this));
    return switch (run) {
      case VendorRun.Completed c ->
          c.ok()
              ? StepResult.ok()
              : Failures.recoverable(
                  "js-import exited with " + c.exitCode() + ": " + String.join(" | ", c.tail()),
                  List.of(request.archive()),
                  "check the buildomatic log; the pre-import snapshot is re-imported by rollback");
      case VendorRun.TimedOut t ->
          Failures.recoverable(
              "js-import did not finish within " + t.timeout().toMinutes() + " minutes",
              List.of(request.archive()),
              "check for a hung buildomatic process; the pre-import snapshot is re-imported by"
                  + " rollback");
      case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), List.of(), n.remediation());
    };
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Logs.info(out, ctx, this, StartImport.ROLLBACK_NOTE);
    return StepResult.ok();
  }
}
