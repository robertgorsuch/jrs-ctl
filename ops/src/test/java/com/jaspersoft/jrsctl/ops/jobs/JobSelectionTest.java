package com.jaspersoft.jrsctl.ops.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #304, ADR-0056: which jobs a pause or resume touches, and how records are matched. */
class JobSelectionTest {

  private static JobSummary job(String id, String label, String state) {
    return new JobSummary(id, label, "/reports/" + label, "jasperadmin|organization_1", state);
  }

  @Test
  void should_pause_every_job_not_paused_already_when_pausing_all() {
    JobSelection s =
        JobSelection.pauseAll(
            List.of(job("1", "a", "NORMAL"), job("2", "b", "PAUSED"), job("3", "c", "EXECUTING")));

    assertThat(s.change()).containsExactly("1", "3");
    assertThat(s.alreadyDone()).extracting(JobSummary::id).containsExactly("2");
  }

  @Test
  void should_resume_only_jobs_recorded_active_and_paused_now_when_resuming_from_a_record() {
    List<JobSummary> recorded =
        List.of(
            job("1", "daily", "NORMAL"),
            job("2", "weekly", "PAUSED"),
            job("3", "monthly", "WAITING"),
            job("4", "broken", "ERROR"),
            job("5", "running", "EXECUTING"));
    List<JobSummary> current =
        List.of(
            job("1", "daily", "PAUSED"),
            job("2", "weekly", "PAUSED"),
            job("3", "monthly", "PAUSED"),
            job("4", "broken", "PAUSED"),
            job("5", "running", "NORMAL"));

    JobSelection s = JobSelection.resumeFrom(recorded, current);

    assertThat(s.change()).containsExactly("1", "3");
    assertThat(s.leftPaused()).extracting(JobSummary::id).containsExactly("2");
    assertThat(s.errorState()).extracting(JobSummary::id).containsExactly("4");
    assertThat(s.alreadyDone()).extracting(JobSummary::id).containsExactly("5");
    assertThat(s.unmatched()).isEmpty();
  }

  @Test
  void should_match_by_label_report_and_owner_when_the_ids_changed_across_the_import() {
    List<JobSummary> recorded = List.of(job("1898", "daily", "NORMAL"));
    List<JobSummary> current = List.of(job("17", "daily", "PAUSED"), job("18", "other", "PAUSED"));

    assertThat(JobSelection.resumeFrom(recorded, current).change()).containsExactly("17");
  }

  @Test
  void should_not_trust_a_reused_id_when_label_report_or_owner_differ() {
    // id 7 now belongs to another job; the recorded one is found by its key as id 9
    List<JobSummary> recorded = List.of(job("7", "daily", "NORMAL"));
    List<JobSummary> current = List.of(job("7", "other", "PAUSED"), job("9", "daily", "PAUSED"));

    assertThat(JobSelection.resumeFrom(recorded, current).change()).containsExactly("9");
  }

  @Test
  void should_leave_a_job_unmatched_when_several_share_its_label_report_and_owner() {
    List<JobSummary> recorded = List.of(job("1", "daily", "NORMAL"));
    List<JobSummary> current = List.of(job("21", "daily", "PAUSED"), job("22", "daily", "PAUSED"));

    JobSelection s = JobSelection.resumeFrom(recorded, current);

    assertThat(s.change()).isEmpty();
    assertThat(s.unmatched()).hasSize(1);
    assertThat(s.unmatched().get(0).reason()).contains("2 jobs").contains("21, 22");
  }

  @Test
  void should_leave_a_job_unmatched_when_nothing_on_the_server_resembles_it() {
    JobSelection s =
        JobSelection.pauseFrom(List.of(job("1", "gone", "NORMAL")), List.of(job("2", "b", "")));

    assertThat(s.change()).isEmpty();
    assertThat(s.unmatched()).extracting(u -> u.recorded().id()).containsExactly("1");
  }

  @Test
  void should_pause_recorded_jobs_whatever_their_recorded_state_when_pausing_from_a_record() {
    List<JobSummary> recorded = List.of(job("1", "a", "PAUSED"), job("2", "b", "NORMAL"));
    List<JobSummary> current =
        List.of(job("1", "a", "NORMAL"), job("2", "b", "PAUSED"), job("3", "c", "NORMAL"));

    JobSelection s = JobSelection.pauseFrom(recorded, current);

    assertThat(s.change()).containsExactly("1");
    assertThat(s.alreadyDone()).extracting(JobSummary::id).containsExactly("2");
  }
}
