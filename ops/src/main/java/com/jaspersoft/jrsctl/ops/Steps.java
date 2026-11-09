package com.jaspersoft.jrsctl.ops;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.util.Objects;
import java.util.Optional;

/**
 * What every step of every plan does the same way: emit a log event stamped with the run, the step
 * and the phase, with the message passed through the services' redactor first; name the scope a
 * vendor tool's output lines belong to; and build a recoverable failure. Invariants: no message
 * reaches an {@link EventSink} unredacted; nothing here touches the server or the file system.
 */
public final class Steps {

  private Steps() {}

  public static void info(Services s, Context ctx, EventSink out, Step step, String message) {
    emit(s, ctx, out, step, Event.Log.Level.INFO, message);
  }

  public static void warn(Services s, Context ctx, EventSink out, Step step, String message) {
    emit(s, ctx, out, step, Event.Log.Level.WARN, message);
  }

  public static void emit(
      Services s, Context ctx, EventSink out, Step step, Event.Log.Level level, String message) {
    Objects.requireNonNull(s, "s");
    out.emit(
        new Event.Log(
            s.clock().instant(),
            ctx.runId(),
            Optional.of(step.id()),
            step.phase(),
            level,
            s.redactor().redact(message)));
  }

  /** The run and step a vendor tool's streamed output belongs to. */
  public static VendorTools.LogScope scope(Context ctx, Step step) {
    return new VendorTools.LogScope(ctx.runId(), Optional.of(step.id()), step.phase());
  }

  public static StepResult recoverable(String cause, String nextAction) {
    return StepResult.failed(StepFailure.recoverable(cause, nextAction));
  }

  /** {@code ClassName: message}, or the class name alone when the exception has no message. */
  public static String describe(Exception e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }
}
