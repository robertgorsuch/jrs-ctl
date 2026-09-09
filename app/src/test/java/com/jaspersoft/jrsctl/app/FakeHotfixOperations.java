package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link HotfixOperations} for CLI tests: returns small plans of recording steps, one of which
 * can be made to fail, and a verify report whose three verdicts are switchable.
 */
public final class FakeHotfixOperations implements HotfixOperations {

  public static final String ID = "JRS-8.2.0-HF-0001";
  public static final String TITLE = "Fake scheduler fix";
  public static final List<String> APPLY_STEPS =
      List.of(
          "verify-signature",
          "validate-manifest",
          "snapshot",
          "stage-files",
          "atomic-swap",
          "record-installed");

  public volatile boolean signatureValid = true;
  public volatile boolean hashesValid = true;
  public volatile boolean applicable = true;
  public volatile Optional<String> failStep = Optional.empty();
  public volatile Optional<RuntimeException> planFailure = Optional.empty();
  public volatile List<HotfixInstalled> installed = List.of();

  public final List<String> executed = new CopyOnWriteArrayList<>();
  public final List<String> compensated = new CopyOnWriteArrayList<>();
  public volatile String lastPlanId = "";
  public volatile Optional<Path> lastBundle = Optional.empty();
  public volatile boolean lastAllowUnsigned;
  public volatile Optional<String> lastRollbackId = Optional.empty();
  public volatile boolean lastCascade;

  @Override
  public Path build(Path bundleDir, SecretRef privateKeyRef, Path out) {
    try {
      Files.writeString(out, "fake bundle from " + bundleDir, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  @Override
  public VerifyReport verify(Path bundle) {
    return new VerifyReport(
        signatureValid,
        signatureValid ? Optional.of("customer") : Optional.empty(),
        hashesValid,
        hashesValid ? List.of() : List.of("payload/x.jar: sha256 mismatch"),
        applicable,
        applicable ? List.of() : List.of("server 9.0.0 is outside >=8.2.0 <8.3.0"),
        ID,
        TITLE);
  }

  @Override
  public Plan planApply(Path bundle, ApplyOptions options) {
    planFailure.ifPresent(
        e -> {
          throw e;
        });
    lastBundle = Optional.of(bundle);
    lastAllowUnsigned = options.allowUnsigned();
    List<Step> steps =
        List.of(
            step("verify-signature", "verify", "Verify signature", "key customer"),
            step("validate-manifest", "verify", "Validate manifest", ""),
            step("snapshot", "backup", "Snapshot files", "1 file"),
            step("stage-files", "apply", "Stage files", "staging under runs/"),
            step("atomic-swap", "apply", "Atomic swap", ""),
            step("record-installed", "record", "Record installed", ""));
    PlanSummary summary =
        new PlanSummary(
            "hotfix.apply",
            ID,
            List.of(Path.of("webapps/jasperserver-pro/scripts/jrsctl-fix.js")),
            List.of(),
            false,
            List.of(Path.of("snapshots/snap-1")),
            Map.of("apply", "snapshot snap-1"),
            "snapshot",
            List.of("no service restart needed"));
    lastPlanId = "fake-apply-" + UUID.randomUUID();
    return new Plan(
        lastPlanId,
        steps,
        summary,
        PlanFingerprint.of(Map.of("bundle", bundle.toString(), "server", "8.2.0 PRO")));
  }

  @Override
  public Plan planRollback(String hotfixId, RollbackOptions options) {
    planFailure.ifPresent(
        e -> {
          throw e;
        });
    lastRollbackId = Optional.of(hotfixId);
    lastCascade = options.cascade();
    List<Step> steps =
        List.of(
            step("restore-snapshot", "apply", "Restore snapshot", "snap-1"),
            step("record-rolled-back", "record", "Record rolled back", ""));
    PlanSummary summary =
        new PlanSummary(
            "hotfix.rollback",
            hotfixId,
            List.of(Path.of("webapps/jasperserver-pro/scripts/jrsctl-fix.js")),
            List.of(),
            false,
            List.of(),
            Map.of(),
            "snapshot",
            List.of());
    lastPlanId = "fake-rollback-" + UUID.randomUUID();
    return new Plan(lastPlanId, steps, summary, PlanFingerprint.of(Map.of("hotfix", hotfixId)));
  }

  @Override
  public List<HotfixInstalled> list() {
    return installed;
  }

  private Step step(String id, String phase, String title, String detail) {
    return new FakeStep(id, phase, title, detail);
  }

  /** Records execution and compensation; fails when its id is {@link #failStep}. */
  private final class FakeStep implements Step {
    private final String id;
    private final String phase;
    private final String title;
    private final String detail;

    FakeStep(String id, String phase, String title, String detail) {
      this.id = id;
      this.phase = phase;
      this.title = title;
      this.detail = detail;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return title;
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return detail;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      executed.add(id);
      if (failStep.map(id::equals).orElse(false)) {
        return StepResult.failed(
            new StepFailure.Recoverable(
                "simulated failure of " + id,
                List.of(Path.of("webapps/jasperserver-pro/scripts/jrsctl-fix.js")),
                List.of(),
                List.of(Path.of("snapshots/snap-1")),
                "inspect the fake and retry"));
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      compensated.add(id);
      return StepResult.ok();
    }
  }
}
