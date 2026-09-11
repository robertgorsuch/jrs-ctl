package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.ServiceControlException;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Service stop/start/wait steps for the upgrade plans (spec §10.2 steps 4, 9, 11 and the rollback
 * plan). Invariants: stop and start are idempotent (a service already in the wanted state is left
 * alone); a stop records a marker file in the run directory so that its compensation only starts
 * the service if this run stopped it; wait-for-server polls {@code serverInfo} with the spec §6.5
 * backoff under a 10-minute cap and never mutates anything.
 */
final class UpgradeServiceSteps {

  static final Duration WAIT_CAP = Duration.ofMinutes(10);

  /** The platform refused the service command outright; waiting would not have helped. */
  static final String RIGHTS_REMEDIATION =
      "run jrsctl with the rights the service manager demands (see the message), then re-run;"
          + " nothing was changed";

  private UpgradeServiceSteps() {}

  static Step stop(UpgradeRuntime rt, String phase, String id) {
    return new StopService(rt, phase, id);
  }

  static Step start(UpgradeRuntime rt, String phase, String id) {
    return new StartService(rt, phase, id);
  }

  static Step waitForServer(UpgradeRuntime rt, String phase, String id) {
    return new WaitForServer(rt, phase, id);
  }

  static CheckResult controllerCheck(UpgradeRuntime rt) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.UNKNOWN) {
        return CheckResult.fail(
            "service state cannot be determined (" + controller.describe() + ")",
            "check service.kind, service.name and service.scriptPath in config.yaml; the manual"
                + " kind needs an interactive session");
      }
      return CheckResult.pass();
    } catch (ConfigException e) {
      return CheckResult.fail(e.getMessage(), e.remediation());
    } catch (RuntimeException e) {
      return CheckResult.fail(
          "cannot query the service: " + Failures.describe(e), "check service.* in config.yaml");
    }
  }

  static StepResult stop(UpgradeRuntime rt, BooleanSupplier cancelled) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.stop(rt.serviceTimeout(), cancelled);
      if (result == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      return Failures.recoverable(
          "service did not stop within "
              + rt.serviceTimeout().toSeconds()
              + "s (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "stop the service by hand or raise service.stopTimeoutSeconds, then run again");
    } catch (ServiceControlException e) {
      return Failures.recoverable(e.getMessage(), RIGHTS_REMEDIATION);
    } catch (RuntimeException e) {
      return Failures.recoverable(
          "cannot stop the service: " + Failures.describe(e), "check service.* in config.yaml");
    }
  }

  static StepResult start(UpgradeRuntime rt, BooleanSupplier cancelled) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.start(WAIT_CAP, cancelled);
      if (result == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      return Failures.recoverable(
          "service did not start within "
              + WAIT_CAP.toMinutes()
              + " minutes (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "check the Tomcat log and start the service by hand");
    } catch (ServiceControlException e) {
      return Failures.recoverable(e.getMessage(), RIGHTS_REMEDIATION);
    } catch (RuntimeException e) {
      return Failures.recoverable(
          "cannot start the service: " + Failures.describe(e), "check service.* in config.yaml");
    }
  }

  private static final class StopService implements Step {
    private final UpgradeRuntime rt;
    private final String phase;
    private final String id;

    StopService(UpgradeRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(id + ".stopped");
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return "stop the JasperReports Server service";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "timeout " + rt.serviceTimeout().toSeconds() + "s";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return controllerCheck(rt);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      ServiceController.State before;
      try {
        before = rt.controller().state();
      } catch (RuntimeException e) {
        return Failures.recoverable(
            "cannot query the service: " + Failures.describe(e), "check service.* in config.yaml");
      }
      if (before == ServiceController.State.STOPPED) {
        Logs.info(rt, ctx, out, this, "service already stopped");
        return StepResult.ok();
      }
      StepResult result = stop(rt, ctx.cancel()::isCancelled);
      if (result instanceof StepResult.Ok) {
        try {
          Files.createDirectories(marker(ctx).getParent());
          Files.writeString(marker(ctx), "stopped", StandardCharsets.UTF_8);
        } catch (IOException e) {
          return Failures.recoverable(
              "service stopped but " + marker(ctx) + " could not be written: " + e.getMessage(),
              "check that the run directory is writable");
        }
      }
      return result;
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      ServiceController.State s = rt.controller().state();
      return s == ServiceController.State.STOPPED
          ? CheckResult.pass()
          : CheckResult.fail("service state is " + s + " after stop", "stop the service by hand");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      if (!Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "service was not stopped by this run; leaving it as is");
        return StepResult.ok();
      }
      StepResult result = start(rt, ctx.cancel()::isCancelled);
      if (result instanceof StepResult.Ok) {
        try {
          Files.deleteIfExists(marker(ctx));
        } catch (IOException e) {
          Logs.warn(rt, ctx, out, this, "cannot delete " + marker(ctx) + ": " + e.getMessage());
        }
      }
      return result;
    }
  }

  private static final class StartService implements Step {
    private final UpgradeRuntime rt;
    private final String phase;
    private final String id;

    StartService(UpgradeRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return "start the JasperReports Server service";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "timeout " + WAIT_CAP.toMinutes() + "m";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return controllerCheck(rt);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return start(rt, ctx.cancel()::isCancelled);
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      ServiceController.State s = rt.controller().state();
      return s == ServiceController.State.RUNNING
          ? CheckResult.pass()
          : CheckResult.fail("service state is " + s + " after start", "check the Tomcat log");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return stop(rt, ctx.cancel()::isCancelled);
    }
  }

  private static final class WaitForServer implements Step {
    private final UpgradeRuntime rt;
    private final String phase;
    private final String id;

    WaitForServer(UpgradeRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String title() {
      return "wait for the server to answer";
    }

    @Override
    public String phase() {
      return phase;
    }

    @Override
    public String detail() {
      return "GET /rest_v2/serverInfo, up to " + WAIT_CAP.toMinutes() + " min";
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
      RetryPolicy policy = RetryPolicy.HTTP_DEFAULT;
      Duration waited = Duration.ZERO;
      int attempt = 1;
      while (true) {
        ctx.cancel().checkpoint();
        String problem;
        try {
          ServerIdentity identity = rt.identity();
          Logs.info(
              rt,
              ctx,
              out,
              this,
              "server answered: " + identity.version() + " " + identity.edition());
          return StepResult.ok();
        } catch (JrsUnreachableException | RestException | ConfigException e) {
          problem = e.getMessage();
        }
        attempt++;
        Duration delay = policy.delayBefore(attempt);
        waited = waited.plus(delay);
        if (waited.compareTo(WAIT_CAP) > 0) {
          return Failures.recoverable(
              "server did not answer within " + WAIT_CAP.toMinutes() + " minutes: " + problem,
              "check the Tomcat and jasperserver logs; the service may still be starting");
        }
        Logs.emit(
            rt,
            ctx,
            out,
            this,
            Event.Log.Level.DEBUG,
            "not yet: " + problem + "; retry in " + delay.toSeconds() + "s");
        rt.sleeper().sleep(delay);
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}
