package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.ops.service.ServiceSteps;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaitForServerCancelTest {

  @TempDir Path tmp;

  /**
   * Review finding 1.11, remainder: the wait-for-server backoff slept without the token, so Ctrl-C
   * during a retry delay of the ten-minute wait was noticed only when the delay ended.
   */
  @Test
  void should_notice_cancellation_during_the_wait_backoff_rather_than_after_it()
      throws IOException {
    try (HotfixFixture f = HotfixFixture.create(tmp)) {
      f.fake.unreachable = true;
      CancellationToken token = new CancellationToken();
      CancellingSleeper sleeper = new CancellingSleeper(token);
      HotfixRuntime rt =
          new HotfixRuntime(
              f.services,
              new SnapshotStore(f.fake.home, f.services.platform().files(), f.fake.clock),
              f.jdbc,
              new KeyRing(f.fake.home),
              path -> 200,
              sleeper);
      Step wait = ServiceSteps.waitForServer(rt, "apply", ServiceSteps.WAIT);
      Context ctx = new Context("r-wait", f.fake.home, f.services.platform(), token, Map.of());

      assertThatThrownBy(() -> wait.execute(ctx, EventSink.discard()))
          .isInstanceOf(CancellationToken.CancelledException.class);
      assertThat(sleeper.slept)
          .as("the wait ends within one slice, not after the whole backoff")
          .isLessThanOrEqualTo(Sleeper.SLICE);
    }
  }

  /** Books every requested sleep and cancels the token on the first one, like Ctrl-C mid-wait. */
  static final class CancellingSleeper implements Sleeper {
    private final CancellationToken token;
    Duration slept = Duration.ZERO;

    CancellingSleeper(CancellationToken token) {
      this.token = token;
    }

    @Override
    public void sleep(Duration duration) {
      slept = slept.plus(duration);
      token.cancel("operator");
    }
  }
}
