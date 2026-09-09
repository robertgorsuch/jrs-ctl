package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import java.util.Objects;

/**
 * A step of another plan (a hotfix apply plan, spec §10.2 step 12) embedded in the reconcile phase
 * of an upgrade. Invariants: the id is prefixed with the hotfix slug so it stays unique in the
 * outer plan; the inner step sees a context whose run id is {@code <outerRunId>-hf-<slug>}, so its
 * bundle copy, staging, snapshot and state-store rows land in their own directories exactly as they
 * would for a standalone {@code hotfix apply}; every other property delegates unchanged.
 */
final class EmbeddedStep implements Step {

  static final String PREFIX = "reapply-";
  static final String RUN_SUFFIX = "-hf-";

  private final Step inner;
  private final String hotfixId;
  private final String slug;

  EmbeddedStep(Step inner, String hotfixId) {
    this.inner = Objects.requireNonNull(inner, "inner");
    this.hotfixId = Objects.requireNonNull(hotfixId, "hotfixId");
    this.slug = HotfixReconciler.slug(hotfixId);
  }

  static String subRunId(String runId, String hotfixId) {
    return runId + RUN_SUFFIX + HotfixReconciler.slug(hotfixId);
  }

  private Context sub(Context ctx) {
    return new Context(
        subRunId(ctx.runId(), hotfixId), ctx.home(), ctx.platform(), ctx.cancel(), ctx.services());
  }

  @Override
  public String id() {
    return PREFIX + slug + "-" + inner.id();
  }

  @Override
  public String title() {
    return "[" + hotfixId + "] " + inner.title();
  }

  @Override
  public String phase() {
    return Phases.RECONCILE;
  }

  @Override
  public String detail() {
    return inner.detail();
  }

  @Override
  public boolean irreversible() {
    return inner.irreversible();
  }

  @Override
  public boolean mutating() {
    return inner.mutating();
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return inner.precheck(sub(ctx));
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    return inner.execute(sub(ctx), out);
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    return inner.postcheck(sub(ctx));
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return inner.compensate(sub(ctx), out);
  }

  @Override
  public RetryPolicy retryPolicy() {
    return inner.retryPolicy();
  }
}
