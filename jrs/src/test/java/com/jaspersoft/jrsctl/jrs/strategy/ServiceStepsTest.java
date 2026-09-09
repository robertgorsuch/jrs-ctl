package com.jaspersoft.jrsctl.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsctl.jrs.RecordingSink;
import com.jaspersoft.jrsctl.jrs.TestConfigs;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceStepsTest {

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Config config;

  @BeforeEach
  void setUp() {
    fx = new StrategyFixture(tmp);
    config = StrategyFixture.vendorConfig(tmp.resolve("jrs"), Optional.of(tmp.resolve("jdk")));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Context ctx() {
    return fx.context(config, new FakeJrsAdapter());
  }

  @Test
  void should_not_call_controller_when_service_already_stopped() {
    fx.service.stop(Duration.ZERO);
    Step stop = ServiceSteps.stop("apply");
    Context ctx = ctx();

    StepResult result = stop.execute(ctx, EventSink.discard());
    StepResult comp = stop.compensate(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(comp).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.STOPPED);
    assertThat(Files.exists(RunFiles.in(ctx, "apply.stop-service.stopped"))).isFalse();
  }

  @Test
  void should_stop_record_marker_and_start_on_compensate_when_service_running() {
    Step stop = ServiceSteps.stop("apply");
    Context ctx = ctx();

    StepResult result = stop.execute(ctx, EventSink.discard());
    assertThat(Files.exists(RunFiles.in(ctx, "apply.stop-service.stopped"))).isTrue();
    assertThat(stop.postcheck(ctx)).isInstanceOf(CheckResult.Pass.class);
    StepResult comp = stop.compensate(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(comp).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.RUNNING);
    assertThat(stop.id()).isEqualTo("apply.stop-service");
  }

  @Test
  void should_be_noop_when_start_called_on_running_service() {
    Step start = ServiceSteps.start("apply");

    StepResult result = start.execute(ctx(), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).isEmpty();
    assertThat(start.id()).isEqualTo("apply.start-service");
  }

  @Test
  void should_start_and_stop_on_compensate_when_service_stopped() {
    fx.service.stop(Duration.ZERO);
    Step start = ServiceSteps.start("apply");
    Context ctx = ctx();

    StepResult result = start.execute(ctx, EventSink.discard());
    StepResult comp = start.compensate(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(comp).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start", "stop");
  }

  @Test
  void should_fail_precheck_when_service_kind_not_configured() {
    Config noService = TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC);

    CheckResult result =
        ServiceSteps.stop("apply").precheck(fx.context(noService, new FakeJrsAdapter()));

    assertThat(result).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) result).message()).contains("service.kind");
  }

  @Test
  void should_wait_until_repository_answers_when_server_restarting() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();
    AtomicInteger n = new AtomicInteger();
    adapter.listFolderBehaviour =
        () -> {
          if (n.incrementAndGet() < 3) {
            throw new JrsUnreachableException(
                URI.create("http://x"), "connection refused", "wait", null);
          }
          return List.of("/public");
        };
    Step wait = ServiceSteps.waitForServer("apply", fx.polling);

    StepResult result = wait.execute(fx.context(config, adapter), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(adapter.listFolderCalls).isEqualTo(3);
    assertThat(wait.mutating()).isFalse();
    assertThat(wait.id()).isEqualTo("apply.wait-for-server");
  }

  @Test
  void should_fail_recoverably_and_log_progress_when_server_never_answers() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();
    adapter.listFolderBehaviour =
        () -> {
          throw new JrsUnreachableException(URI.create("http://x"), "refused", "wait", null);
        };
    Clock stepping = new SteppingClock(StrategyFixture.NOW, Duration.ofSeconds(31));
    Polling polling =
        new Polling(
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofHours(1),
            Duration.ofSeconds(30),
            stepping,
            Sleeper.none());
    Step wait = ServiceSteps.waitForServer("apply", polling);
    RecordingSink sink = new RecordingSink();

    StepResult result = wait.execute(fx.context(config, adapter), sink);

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure().cause()).contains("10 minutes");
    assertThat(sink.of(Event.Log.class)).isNotEmpty();
    assertThat(sink.logMessages()).anyMatch(m -> m.contains("server start in progress"));
  }

  @Test
  void should_throw_cancelled_when_token_cancelled_before_poll() {
    CancellationToken token = new CancellationToken();
    token.cancel("operator");
    Context ctx =
        new Context(
            StrategyFixture.RUN,
            fx.home,
            fx.platform,
            token,
            java.util.Map.of(
                Config.class,
                config,
                com.jaspersoft.jrsctl.jrs.api.JrsAdapter.class,
                new FakeJrsAdapter()));

    assertThatThrownBy(
            () -> ServiceSteps.waitForServer("apply", fx.polling).execute(ctx, EventSink.discard()))
        .isInstanceOf(CancellationToken.CancelledException.class);
  }

  /** Advances by a fixed step on every {@link #instant()} call. */
  static final class SteppingClock extends Clock {
    private Instant now;
    private final Duration step;

    SteppingClock(Instant start, Duration step) {
      this.now = start;
      this.step = step;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      Instant current = now;
      now = now.plus(step);
      return current;
    }
  }
}
