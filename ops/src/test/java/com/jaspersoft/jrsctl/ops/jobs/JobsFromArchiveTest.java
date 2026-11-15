package com.jaspersoft.jrsctl.ops.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.jrs.strategy.SidecarJobs;
import com.jaspersoft.jrsctl.ops.FakeServices;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #309, ADR-0059: {@code jobs resume --from <archive>} reads the export sidecar. */
class JobsFromArchiveTest {

  @TempDir Path tmp;

  private static JobSummary job(String id, String label, String state) {
    return new JobSummary(id, label, "/reports/" + label, "jasperadmin", state);
  }

  private static Sidecar sidecar(Optional<SidecarJobs> jobs) {
    Sidecar base =
        new Sidecar(
            Instant.parse("2026-11-15T09:00:00Z"),
            "http://source:8080/jasperserver-pro|8.2.0|PRO|MT|1",
            "8.2.0",
            Optional.empty(),
            new Sidecar.Flags(
                ExportRequest.Scope.EVERYTHING, List.of(), true, false, false, false, true, true),
            "00",
            ExportImportStrategy.Kind.REST);
    return jobs.map(base::withJobs).orElse(base);
  }

  private Path archive(Optional<SidecarJobs> jobs) throws Exception {
    Path zip = tmp.resolve("full.zip");
    Files.writeString(zip, "not really a zip", StandardCharsets.UTF_8);
    Sidecar.write(Sidecar.pathFor(zip), sidecar(jobs));
    return zip;
  }

  private static SidecarJobs recorded(JobSummary... jobs) {
    return SidecarJobs.recorded(
        Instant.parse("2026-11-15T09:00:00Z"),
        "http://source:8080/jasperserver-pro",
        List.of(jobs));
  }

  @Test
  void should_read_the_states_from_the_archive_or_the_sidecar_itself() throws Exception {
    Path zip = archive(Optional.of(recorded(job("1", "daily", "NORMAL"))));

    JobsRecord fromArchive = JobsRecord.read(zip);
    JobsRecord fromSidecar = JobsRecord.read(Sidecar.pathFor(zip));

    assertThat(fromArchive).isEqualTo(fromSidecar);
    assertThat(fromArchive.serverUrl()).isEqualTo("http://source:8080/jasperserver-pro");
    assertThat(fromArchive.serverVersion()).isEqualTo("8.2.0");
    assertThat(fromArchive.jobs()).containsExactly(job("1", "daily", "NORMAL"));
    assertThat(JobsRecord.sourceFile(zip)).isEqualTo(Sidecar.pathFor(zip));
  }

  @Test
  void should_refuse_a_version_1_sidecar_and_one_without_recorded_states() throws Exception {
    Path v1 = archive(Optional.empty());
    assertThatThrownBy(() -> JobsRecord.read(v1))
        .isInstanceOfSatisfying(JobsException.class, e -> assertThat(e.exitCode()).isEqualTo(2))
        .hasMessageContaining("records no job states");

    Files.delete(Sidecar.pathFor(v1));
    Path none = archive(Optional.of(SidecarJobs.notRecorded("server down")));
    assertThatThrownBy(() -> JobsRecord.read(none))
        .isInstanceOf(JobsException.class)
        .hasMessageContaining("server down");
  }

  @Test
  void should_refuse_an_archive_without_a_sidecar_naming_why() throws Exception {
    Path zip = tmp.resolve("bare.zip");
    Files.writeString(zip, "zip", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> JobsRecord.read(zip))
        .isInstanceOf(JobsException.class)
        .hasMessageContaining("has no .jrsctl.json sidecar");
  }

  @Test
  void should_plan_a_resume_from_the_archive() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      Path zip =
          archive(Optional.of(recorded(job("1", "daily", "NORMAL"), job("2", "w", "PAUSED"))));
      f.adapter.jobs.addAll(List.of(job("11", "daily", "PAUSED"), job("12", "w", "PAUSED")));

      Plan plan =
          new DefaultJobsOperations(f.build()).planResume(new JobsOperations.ResumeOptions(zip));

      assertThat(plan.summary().resourcesTouched().get(0)).contains("resume 1 (11)");
      assertThat(String.join("\n", plan.summary().warnings()))
          .doesNotContain("was paused when the archive was exported");
    }
  }

  @Test
  void should_warn_when_every_job_was_paused_at_export_time() throws Exception {
    try (FakeServices f = FakeServices.in(tmp)) {
      f.platform.realFiles = true;
      Path zip =
          archive(Optional.of(recorded(job("1", "daily", "PAUSED"), job("2", "w", "PAUSED"))));
      f.adapter.jobs.addAll(List.of(job("11", "daily", "PAUSED"), job("12", "w", "PAUSED")));

      Plan plan =
          new DefaultJobsOperations(f.build()).planResume(new JobsOperations.ResumeOptions(zip));

      assertThat(String.join("\n", plan.summary().warnings()))
          .contains("every job in")
          .contains("was paused when the archive was exported")
          .contains("`jobs record` file taken before the pause");
    }
  }
}
