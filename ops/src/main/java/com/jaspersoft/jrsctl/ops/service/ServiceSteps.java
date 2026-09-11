package com.jaspersoft.jrsctl.ops.service;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.RetryPolicy;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepFailure;
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
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The service stop, start and wait steps of every ops plan: hotfix apply and rollback (spec §8.2
 * steps 6 and 10, §8.3) and upgrade, reconcile and rollback (spec §10.2 steps 4, 9, 11). One
 * implementation, since the two copies that preceded it drifted (review finding 1.13: only one had
 * learnt not to start a service the operator had stopped, and it recorded that fact too late).
 * Invariants: stop and start consult the controller's state first and leave a service already in
 * the wanted state alone; a stop writes a run-scoped marker <em>before</em> it acts, so its
 * compensation starts the service exactly when this run tried to stop it, including a stop that
 * went wrong half-way, and never when the operator had it stopped; a start's compensation stops it
 * again; wait-for-server polls the server through {@link ServiceRuntime#refreshIdentity()} with the
 * spec §6.5 backoff under a ten-minute cap and mutates nothing. A platform that refuses a command
 * outright ({@link ServiceControlException}) fails at once with the rights remediation rather than
 * being waited out (spec §5.3).
 */
public final class ServiceSteps {

  public static final String STOP = "stop-service";
  public static final String START = "start-service";
  public static final String WAIT = "wait-for-server";
  public static final Duration WAIT_CAP = Duration.ofMinutes(10);

  /** The platform refused the service command outright; waiting would not have helped. */
  public static final String RIGHTS_REMEDIATION =
      "run jrsctl with the rights the service manager demands (see the message), then re-run;"
          + " nothing was changed";

  private static final String CONFIG_REMEDIATION = "check service.* in config.yaml";

  private ServiceSteps() {}

  public static Step stop(ServiceRuntime rt, String phase, String id) {
    return new StopService(rt, phase, id);
  }

  public static Step start(ServiceRuntime rt, String phase, String id) {
    return new StartService(rt, phase, id);
  }

  public static Step waitForServer(ServiceRuntime rt, String phase, String id) {
    return new WaitForServer(rt, phase, id);
  }

  /** Precheck shared by stop and start: the service must be identifiable and its state known. */
  public static CheckResult controllerCheck(ServiceRuntime rt) {
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
      return CheckResult.fail("cannot query the service: " + describe(e), CONFIG_REMEDIATION);
    }
  }

  /** Stops the service unless it is stopped already; a refusal or a timeout is recoverable. */
  public static StepResult stop(ServiceRuntime rt, BooleanSupplier cancelled) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.stop(rt.serviceTimeout(), cancelled);
      if (result == ServiceController.State.STOPPED) {
        return StepResult.ok();
      }
      return recoverable(
          "service did not stop within "
              + rt.serviceTimeout().toSeconds()
              + "s (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "stop the service by hand or raise service.stopTimeoutSeconds, then run again");
    } catch (ServiceControlException e) {
      return recoverable(e.getMessage(), RIGHTS_REMEDIATION);
    } catch (RuntimeException e) {
      return recoverable("cannot stop the service: " + describe(e), CONFIG_REMEDIATION);
    }
  }

  /** Starts the service unless it is running already, waiting up to {@link #WAIT_CAP}. */
  public static StepResult start(ServiceRuntime rt, BooleanSupplier cancelled) {
    try {
      ServiceController controller = rt.controller();
      if (controller.state() == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      ServiceController.State result = controller.start(WAIT_CAP, cancelled);
      if (result == ServiceController.State.RUNNING) {
        return StepResult.ok();
      }
      return recoverable(
          "service did not start within "
              + WAIT_CAP.toMinutes()
              + " minutes (state "
              + result
              + ", "
              + controller.describe()
              + ")",
          "check the Tomcat log and start the service by hand");
    } catch (ServiceControlException e) {
      return recoverable(e.getMessage(), RIGHTS_REMEDIATION);
    } catch (RuntimeException e) {
      return recoverable("cannot start the service: " + describe(e), CONFIG_REMEDIATION);
    }
  }

  static String fileSafe(String stepId) {
    return stepId.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static StepResult recoverable(String cause, String nextAction) {
    return StepResult.failed(StepFailure.recoverable(cause, nextAction));
  }

  private static String describe(Exception e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }

  private static void log(
      ServiceRuntime rt,
      Context ctx,
      EventSink out,
      Step step,
      Event.Log.Level level,
      String message) {
    out.emit(
        new Event.Log(
            rt.clock().instant(),
            ctx.runId(),
            Optional.of(step.id()),
            step.phase(),
            level,
            message));
  }

  /** Stops the service; compensation starts it only if this run tried to stop it. */
  private static final class StopService implements Step {
    private final ServiceRuntime rt;
    private final String phase;
    private final String id;

    StopService(ServiceRuntime rt, String phase, String id) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.phase = Objects.requireNonNull(phase, "phase");
      this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Run-scoped marker "this run stopped the service". Step ids may hold characters a file name
     * cannot (hotfix rollback ids carry a colon, illegal on Windows), so the id is made safe.
     */
    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(fileSafe(id) + ".stopped");
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
        return recoverable("cannot query the service: " + describe(e), CONFIG_REMEDIATION);
      }
      if (before == ServiceController.State.STOPPED) {
        log(rt, ctx, out, this, Event.Log.Level.INFO, "service already stopped");
        return StepResult.ok();
      }
      // The marker goes down before the stop is attempted (review 1.13): a stop that goes wrong
      // half-way must still be undone by starting the service, and nothing has changed yet if the
      // marker cannot be written.
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "stopped", StandardCharsets.UTF_8);
      } catch (IOException e) {
        return recoverable(
            "cannot record the service stop in " + marker(ctx) + ": " + e.getMessage(),
            "check that the run directory is writable; the service was not touched");
      }
      return stop(rt, ctx.cancel()::isCancelled);
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
        log(
            rt,
            ctx,
            out,
            this,
            Event.Log.Level.INFO,
            "service was not stopped by this run; leaving it as is");
        return StepResult.ok();
      }
      StepResult result = start(rt, ctx.cancel()::isCancelled);
      if (result instanceof StepResult.Ok) {
        try {
          Files.deleteIfExists(marker(ctx));
        } catch (IOException e) {
          log(
              rt,
              ctx,
              out,
              this,
              Event.Log.Level.WARN,
              "cannot delete " + marker(ctx) + ": " + e.getMessage());
        }
      }
      return result;
    }
  }

  /** Starts the service; compensation stops it again. */
  private static final class StartService implements Step {
    private final ServiceRuntime rt;
    private final String phase;
    private final String id;

    StartService(ServiceRuntime rt, String phase, String id) {
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

  /** Polls {@code serverInfo}, uncached, until the server answers; read-only. */
  private static final class WaitForServer implements Step {
    private final ServiceRuntime rt;
    private final String phase;
    private final String id;

    WaitForServer(ServiceRuntime rt, String phase, String id) {
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
          // refreshIdentity(), never identity(): the adapter memoises identity(), so after a
          // service stop it would answer from before the stop and this step would pass without
          // reaching the server.
          ServerIdentity identity = rt.refreshIdentity();
          log(
              rt,
              ctx,
              out,
              this,
              Event.Log.Level.INFO,
              "server answered: " + identity.version() + " " + identity.edition());
          return StepResult.ok();
        } catch (JrsUnreachableException | RestException | ConfigException e) {
          problem = e.getMessage();
        }
        attempt++;
        Duration delay = policy.delayBefore(attempt);
        waited = waited.plus(delay);
        if (waited.compareTo(WAIT_CAP) > 0) {
          return recoverable(
              "server did not answer within " + WAIT_CAP.toMinutes() + " minutes: " + problem,
              "check the Tomcat and jasperserver logs; the service may still be starting");
        }
        log(
            rt,
            ctx,
            out,
            this,
            Event.Log.Level.DEBUG,
            "not yet: " + problem + "; retry in " + delay.toSeconds() + "s");
        rt.sleeper().sleep(delay, ctx.cancel());
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}
