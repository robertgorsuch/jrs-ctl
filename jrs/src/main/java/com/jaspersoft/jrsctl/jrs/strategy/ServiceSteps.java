package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The service stop/start/wait steps every strategy and the ops layer's hotfix and upgrade plans
 * share (spec §5.3, §6.5, §9.2). Invariants: state is polled through the {@link ServiceController}
 * and never assumed; {@code stop} on a stopped service and {@code start} on a running one are
 * no-ops; {@code stop} records a run-scoped marker when it was the one to stop the service so its
 * compensation starts the service only in that case; {@code waitForServer} is read-only and treats
 * a successful authenticated repository listing as "up", because {@code JrsAdapter.identity()} is
 * cached and would not notice a restart. Step ids are {@code <phase>.stop-service}, {@code
 * <phase>.start-service} and {@code <phase>.wait-for-server}, unique per phase.
 */
public final class ServiceSteps {

  public static final Duration START_TIMEOUT = Duration.ofMinutes(10);

  private ServiceSteps() {}

  public static Step stop(String phase) {
    return new Stop(phase);
  }

  public static Step start(String phase) {
    return new Start(phase);
  }

  /** Polls the server for up to {@link Polling#SERVER_START_TIMEOUT} with the given backoff. */
  public static Step waitForServer(String phase, Polling polling) {
    return new WaitForServer(phase, polling.withTimeout(Polling.SERVER_START_TIMEOUT));
  }

  static String stopId(String phase) {
    return phase + ".stop-service";
  }

  static String startId(String phase) {
    return phase + ".start-service";
  }

  static String waitId(String phase) {
    return phase + ".wait-for-server";
  }

  private static CheckResult serviceConfigured(Context ctx) {
    try {
      ctx.service(Config.class).toServiceConfig();
      return CheckResult.pass();
    } catch (ConfigException e) {
      return CheckResult.fail(e.getMessage(), e.remediation());
    }
  }

  private static ServiceController controller(Context ctx) {
    ServiceConfig cfg = ctx.service(Config.class).toServiceConfig();
    return ctx.platform().services(cfg);
  }

  private static Duration stopTimeout(Context ctx) {
    return ctx.service(Config.class).toServiceConfig().stopTimeout();
  }

  private static final class Stop implements Step {
    private final String phase;

    Stop(String phase) {
      this.phase = Objects.requireNonNull(phase, "phase");
    }

    private Path marker(Context ctx) {
      return RunFiles.in(ctx, id() + ".stopped");
    }

    @Override
    public String id() {
      return stopId(phase);
    }

