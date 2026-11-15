package com.jaspersoft.jrsctl.ops.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Idempotency;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of the {@code jobs} steps (spec §6.1, issue #304, ADR-0056): executed twice,
 * a step changes each job once and its record keeps every id it changed; compensated twice, it
 * changes back exactly the recorded ids and nothing else.
 */
class JobsStepIdempotencyTest {

  @TempDir Path tmp;

  private static JobSummary job(String id, String state) {
    return new JobSummary(id, "job " + id, "/reports/r" + id, "jasperadmin", state);
  }

  private static Context ctx(FakeServices f, String runId) {
    return new Context(runId, f.home, f.platform, new CancellationToken(), Map.of())
        .with(JrsAdapter.class, f.adapter);
  }

  private static List<String> states(FakeServices f) {
    return f.adapter.jobs.stream().map(j -> j.id() + "=" + j.state()).toList();
  }

  private static long calls(FakeServices f, String prefix) {
    return f.adapter.calls.stream().filter(c -> c.startsWith(prefix)).count();
  }

  @Test
  void should_pause_each_job_once_when_jobs_pause_executes_twice() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.jobs.addAll(List.of(job("1", "NORMAL"), job("2", "PAUSED"), job("3", "NORMAL")));
      Plan plan =
          new DefaultJobsOperations(f.build())
              .planPause(new JobsOperations.PauseOptions(Optional.empty()));
      Step step = Idempotency.step(plan, JobsSteps.Pause.ID);
      Context ctx = ctx(f, "r-pause");

      Idempotency.executeOk(step, ctx);
      Path record = f.home.runDir("r-pause").resolve(JobsSteps.Pause.FILE);
      List<String> first = Files.readAllLines(record);
      Idempotency.executeOk(step, ctx);

      assertThat(first).containsExactly("1", "3");
      assertThat(Files.readAllLines(record)).isEqualTo(first);
      assertThat(f.adapter.calls).contains("pauseJobs [1, 3]");
      assertThat(calls(f, "pauseJobs")).isEqualTo(1);
      assertThat(states(f)).containsExactly("1=PAUSED", "2=PAUSED", "3=PAUSED");
    }
  }

  @Test
  void should_resume_only_the_jobs_it_paused_when_jobs_pause_compensates_twice() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.jobs.addAll(List.of(job("1", "NORMAL"), job("2", "PAUSED")));
      Plan plan =
          new DefaultJobsOperations(f.build())
              .planPause(new JobsOperations.PauseOptions(Optional.empty()));
      Step step = Idempotency.step(plan, JobsSteps.Pause.ID);
      Context ctx = ctx(f, "r-undo");
      Idempotency.executeOk(step, ctx);

      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(f.adapter.calls.stream().filter(c -> c.startsWith("resumeJobs")))
          .containsExactly("resumeJobs [1]", "resumeJobs [1]");
      assertThat(states(f)).containsExactly("1=NORMAL", "2=PAUSED");
    }
  }

  @Test
  void should_resume_each_recorded_active_job_once_when_jobs_resume_executes_twice()
      throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      Path file = tmp.resolve("source.json");
      new JobsRecord(
              Instant.parse("2026-11-15T10:00:00Z"),
              "http://source",
              "8.2.0",
              List.of(job("1", "NORMAL"), job("2", "PAUSED"), job("3", "WAITING")))
          .write(file);
      f.adapter.jobs.addAll(List.of(job("1", "PAUSED"), job("2", "PAUSED"), job("3", "PAUSED")));
      Plan plan =
          new DefaultJobsOperations(f.build()).planResume(new JobsOperations.ResumeOptions(file));
      Step step = Idempotency.step(plan, JobsSteps.Resume.ID);
      Context ctx = ctx(f, "r-resume");

      Idempotency.executeOk(step, ctx);
      Idempotency.executeOk(step, ctx);

      assertThat(calls(f, "resumeJobs")).isEqualTo(1);
      assertThat(f.adapter.calls).contains("resumeJobs [1, 3]");
      assertThat(Files.readAllLines(f.home.runDir("r-resume").resolve(JobsSteps.Resume.FILE)))
          .containsExactly("1", "3");
      assertThat(states(f)).containsExactly("1=NORMAL", "2=PAUSED", "3=NORMAL");
    }
  }

  @Test
  void should_pause_again_only_the_jobs_it_resumed_when_jobs_resume_compensates_twice()
      throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      Path file = tmp.resolve("source.json");
      new JobsRecord(
              Instant.parse("2026-11-15T10:00:00Z"),
              "http://source",
              "8.2.0",
              List.of(job("1", "NORMAL"), job("2", "NORMAL")))
          .write(file);
      // job 2 already runs on the target: the resume leaves it alone and so must the rollback
      f.adapter.jobs.addAll(List.of(job("1", "PAUSED"), job("2", "NORMAL")));
      Plan plan =
          new DefaultJobsOperations(f.build()).planResume(new JobsOperations.ResumeOptions(file));
      Step step = Idempotency.step(plan, JobsSteps.Resume.ID);
      Context ctx = ctx(f, "r-resume-undo");
      Idempotency.executeOk(step, ctx);

      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);

      assertThat(calls(f, "pauseJobs")).isEqualTo(2);
      assertThat(f.adapter.calls).contains("pauseJobs [1]").doesNotContain("pauseJobs [1, 2]");
      assertThat(states(f)).containsExactly("1=PAUSED", "2=NORMAL");
    }
  }

  @Test
  void should_fail_after_recording_the_ids_when_the_server_refuses_the_pause() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      f.adapter.jobs.add(job("1", "NORMAL"));
      Plan plan =
          new DefaultJobsOperations(f.build())
              .planPause(new JobsOperations.PauseOptions(Optional.empty()));
      Step step = Idempotency.step(plan, JobsSteps.Pause.ID);
      Context ctx = ctx(f, "r-refused");
      f.adapter.changeJobsFailure = Optional.of(new IllegalStateException("HTTP 500"));

      StepResult result = step.execute(ctx, e -> {});

      assertThat(result).isInstanceOf(StepResult.Failed.class);
      assertThat(result.toString()).contains("refused to pause").contains("HTTP 500");
      // the record exists, so a rollback resumes whatever the half-applied call paused
      assertThat(Files.readAllLines(f.home.runDir("r-refused").resolve(JobsSteps.Pause.FILE)))
          .containsExactly("1");
    }
  }
}
