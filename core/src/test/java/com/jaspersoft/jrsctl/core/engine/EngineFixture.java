package com.jaspersoft.jrsctl.core.engine;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.event.EventBus;
import com.jaspersoft.jrsctl.core.event.RecordingSink;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Everything an engine test needs: a temp home, an open store, a bus, a fixed clock, a runner. */
public final class EngineFixture implements AutoCloseable {

  public static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");

  public final JrsctlHome home;
  public final StateStore store;
  public final EventBus bus = new EventBus();
  public final RecordingSink sink = new RecordingSink();
  public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  public final List<Duration> sleeps = new ArrayList<>();
  public final List<String> trace = new ArrayList<>();
  public final Runner runner;

  public EngineFixture(Path root) {
    this.home = new JrsctlHome(root);
    this.store = StateStore.open(home, clock);
    bus.subscribe(sink);
    this.runner = new Runner(store, bus, clock, sleeps::add);
  }

  public Context context(String runId) {
    return new Context(
        runId, home, new FakePlatform(home.root()), new CancellationToken(), Map.of());
  }

  public FakeStep step(String id, String phase) {
    return new FakeStep(id, phase, trace);
  }

  public static Plan plan(String planId, Step... steps) {
    return new Plan(planId, Arrays.asList(steps), summary(), fingerprint());
  }

  public static PlanSummary summary() {
    return new PlanSummary(
        "hotfix apply",
        "server-1",
        List.of(),
        List.of(),
        false,
        List.of(),
        Map.of(),
        "rest",
        List.of());
  }

  public static PlanFingerprint fingerprint() {
    return PlanFingerprint.of(Map.of("server", "srv-1", "bundle", "sha256:abc"));
  }

  @Override
  public void close() {
    store.close();
  }
}
