package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The console's {@code hotfix.verify} as a one-step, read-only {@link Plan} so it runs, journals
 * and streams like every other operation (spec §8.4, §13.1). Invariants: the single step is not
 * mutating and has no compensation to do; its execution only calls {@link HotfixOperations#verify},
 * logs the three verdicts, and fails with a {@code Fatal} failure when the bundle is rejected,
 * which the runner reports as a failure before any change (exit 2 semantics); the fingerprint
 * covers the bundle path, size and modification time so a replaced bundle refuses to run under the
 * old plan.
 */
final class VerifyPlan {

  static final String STEP_ID = "verify-bundle";
  static final String PHASE = "verify";

  private VerifyPlan() {}

  static Plan build(HotfixOperations hotfix, Path bundle) {
    Path abs = bundle.toAbsolutePath().normalize();
    Step step = new VerifyStep(hotfix, abs);
    PlanSummary summary =
        new PlanSummary(
            OperationCatalog.HOTFIX_VERIFY,
            abs.getFileName().toString(),
            List.of(),
            List.of(),
            false,
            List.of(),
            Map.of(),
            "read-only verification",
            List.of("Nothing is changed: signature, hashes and applicability are checked only."));
    return new Plan(
        "verify-" + UUID.randomUUID(),
        List.of(step),
        summary,
        PlanFingerprint.of(Map.of("bundle", abs.toString(), "bundle-stat", stat(abs))));
  }

  private static String stat(Path file) {
    try {
      return Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return "missing";
    }
  }

  /** Runs the verification report and turns it into log lines plus a pass/fail result. */
  private static final class VerifyStep implements Step {

    private final HotfixOperations hotfix;
    private final Path bundle;

    VerifyStep(HotfixOperations hotfix, Path bundle) {
      this.hotfix = hotfix;
      this.bundle = bundle;
    }

    @Override
    public String id() {
      return STEP_ID;
    }

    @Override
    public String title() {
      return "Verify bundle";
    }

    @Override
    public String phase() {
      return PHASE;
    }

    @Override
    public String detail() {
      return bundle.toString();
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return Files.isRegularFile(bundle)
          ? CheckResult.pass()
          : CheckResult.fail("bundle " + bundle + " does not exist", "check the path");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      HotfixOperations.VerifyReport report = hotfix.verify(bundle);
      List<String> problems = new ArrayList<>();
      log(
          out,
          ctx,
          report.signatureValid(),
          "signature: signed by " + report.signedBy().orElse("a trusted key"),
          "signature: missing or not made by a trusted key",
          problems);
      log(
          out,
          ctx,
          report.hashesValid(),
          "hashes: every listed file matches its manifest hash",
          "hashes: " + String.join("; ", report.hashProblems()),
          problems);
      log(
          out,
          ctx,
          report.applicable(),
          "applicability: applies to the configured server",
          "applicability: " + String.join("; ", report.applicabilityProblems()),
          problems);
      out.emit(
          new Event.Log(
              Instant.now(),
              ctx.runId(),
              Optional.of(STEP_ID),
              PHASE,
              Event.Log.Level.INFO,
              "manifest: " + report.manifestId() + "  " + report.title()));
      if (!report.ok()) {
        return StepResult.failed(
            StepFailure.fatal(
                "bundle " + report.manifestId() + " rejected: " + String.join("; ", problems),
                "obtain the bundle again from its publisher, or add the signer's key with"
                    + " `jrsctl keys add`"));
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    private static void log(
        EventSink out,
        Context ctx,
        boolean ok,
        String passText,
        String failText,
        List<String> problems) {
      if (!ok) {
        problems.add(failText);
      }
      out.emit(
          new Event.Log(
              Instant.now(),
              ctx.runId(),
              Optional.of(STEP_ID),
              PHASE,
              ok ? Event.Log.Level.INFO : Event.Log.Level.ERROR,
              ok ? passText : failText));
    }
  }
}
