package com.jaspersoft.jrsctl.ops.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.ops.FakeServices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #304, ADR-0056: recording job states and planning a pause or a resume. */
class DefaultJobsOperationsTest {

  @TempDir Path tmp;

  private static JobSummary job(String id, String label, String state) {
    return new JobSummary(id, label, "/reports/" + label, "jasperadmin", state);
  }

  @Test
  void should_write_every_job_to_a_new_file_in_the_home_when_recording() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.jobs.addAll(
          List.of(job("1", "a", "NORMAL"), job("2", "b", "PAUSED"), job("3", "c", "ERROR")));

      JobsRecordReport report =
          new DefaultJobsOperations(f.build())
              .record(new JobsOperations.RecordOptions(Optional.empty()));

      assertThat(report.file())
          .isEqualTo(f.home.root().resolve("jobs/jobs-20260908-100000.json").toAbsolutePath());
      assertThat(report.active()).isEqualTo(1);
      assertThat(report.paused()).isEqualTo(1);
      assertThat(report.other()).isEqualTo(1);
      JobsRecord back = JobsRecord.read(report.file());
      assertThat(back.jobs()).isEqualTo(f.adapter.jobs);
      assertThat(back.recordedAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
    }
  }

  @Test
  void should_refuse_to_overwrite_an_existing_file_when_recording() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      Path out = tmp.resolve("jobs.json");
      Files.writeString(out, "keep me");

      assertThatThrownBy(
              () ->
                  new DefaultJobsOperations(f.build())
                      .record(new JobsOperations.RecordOptions(Optional.of(out))))
          .isInstanceOf(JobsException.class)
          .hasMessageContaining("never overwrites");
      assertThat(Files.readString(out)).isEqualTo("keep me");
    }
  }

  @Test
  void should_refuse_with_exit_2_when_the_server_refuses_the_job_listing() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.listJobsFailure = Optional.of(new IllegalStateException("HTTP 403"));

      assertThatThrownBy(
              () ->
                  new DefaultJobsOperations(f.build())
                      .planPause(new JobsOperations.PauseOptions(Optional.empty())))
          .isInstanceOfSatisfying(JobsException.class, e -> assertThat(e.exitCode()).isEqualTo(2))
          .hasMessageContaining("HTTP 403");
    }
  }

  @Test
  void should_count_and_warn_in_the_summary_when_planning_a_pause_of_every_job() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.jobs.addAll(List.of(job("1", "a", "NORMAL"), job("2", "b", "PAUSED")));

      Plan plan =
          new DefaultJobsOperations(f.build())
              .planPause(new JobsOperations.PauseOptions(Optional.empty()));

      assertThat(plan.summary().operation()).isEqualTo(JobsOperations.PAUSE_OPERATION);
      assertThat(plan.summary().resourcesTouched().get(0))
          .contains("pause 1 (1)")
          .contains("1 paused already");
      assertThat(plan.summary().warnings()).contains(DefaultJobsOperations.PAUSE_ALL_WARNING);
      assertThat(plan.summary().rollbackPointsByPhase())
          .containsEntry(JobsSteps.PHASE, "resume exactly the jobs this run paused");
      assertThat(plan.mutating()).isTrue();
    }
  }

  @Test
  void should_name_error_state_and_unmatched_jobs_when_planning_a_resume() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      Path file = tmp.resolve("source.json");
      new JobsRecord(
              Instant.parse("2026-11-15T10:00:00Z"),
              "http://source:8080/jasperserver-pro",
              "8.2.0",
              List.of(
                  job("1", "daily", "NORMAL"),
                  job("2", "weekly", "PAUSED"),
                  job("3", "broken", "ERROR"),
                  job("4", "gone", "NORMAL")))
          .write(file);
      f.adapter.jobs.addAll(List.of(job("11", "daily", "PAUSED"), job("12", "weekly", "PAUSED")));

      Plan plan =
          new DefaultJobsOperations(f.build()).planResume(new JobsOperations.ResumeOptions(file));

      assertThat(plan.summary().resourcesTouched().get(0))
          .contains("resume 1 (11)")
          .contains("1 recorded as paused stay paused");
      assertThat(String.join("\n", plan.summary().warnings()))
          .contains("the record was taken on http://source:8080/jasperserver-pro (8.2.0)")
          .contains("3 \"broken\"")
          .contains("1 recorded job(s) are not matched")
          .contains("4 \"gone\"");
    }
  }

  @Test
  void should_keep_the_fingerprint_when_the_job_states_change_after_planning() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.jobs.add(job("1", "a", "NORMAL"));
      DefaultJobsOperations ops = new DefaultJobsOperations(f.build());
      Plan before = ops.planPause(new JobsOperations.PauseOptions(Optional.empty()));
      f.adapter.pauseJobs(List.of("1"));

      Plan after = ops.planPause(new JobsOperations.PauseOptions(Optional.empty()));

      // runs recover rebuilds the plan of an interrupted run after its change was made
      assertThat(after.fingerprint().matches(before.fingerprint())).isTrue();
    }
  }
}
