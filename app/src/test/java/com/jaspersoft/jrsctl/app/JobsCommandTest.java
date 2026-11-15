package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #304, ADR-0056: the {@code jobs} commands against the fake server. */
class JobsCommandTest {

  @TempDir Path tmp;

  private Path home;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          auth:
            username: superuser
            passwordRef: env:JRS_PASSWORD
        """,
        StandardCharsets.UTF_8);
    Env.override(Map.of("JRS_PASSWORD", "pw-for-tests"));
    TestAdapterFactory.adapter = new AppFakeAdapter();
    TestAdapterFactory.adapter.jobs.add(
        new JobSummary("1898", "Daily", "/reports/a", "jasperadmin", "NORMAL"));
    TestAdapterFactory.adapter.jobs.add(
        new JobSummary("1899", "Weekly", "/reports/b", "jasperadmin", "PAUSED"));
  }

  @AfterEach
  void tearDown() {
    Env.reset();
    TestAdapterFactory.adapter = new AppFakeAdapter();
  }

  @Test
  void should_print_the_counts_and_the_file_when_recording_jobs() throws Exception {
    Path out = tmp.resolve("source.json");

    InitCommandTest.Run r =
        InitCommandTest.run(
            "jobs", "record", "--out", out.toString(), "--home", home.toString(), "--no-color");

    assertThat(r.code()).as(r.out() + r.err()).isZero();
    assertThat(r.out()).contains("Recorded 2 report job(s)").contains("1 active, 1 paused");
    assertThat(r.out()).contains(out.toAbsolutePath().toString());
    assertThat(Files.readString(out)).contains("\"1898\"").contains("jrsctl-jobs/1");
    assertThat(r.out()).doesNotContain("pw-for-tests");
  }

  @Test
  void should_refuse_as_usage_when_pause_names_neither_or_both_selections() {
    InitCommandTest.Run neither = InitCommandTest.run("jobs", "pause", "--home", home.toString());
    InitCommandTest.Run both =
        InitCommandTest.run(
            "jobs", "pause", "--all", "--from", "x.json", "--home", home.toString());

    assertThat(neither.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(both.code()).isEqualTo(ExitCodes.USAGE);
  }

  @Test
  void should_show_the_plan_and_change_nothing_when_pausing_all_with_plan() {
    InitCommandTest.Run r =
        InitCommandTest.run(
            "jobs", "pause", "--all", "--plan", "--home", home.toString(), "--no-color");

    assertThat(r.code()).as(r.out() + r.err()).isZero();
    assertThat(r.out()).contains("jobs.pause").contains("pause 1 (1898)");
    assertThat(r.out()).contains("jobs record");
    assertThat(TestAdapterFactory.adapter.jobs.get(0).state()).isEqualTo("NORMAL");
  }

  @Test
  void should_exit_2_naming_the_file_when_resume_reads_a_file_that_is_not_a_record()
      throws Exception {
    Path bogus = tmp.resolve("bogus.json");
    Files.writeString(bogus, "{\"format\":\"other\"}");

    InitCommandTest.Run r =
        InitCommandTest.run(
            "jobs", "resume", "--from", bogus.toString(), "--plan", "--home", home.toString());

    assertThat(r.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(r.out() + r.err()).contains("is not a jobs record");
  }
}
