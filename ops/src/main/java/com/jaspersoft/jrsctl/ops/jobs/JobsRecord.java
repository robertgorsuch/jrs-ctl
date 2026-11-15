package com.jaspersoft.jrsctl.ops.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.jrs.strategy.SidecarJobs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The file {@code jobs record} writes and {@code jobs pause --from} / {@code jobs resume --from}
 * read (issue #304, ADR-0056): every report job of one server with its state at one moment.
 * Invariants: the format tag is {@link #FORMAT}, and a file with another tag, no tag or no {@code
 * jobs} array is refused with the reason; ids are unique within a record; {@link #write} replaces
 * the target in one move from a sibling temporary file, so a reader never sees half a record.
 * {@link #read} also takes an export archive or its {@code .jrsctl.json} sidecar and reads the
 * sidecar's {@code jobs} block (issue #309, ADR-0059); a sidecar without a recorded block is
 * refused with the reason, never read as an empty record.
 */
public record JobsRecord(
    Instant recordedAt, String serverUrl, String serverVersion, List<JobSummary> jobs) {

  public static final String FORMAT = "jrsctl-jobs/1";

  public JobsRecord {
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(serverUrl, "serverUrl");
    Objects.requireNonNull(serverVersion, "serverVersion");
    jobs = List.copyOf(jobs);
    if (jobs.stream().map(JobSummary::id).distinct().count() != jobs.size()) {
      throw new IllegalArgumentException("a job id appears twice in the record");
    }
  }

  /** The record as the JSON document the file holds. */
  public ObjectNode toJson() {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("format", FORMAT);
    node.put("recordedAt", recordedAt.toString());
    ObjectNode server = node.putObject("server");
    server.put("url", serverUrl);
    server.put("version", serverVersion);
    ArrayNode list = node.putArray("jobs");
    for (JobSummary job : jobs) {
      list.add(jobJson(job));
    }
    return node;
  }

  static ObjectNode jobJson(JobSummary job) {
    ObjectNode j = Json.mapper().createObjectNode();
    j.put("id", job.id());
    j.put("label", job.label());
    j.put("reportUnitURI", job.reportUnitUri());
    j.put("owner", job.owner());
    j.put("state", job.state());
    return j;
  }

  /** Writes the record to {@code file} through a sibling temporary file and one move. */
  public void write(Path file) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path tmp = parent.resolve(file.getFileName() + ".tmp");
    Files.writeString(tmp, Json.write(toJson()) + System.lineSeparator(), StandardCharsets.UTF_8);
    try {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * The sidecar a {@code --from} path stands for: the path itself when it is a {@code .jrsctl.json}
   * sidecar, the archive's sidecar when one is next to it, otherwise empty (a jobs record file).
   */
  public static Optional<Path> sidecarOf(Path from) {
    Path name = from.getFileName();
    if (name != null && name.toString().endsWith(Sidecar.SUFFIX)) {
      return Optional.of(from);
    }
    Path sidecar = Sidecar.pathFor(from);
    return Files.isRegularFile(sidecar) ? Optional.of(sidecar) : Optional.empty();
  }

  /** The file whose content a {@code --from} path stands for, for the plan fingerprint. */
  public static Path sourceFile(Path from) {
    return sidecarOf(from).orElse(from);
  }

  /**
   * Reads a record file, or the jobs block of an export archive's sidecar.
   *
   * @throws JobsException with exit code 2 when the file cannot be read or is not a jobs record, or
   *     the sidecar records no job states
   */
  public static JobsRecord read(Path file) {
    Optional<Path> sidecar = sidecarOf(file);
    if (sidecar.isPresent()) {
      return fromSidecar(sidecar.get());
    }
    Path name = file.getFileName();
    if (name != null && name.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
      throw new JobsException(
          JobsException.PRECHECK,
          file + " has no " + Sidecar.SUFFIX + " sidecar next to it, so it records no job states",
          "name a file written by `jrsctl jobs record`, or an archive exported by jrsctl");
    }
    JsonNode node;
    try (InputStream in = Files.newInputStream(file)) {
      node = Json.mapper().readTree(in);
    } catch (IOException e) {
      throw new JobsException(
          JobsException.PRECHECK,
          "cannot read the jobs record " + file + ": " + e.getMessage(),
          "name a file written by `jrsctl jobs record`");
    }
    if (node == null || !FORMAT.equals(node.path("format").asText())) {
      throw notARecord(file, "its format is not " + FORMAT);
    }
    JsonNode list = node.path("jobs");
    if (!list.isArray()) {
      throw notARecord(file, "it has no jobs list");
    }
    Instant at;
    try {
      at = Instant.parse(node.path("recordedAt").asText());
    } catch (DateTimeParseException e) {
      throw notARecord(file, "recordedAt is not a time");
    }
    List<JobSummary> jobs = new ArrayList<>();
    for (JsonNode j : list) {
      String id = j.path("id").asText("");
      if (id.isBlank()) {
        throw notARecord(file, "a job has no id");
      }
      jobs.add(
          new JobSummary(
              id,
              j.path("label").asText(""),
              j.path("reportUnitURI").asText(""),
              j.path("owner").asText(""),
              j.path("state").asText("")));
    }
    try {
      return new JobsRecord(
          at,
          node.path("server").path("url").asText(""),
          node.path("server").path("version").asText(""),
          jobs);
    } catch (IllegalArgumentException e) {
      throw notARecord(file, e.getMessage());
    }
  }

  private static JobsRecord fromSidecar(Path file) {
    Optional<Sidecar> sidecar;
    try {
      sidecar = Sidecar.read(file);
    } catch (IOException | RuntimeException e) {
      throw new JobsException(
          JobsException.PRECHECK,
          "cannot read the export sidecar " + file + ": " + e.getMessage(),
          "name a file written by `jrsctl jobs record`, or an archive exported by jrsctl");
    }
    if (sidecar.isEmpty()) {
      throw new JobsException(
          JobsException.PRECHECK,
          file + " does not exist",
          "name a file written by `jrsctl jobs record`, or an archive exported by jrsctl");
    }
    Optional<SidecarJobs> block = sidecar.get().jobs();
    if (block.isEmpty()) {
      throw new JobsException(
          JobsException.PRECHECK,
          file
              + " records no job states: it was written before jrsctl recorded them (sidecar"
              + " version "
              + sidecar.get().sidecarVersion()
              + ")",
          "name a file written by `jrsctl jobs record`");
    }
    SidecarJobs jobs = block.get();
    if (!jobs.recorded()) {
      throw new JobsException(
          JobsException.PRECHECK,
          file + " records no job states: " + jobs.reason().orElse("no reason given"),
          "name a file written by `jrsctl jobs record`");
    }
    try {
      return new JobsRecord(
          jobs.recordedAt().orElseThrow(),
          jobs.serverUrl().orElseThrow(),
          sidecar.get().serverVersion(),
          jobs.jobs());
    } catch (IllegalArgumentException e) {
      throw notARecord(file, e.getMessage());
    }
  }

  private static JobsException notARecord(Path file, String why) {
    return new JobsException(
        JobsException.PRECHECK,
        file + " is not a jobs record: " + why,
        "name a file written by `jrsctl jobs record`");
  }
}