    @Override
    public String title() {
      return "Stop service";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return serviceConfigured(ctx);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      ServiceController c = controller(ctx);
      ServiceController.State before = c.state();
      if (before == ServiceController.State.STOPPED) {
        Logs.info(out, ctx, this, c.describe() + " already stopped");
        return StepResult.ok();
      }
      Duration timeout = stopTimeout(ctx);
      Logs.info(
          out, ctx, this, "stopping " + c.describe() + " (timeout " + timeout.toSeconds() + "s)");
      ServiceController.State after = c.stop(timeout, ctx.cancel()::isCancelled);
      if (after != ServiceController.State.STOPPED) {
        return Failures.recoverable(
            c.describe()
                + " did not stop within "
                + timeout.toSeconds()
                + "s (state "
                + after
                + ")",
            List.of(),
            "stop the service by hand or raise service.stopTimeoutSeconds, then run again");
      }
      try {
        RunFiles.write(marker(ctx), "stopped");
      } catch (IOException e) {
        return Failures.recoverable(
            "service stopped but the marker "
                + marker(ctx)
                + " could not be written: "
                + e.getMessage(),
            List.of(marker(ctx)),
            "check the run directory is writable");
      }
      return StepResult.ok();
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      ServiceController.State s = controller(ctx).state();
      return s == ServiceController.State.STOPPED
          ? CheckResult.pass()
          : CheckResult.fail("service state is " + s + " after stop", "stop the service by hand");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        if (RunFiles.read(marker(ctx)).isEmpty()) {
          Logs.info(out, ctx, this, "service was not stopped by this run; leaving it as is");
          return StepResult.ok();
        }
      } catch (IOException e) {
        // an unreadable marker is treated as "we stopped it": starting a running service is a no-op
      }
      ServiceController c = controller(ctx);
      ServiceController.State s = c.start(START_TIMEOUT, ctx.cancel()::isCancelled);
      if (s != ServiceController.State.RUNNING) {
        return Failures.recoverable(
            c.describe() + " did not start during rollback (state " + s + ")",
            List.of(),
            "start the service by hand");
      }
      try {
        RunFiles.delete(marker(ctx));
      } catch (IOException e) {
        // a stale marker is harmless; the next run has a new run directory
      }
      return StepResult.ok();
    }
  }

  private static final class Start implements Step {
    private final String phase;

    Start(String phase) {
      this.phase = Objects.requireNonNull(phase, "phase");
    }

    @Override
    public String id() {
      return startId(phase);
    }

    @Override
    public String title() {
      return "Start service";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "timeout " + START_TIMEOUT.toMinutes() + "m";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return serviceConfigured(ctx);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      ServiceController c = controller(ctx);
      if (c.state() == ServiceController.State.RUNNING) {
        Logs.info(out, ctx, this, c.describe() + " already running");
        return StepResult.ok();
      }
      Logs.info(out, ctx, this, "starting " + c.describe());
      ServiceController.State s = c.start(START_TIMEOUT, ctx.cancel()::isCancelled);
      if (s != ServiceController.State.RUNNING) {
        return Failures.recoverable(
            c.describe()
                + " did not start within "
                + START_TIMEOUT.toMinutes()
                + "m (state "
                + s
                + ")",
            List.of(),
            "check the Tomcat log and start the service by hand");
      }
      return StepResult.ok();
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      ServiceController.State s = controller(ctx).state();
      return s == ServiceController.State.RUNNING
          ? CheckResult.pass()
          : CheckResult.fail("service state is " + s + " after start", "check the Tomcat log");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      ServiceController c = controller(ctx);
      if (c.state() == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      ServiceController.State s = c.stop(stopTimeout(ctx));
      return s == ServiceController.State.STOPPED
          ? StepResult.ok()
          : Failures.recoverable(
              c.describe() + " did not stop during rollback (state " + s + ")",
              List.of(),
              "stop the service by hand");
    }
  }

  private static final class WaitForServer implements Step {
    private final String phase;
    private final Polling polling;

    WaitForServer(String phase, Polling polling) {
      this.phase = Objects.requireNonNull(phase, "phase");
      this.polling = Objects.requireNonNull(polling, "polling");
    }

    @Override
    public String id() {
      return waitId(phase);
    }

    @Override
    public String title() {
      return "Wait for server";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "timeout " + polling.timeout().toMinutes() + "m";
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
      JrsAdapter adapter = ctx.service(JrsAdapter.class);
      Polling.Outcome outcome =
          polling.until(
              ctx,
              out,
              this,
              "server start",
              () -> {
                try {
                  adapter.listFolder("/");
                  return new Polling.Tick.Done();
                } catch (JrsUnreachableException e) {
                  return new Polling.Tick.Continue(e.getMessage());
                } catch (RestException e) {
                  return new Polling.Tick.Continue("HTTP " + e.status());
                }
              });
      return switch (outcome) {
        case Polling.Outcome.Completed c -> {
          Logs.info(out, ctx, this, "server answered after " + c.attempts() + " attempt(s)");
          yield StepResult.ok();
        }
        case Polling.Outcome.Failed f ->
            Failures.recoverable(f.message(), List.of(), "check the server log");
        case Polling.Outcome.TimedOut t ->
            Failures.recoverable(
                "server did not answer within " + t.after().toMinutes() + " minutes",
                List.of(),
                "check the Tomcat and jasperserver logs; the service may still be starting");
      };
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}
