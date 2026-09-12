package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import java.io.IOException;
import java.util.Optional;

/**
 * The steps of the apply plan (spec §8.2) other than the service steps. Invariants: verify-phase
 * steps do all their work in {@code precheck} and mutate nothing, so a refusal ends the run with
 * exit code 2; every mutating step re-checks the current state before acting, so re-execution
 * converges; {@code AtomicSwap} restores from the snapshot and {@code ApplySql} runs the rollback
 * scripts in reverse when compensated. What each touched file looked like beforehand comes from
 * {@link PriorState}, that is from the run's own snapshot rather than from the plan, because {@code
 * upgrade --reapply-hotfixes} plans before the upgrade and applies after it.
 */
final class ApplySteps {

  static final String VERIFY = "verify";
  static final String BACKUP = "backup";
  static final String APPLY = "apply";
  static final String RECORD = "record";

  static final String VERIFY_SIGNATURE = "verify-signature";
  static final String VALIDATE_MANIFEST = "validate-manifest";
  static final String PREFLIGHT = "preflight";
  static final String RUN_PRECHECKS = "run-prechecks";
  static final String SNAPSHOT = "snapshot";
  static final String STAGE_FILES = "stage-files";
  static final String ATOMIC_SWAP = "atomic-swap";
  static final String APPLY_SQL = "apply-sql";
  static final String RUN_POSTCHECKS = "run-postchecks";
  static final String RECORD_INSTALLED = "record-installed";

  static final String AUDIT_ALLOW_UNSIGNED = "hotfix.allow-unsigned";
  static final String AUDIT_APPLIED = "hotfix.applied";
  static final String AUDIT_ROLLED_BACK = "hotfix.rolled-back";

  private ApplySteps() {}

  /** Read-only base for the verify phase. */
  abstract static class ReadOnly implements Step {
    final HotfixRuntime rt;
    final ApplyInput in;

    ReadOnly(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    HotfixBundle bundle(Context ctx) throws IOException {
      return HotfixBundle.open(in.bundleDir(ctx));
    }

    void log(Context ctx, EventSink out, Event.Log.Level level, String message) {
      out.emit(
          new Event.Log(
              rt.clock().instant(), ctx.runId(), Optional.of(id()), phase(), level, message));
    }
  }

  /*
   * Where the steps live, in plan order (roadmap item 17, mirroring the upgrade package):
   *   verify  1-4   HotfixVerifySteps: VerifySignature, ValidateManifest, Preflight, RunChecks
   *   backup  5     HotfixBackupSteps: TakeSnapshot
   *   apply   7-9   HotfixApplyPhaseSteps: StageFiles, AtomicSwap, ApplySql
   *   record  12    HotfixRecordSteps: RecordInstalled
   * The service steps of 6 and 10 come from ops.service.ServiceSteps, which the upgrade shares.
   */
}
