package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import java.time.Duration;
import java.util.Optional;

/**
 * Service lifecycle steps shared by the apply and rollback plans (spec Â§8.2 steps 6 and 10, Â§8.3).
 * Invariants: stop and start are idempotent because they consult the controller's state first; each
 * one's compensation is the opposite operation; {@code WaitForServer} polls {@code serverInfo} with
 * the HTTP retry cadence and gives up after ten minutes (spec Â§6.5).
 */
final class ServiceSteps {

  static final String STOP = "stop-service";
  static final String START = "start-service";
  static final String WAIT = "wait-for-server";
  static final Duration WAIT_CAP = Duration.ofMinutes(10);

  private ServiceSteps() {}

  /** Stops the service; compensation starts it again. */
  static final class StopService implements Step {
    private final HotfixRuntime rt;
    private final String phase;
    private final String suffix;

    StopService(HotfixRuntime rt, String phase, String suffix) {
      this.rt = rt;
      this.phase = phase;
      this.suffix = suffix;
    }

    @Override
    public String id() {
      return STOP + suffix;
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
      return stop(rt);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return start(rt);
    }
  }

  /** Starts the service; compensation stops it again. */
  static final class StartService implements Step {
    private final HotfixRuntime rt;
    private final String phase;
    private final String suffix;

    StartService(HotfixRuntime rt, String phase, String suffix) {
      this.rt = rt;
      this.phase = phase;
      this.suffix = suffix;
    }

    @Override
    public String id() {
      return START + suffix;
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
      return "timeout " + rt.serviceTimeout().toSeconds() + "s";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return controllerCheck(rt);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return start(rt);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return stop(rt);
    }
  }

  /** Polls {@code serverInfo} until the server answers; read-only. */
  static final class WaitForServer implements Step {
    private final HotfixRuntime rt;
    private final String phase;
    private final String suffix;

    WaitForServer(HotfixRuntime rt, String phase, String suffix) {
      this.rt = rt;
      this.phase = phase;
      this.suffix = suffix;
    }

    @Override
    public String id() {
      return WAIT + suffix;
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
          // Must be refreshIdentity(): identity() memoises, so polling it after a service stop
          // returns the pre-stop value and this step would pass without contacting the server.
          ServerIdentity identity = rt.refreshIdentity();
          out.emit(
              new Event.Log(
                  rt.clock().instant(),
                  ctx.runId(),
                  Optional.of(id()),
                  phase,
                  Event.Log.Level.INFO,
                  "server answered: " + identity.version() + " " + identity.edition()));
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
              "check the server log; the run is rolled back");
        }
        out.emit(
            new Event.Log(
                rt.clock().instant(),
                ctx.runId(),
                Optional.of(id()),
                phase,
                Event.Log.Level.DEBUG,
                "not yet: " + problem + "; retry in " + delay.toSeconds() + "s"));
        rt.sleeper().sleep(delay);
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  static CheckResult controllerCheck(HotfixRuntime rt) {
    try {
      ServiceController.State state = rt.controller().state();
      if (state == ServiceController.State.UNKNOWN) {
        return CheckResult.fail(
            "service state cannot be determined (" + rt.controller().describe() + ")",
            "check service.kind and service.name in config.yaml");
      }
      return CheckResult.pass();
    } catch (ConfigException e) {
      return CheckResult.fail(e.getMessage(), e.remediation());
    }
  }

  static StepResult stop(HotfixRuntime rt) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.stop(rt.serviceTimeout());
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
          "stop the service by hand, then re-run");
    } catch (RuntimeException e) {
      return Failures.recoverable(
          "cannot stop the service: " + Failures.describe(e), "check service.* in config.yaml");
    }
  }

  static StepResult start(HotfixRuntime rt) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.start(rt.serviceTimeout());
      if (result == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      return Failures.recoverable(
          "service did not start within "
              + rt.serviceTimeout().toSeconds()
              + "s (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "start the service by hand and check its log");
    } catch (RuntimeException e) {
      return Failures.recoverable(
          "cannot start the service: " + Failures.describe(e), "check service.* in config.yaml");
    }
  }
}
