package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.RunRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #161: the smaller defects a real 2.0.0 support bundle showed. */
class SupportBundleDetailsTest {

  @TempDir Path tmp;

  private static final Instant START = Instant.parse("2026-09-25T16:19:00Z");
  private static final RunRecord RUN =
      new RunRecord(
          "r-7",
          "hotfix.apply",
          Optional.of("plan-7"),
          START,
          Optional.of(START.plusSeconds(300)),
          Optional.empty(),
          Optional.of(0));

  @Test
  void should_fill_in_the_run_id_the_plan_left_as_a_placeholder() {
    String plan =
        "{\"backupLocations\":[\"C:\\ProgramData\\jrsctl\\snapshots\\{runId}\\snapshot\"]}";

    assertThat(SupportBundle.withRunId(plan, "r-7"))
        .doesNotContain("{runId}")
        .contains("snapshots\\r-7\\snapshot");
  }

  @Test
  void should_leave_out_a_buildomatic_log_written_before_the_run_started() throws IOException {
    Path old = Files.writeString(tmp.resolve("js-export-pro_2026-09-17.log"), "old");
    Files.setLastModifiedTime(old, FileTime.from(START.minusSeconds(8 * 86_400)));
    Path fresh = Files.writeString(tmp.resolve("js-upgrade-newdb_2026-09-25.log"), "new");
    Files.setLastModifiedTime(fresh, FileTime.from(START.plusSeconds(60)));
    Path olderServerLog = Files.writeString(tmp.resolve("jasperserver.log"), "server");
    Files.setLastModifiedTime(olderServerLog, FileTime.from(START.minusSeconds(86_400)));

    assertThat(SupportBundle.belongsToRun(buildomatic(old), RUN)).isFalse();
    assertThat(SupportBundle.belongsToRun(buildomatic(fresh), RUN)).isTrue();
    assertThat(
            SupportBundle.belongsToRun(
                new VendorLogs.Source("vendor/jasperserver.log", olderServerLog, false), RUN))
        .as("the server's own logs are context whatever their age")
        .isTrue();
  }

  @Test
  void should_default_the_bundle_to_the_home_when_run_from_inside_the_distribution() {
    Path dist = tmp.resolve("jrsctl-2.0.0");
    Path home = tmp.resolve("home");

    assertThat(RunsCommand.defaultOut("r-7", dist.resolve("bin"), Optional.of(dist), home))
        .isEqualTo(home.resolve("r-7-support-bundle.zip"));
    assertThat(RunsCommand.defaultOut("r-7", tmp.resolve("work"), Optional.of(dist), home))
        .isEqualTo(tmp.resolve("work").resolve("r-7-support-bundle.zip"));
    assertThat(RunsCommand.defaultOut("r-7", tmp.resolve("work"), Optional.empty(), home))
        .isEqualTo(tmp.resolve("work").resolve("r-7-support-bundle.zip"));
  }

  private static VendorLogs.Source buildomatic(Path file) {
    return new VendorLogs.Source("vendor/buildomatic/" + file.getFileName(), file, false);
  }
}
