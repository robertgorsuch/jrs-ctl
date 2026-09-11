package com.jaspersoft.jrsctl.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLockTest {

  private static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");

  @TempDir Path tmp;

  @Test
  void should_throw_lock_held_with_first_run_id_when_second_lock_is_taken_in_same_jvm() {
    JrsctlHome home = new JrsctlHome(tmp);
    try (RunLock first = new RunLock(home, "r-first", NOW)) {
      assertThat(first.runId()).isEqualTo("r-first");
      assertThatThrownBy(() -> new RunLock(home, "r-second", NOW))
          .isInstanceOf(LockHeldException.class)
          .satisfies(
              e -> {
                LockHeldException lhe = (LockHeldException) e;
                assertThat(lhe.holderRunId()).isEqualTo("r-first");
                assertThat(lhe.holderPid())
                    .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
              })
          .hasMessageContaining("r-first");
    }
  }

  @Test
  void should_write_holder_info_while_held_and_truncate_when_released() throws Exception {
    JrsctlHome home = new JrsctlHome(tmp);
    try (RunLock unusedLock = new RunLock(home, "r-abc", NOW)) {
      String text = Files.readString(home.runLock(), StandardCharsets.UTF_8);
      assertThat(text).isEqualTo("r-abc " + ProcessHandle.current().pid() + " " + NOW);
      assertThat(RunLock.readHolder(home.runLock()))
          .contains(
              new RunLock.Holder(
                  "r-abc", String.valueOf(ProcessHandle.current().pid()), NOW.toString()));
    }
    assertThat(Files.size(home.runLock())).isZero();
    assertThat(RunLock.readHolder(home.runLock())).isEmpty();
  }

  /**
   * Review finding 1.18: {@code close()} runs after the run's outcome is journaled, so it must
   * never throw; the second close finds a closed channel and must be a no-op.
   */
  @Test
  void should_not_throw_when_closed_twice() {
    JrsctlHome home = new JrsctlHome(tmp);
    RunLock lock = new RunLock(home, "r-twice", NOW);

    lock.close();
    lock.close();

    assertThat(RunLock.readHolder(home.runLock())).isEmpty();
  }

  @Test
  void should_allow_reacquiring_when_previous_holder_closed() {
    JrsctlHome home = new JrsctlHome(tmp);
    new RunLock(home, "r-1", NOW).close();
    try (RunLock second = new RunLock(home, "r-2", NOW)) {
      assertThat(second.runId()).isEqualTo("r-2");
    }
  }

  @Test
  void should_create_missing_home_directory_when_acquiring() {
    JrsctlHome home = new JrsctlHome(tmp.resolve("nested").resolve("home"));
    try (RunLock lock = new RunLock(home, "r-1", NOW)) {
      assertThat(Files.exists(lock.file())).isTrue();
    }
  }
}
