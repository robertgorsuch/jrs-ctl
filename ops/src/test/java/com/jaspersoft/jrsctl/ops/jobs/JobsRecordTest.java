package com.jaspersoft.jrsctl.ops.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #304: the jobs record file round-trips and anything else is refused with exit 2. */
class JobsRecordTest {

  @TempDir Path tmp;

  @Test
  void should_read_back_what_it_wrote_when_round_tripping_a_record() throws Exception {
    JobsRecord record =
        new JobsRecord(
            Instant.parse("2026-11-15T10:00:00Z"),
            "http://src:8080/jasperserver-pro",
            "8.2.0",
            List.of(
                new JobSummary("1", "Daily", "/r/a", "jasperadmin|organization_1", "NORMAL"),
                new JobSummary("2", "", "", "", "")));
    Path file = tmp.resolve("sub/jobs.json");

    record.write(file);

    assertThat(JobsRecord.read(file)).isEqualTo(record);
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains(JobsRecord.FORMAT);
    assertThat(file.resolveSibling("jobs.json.tmp")).doesNotExist();
  }

  @Test
  void should_refuse_a_file_that_is_not_a_jobs_record_when_reading() throws Exception {
    Path other = tmp.resolve("other.json");
    Files.writeString(other, "{\"format\":\"something-else\",\"jobs\":[]}", StandardCharsets.UTF_8);
    Path missing = tmp.resolve("missing.json");
    Path dupe = tmp.resolve("dupe.json");
    Files.writeString(
        dupe,
        "{\"format\":\"jrsctl-jobs/1\",\"recordedAt\":\"2026-11-15T10:00:00Z\","
            + "\"jobs\":[{\"id\":\"1\"},{\"id\":\"1\"}]}",
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> JobsRecord.read(other))
        .isInstanceOf(JobsException.class)
        .hasMessageContaining("is not a jobs record");
    assertThatThrownBy(() -> JobsRecord.read(missing))
        .isInstanceOf(JobsException.class)
        .hasMessageContaining("cannot read");
    assertThatThrownBy(() -> JobsRecord.read(dupe))
        .isInstanceOf(JobsException.class)
        .hasMessageContaining("appears twice");
  }
}
