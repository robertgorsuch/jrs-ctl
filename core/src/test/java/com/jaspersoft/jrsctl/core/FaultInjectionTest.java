package com.jaspersoft.jrsctl.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.platform.FailingFileOps;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.TerminalState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review finding 5.4: the fault cases nothing provoked before. A write that fails half-way through
 * a restore, two connections writing one {@code state.db}, and a host clock corrected backwards
 * under the retention and plan-age rules.
 */
class FaultInjectionTest {

  private final FileOps real = Platforms.detect().files();

  // ---- a write that fails half-way -----------------------------------------------------------

  @Test
  void should_stop_at_the_failing_file_and_leave_no_staging_behind_when_a_restore_fails(
      @TempDir Path homeDir, @TempDir Path install) throws IOException {
    JrsctlHome home = new JrsctlHome(homeDir);
    List<Path> files = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      Path f = install.resolve("lib").resolve("jar-" + i + ".jar");
      Files.createDirectories(f.getParent());
      Files.writeString(f, "original " + i, StandardCharsets.UTF_8);
      files.add(f);
    }
    SnapshotStore store = new SnapshotStore(home, real, Clock.systemUTC());
    Snapshot snapshot = store.create("r-1", "swap", files, install);
    for (Path f : files) {
      Files.writeString(f, "changed", StandardCharsets.UTF_8);
    }

    // the second file's replace fails, the way a full disk or a revoked ACL would fail it
    SnapshotStore failing = new SnapshotStore(home, new FailingFileOps(real, 2), Clock.systemUTC());

    assertThatThrownBy(() -> failing.restore(snapshot))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("simulated I/O failure");

    assertThat(Files.readString(files.get(0), StandardCharsets.UTF_8)).isEqualTo("original 1");
    assertThat(Files.readString(files.get(1), StandardCharsets.UTF_8))
        .as("the file whose replace failed is untouched, not half-written")
        .isEqualTo("changed");
    try (var listing = Files.list(install.resolve("lib"))) {
      assertThat(listing.map(p -> p.getFileName().toString()))
          .as("no staging file is left beside the target")
          .noneMatch(n -> n.contains(".jrsctl-restore"));
    }

    // the same restore through real file operations still converges
    store.restore(snapshot);
    for (int i = 0; i < files.size(); i++) {
      assertThat(Files.readString(files.get(i), StandardCharsets.UTF_8))
          .isEqualTo("original " + (i + 1));
    }
  }

  // ---- two writers on one state.db -----------------------------------------------------------

  @Test
  void should_serialise_two_connections_writing_the_same_state_db(@TempDir Path homeDir)
      throws Exception {
    JrsctlHome home = new JrsctlHome(homeDir);
    int rowsEach = 40;
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

    Runnable writer =
        () -> {
          try (StateStore store = StateStore.open(home, Clock.systemUTC())) {
            ready.countDown();
            go.await();
            for (int i = 0; i < rowsEach; i++) {
              store.audit("test", "concurrent.write", Thread.currentThread().getName() + " " + i);
            }
          } catch (Throwable t) {
            failures.add(t);
          }
        };
    Thread a = new Thread(writer, "writer-a");
    Thread b = new Thread(writer, "writer-b");
    a.start();
    b.start();
    assertThat(ready.await(30, TimeUnit.SECONDS)).as("opens: %s", failures).isTrue();
    go.countDown();
    a.join(60_000);
    b.join(60_000);

    assertThat(failures).as("busy_timeout must absorb the contention").isEmpty();
    try (StateStore store = StateStore.open(home, Clock.systemUTC())) {
      assertThat(store.integrity()).isEqualTo("ok");
      assertThat(store.auditRows(1000))
          .filteredOn(e -> e.action().equals("concurrent.write"))
          .hasSize(2 * rowsEach);
    }
  }

  // ---- a clock corrected backwards -------------------------------------------------------------

  @Test
  void should_survive_a_host_clock_corrected_backwards(@TempDir Path homeDir) {
    JrsctlHome home = new JrsctlHome(homeDir);
    MovableClock clock = new MovableClock(Instant.parse("2026-09-12T12:00:00Z"));
    try (StateStore store = StateStore.open(home, clock)) {
      store.recordRunStart("r-future", "hotfix.apply", Optional.empty(), clock.instant());
      store.recordRunEnd("r-future", clock.instant(), TerminalState.SUCCEEDED, 0);

      // the operator notices the host was an hour fast and corrects it
      clock.move(Duration.ofHours(-1));
      store.recordRunStart("r-now", "hotfix.apply", Optional.empty(), clock.instant());

      assertThat(store.run("r-future").orElseThrow().startedAt())
          .as("the earlier row keeps the time it was written with")
          .isEqualTo(Instant.parse("2026-09-12T12:00:00Z"));
      assertThat(store.run("r-now").orElseThrow().startedAt())
          .isEqualTo(Instant.parse("2026-09-12T11:00:00Z"));
      assertThat(store.pendingRuns()).extracting(r -> r.runId()).containsExactly("r-now");
      assertThat(store.integrity()).isEqualTo("ok");
    }
  }

  /** A clock a test moves in either direction, the way {@code ntpd} corrects a host. */
  private static final class MovableClock extends Clock {
    private Instant now;

    MovableClock(Instant start) {
      this.now = start;
    }

    void move(Duration by) {
      now = now.plus(by);
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
      return now;
    }
  }
}
