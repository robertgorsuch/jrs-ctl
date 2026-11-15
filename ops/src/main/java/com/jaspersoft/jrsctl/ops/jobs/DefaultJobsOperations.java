package com.jaspersoft.jrsctl.ops.jobs;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link JobsOperations} over the configured server's REST API (issue #304, ADR-0056). Invariants:
 * {@link #record} never overwrites a file: the default name carries the time to the second and an
 * existing {@code --out} is refused; planning lists the server once to fill the summary, and the
 * step lists it again when it runs; the fingerprint covers only what the operator chose (server
 * URL, selection, the record's SHA-256), not the job states, so a run interrupted after its change
 * still rebuilds the same plan for {@code runs recover}.
 */
public final class DefaultJobsOperations implements JobsOperations {

  static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  static final String PAUSE_ALL_WARNING =
      "pause --all makes every job look paused, also in an export taken afterwards: record the"
          + " states first with `jrsctl jobs record`, so `jobs resume --from <record>` can restore"
          + " them.";

  static final String STAY_PAUSED_NOTE =
      "The paused jobs stay paused until `jrsctl jobs resume --from <record>` or a rollback of"
          + " this run.";

  private final Services services;

  public DefaultJobsOperations(Services services) {
    this.services = Objects.requireNonNull(services, "services");
  }

  @Override
  public JobsRecordReport record(RecordOptions options) {
    Objects.requireNonNull(options, "options");
    JrsAdapter adapter = services.adapter().get();
    Instant now = services.clock().instant().truncatedTo(ChronoUnit.SECONDS);
    Path file =
        options
            .out()
            .map(p -> p.toAbsolutePath().normalize())
            .orElseGet(
                () ->
                    services
                        .home()
                        .root()
                        .resolve("jobs")
                        .resolve("jobs-" + STAMP.format(now) + ".json")
                        .toAbsolutePath()
                        .normalize());
    if (Files.exists(file)) {
      throw new JobsException(
          JobsException.PRECHECK,
          file + " exists; jobs record never overwrites a record",
          "name another file with --out, or move the existing one away");
    }
    ServerIdentity identity = adapter.identity();
    List<JobSummary> jobs = listJobs(adapter);
    JobsRecord record =
        new JobsRecord(now, identity.baseUrl().toString(), identity.version(), jobs);
    try {
      record.write(file);
    } catch (IOException e) {
      throw new JobsException(
          JobsException.PRECHECK,
          "cannot write " + file + ": " + e.getMessage(),
          "name a writable file with --out");
    }
    return JobsRecordReport.of(file, record);
  }

  @Override
  public Plan planPause(PauseOptions options) {
    Objects.requireNonNull(options, "options");
    JobsInput input = new JobsInput(options.from());
    Optional<JobsRecord> record = input.from().map(JobsRecord::read);
    JrsAdapter adapter = services.adapter().get();
    ServerIdentity identity = adapter.identity();
    List<JobSummary> current = listJobs(adapter);
    JobSelection selection =
        record.isPresent()
            ? JobSelection.pauseFrom(record.get().jobs(), current)
            : JobSelection.pauseAll(current);
    List<String> resources = new ArrayList<>();
    resources.add(
        "report jobs on "
            + identity.baseUrl()
            + ": pause "
            + selection.change().size()
            + idList(selection.change())
            + "; "
            + selection.alreadyDone().size()
            + " paused already, left as they are");
    List<String> warnings = new ArrayList<>();
    if (record.isEmpty()) {
      warnings.add(PAUSE_ALL_WARNING);
    } else {
      sourceWarning(record.get(), identity).ifPresent(warnings::add);
    }
    unmatchedWarning(selection).ifPresent(warnings::add);
    warnings.add(STAY_PAUSED_NOTE);
    return plan(
        PAUSE_OPERATION,
        new JobsSteps.Pause(input),
        identity,
        resources,
        "resume exactly the jobs this run paused",
        warnings,
        input,
        record.isPresent() ? "from" : "all");
  }

  @Override
  public Plan planResume(ResumeOptions options) {
    Objects.requireNonNull(options, "options");
    JobsInput input = new JobsInput(Optional.of(options.from()));
    JobsRecord record = JobsRecord.read(input.from().orElseThrow());
    JrsAdapter adapter = services.adapter().get();
    ServerIdentity identity = adapter.identity();
    JobSelection selection = JobSelection.resumeFrom(record.jobs(), listJobs(adapter));
    List<String> resources = new ArrayList<>();
    resources.add(
        "report jobs on "
            + identity.baseUrl()
            + ": resume "
            + selection.change().size()
            + idList(selection.change())
            + "; "
            + selection.alreadyDone().size()
            + " running already; "
            + selection.leftPaused().size()
            + " recorded as paused stay paused");
    List<String> warnings = new ArrayList<>();
    sourceWarning(record, identity).ifPresent(warnings::add);
    pausedAtExportWarning(input.from().orElseThrow(), record).ifPresent(warnings::add);
    if (!selection.errorState().isEmpty()) {
      warnings.add(
          selection.errorState().size()
              + " job(s) were recorded in a state that is neither active nor paused and are left"
              + " alone; decide each one in the scheduler: "
              + String.join(
                  "; ", selection.errorState().stream().map(JobSelection::describe).toList()));
    }
    unmatchedWarning(selection).ifPresent(warnings::add);
    return plan(
        RESUME_OPERATION,
        new JobsSteps.Resume(input),
        identity,
        resources,
        "pause again exactly the jobs this run resumed",
        warnings,
        input,
        "from");
  }

  private Plan plan(
      String operation,
      Step step,
      ServerIdentity identity,
      List<String> resources,
      String rollback,
      List<String> warnings,
      JobsInput input,
      String selection) {
    Map<String, String> rollbackByPhase = new LinkedHashMap<>();
    rollbackByPhase.put(JobsSteps.PHASE, rollback);
    PlanSummary summary =
        new PlanSummary(
            operation,
            identity.baseUrl() + " (server " + identity.version() + ")",
            List.of(),
            resources,
            false,
            List.of(),
            rollbackByPhase,
            "REST (/rest_v2/jobs)",
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.baseUrl().toString());
    inputs.put("selection", selection);
    if (input.from().isPresent()) {
      inputs.put("record", input.from().get().toString());
      // an archive stands for its sidecar: hash the few kilobytes that hold the states
      inputs.put("recordSha256", sha256(JobsRecord.sourceFile(input.from().get())));
    }
    return new Plan(
        operation.replace('.', '-') + "-" + RunIds.next(services.clock()),
        List.of(step),
        summary,
        PlanFingerprint.of(inputs));
  }

  private static List<JobSummary> listJobs(JrsAdapter adapter) {
    try {
      return adapter.listJobs();
    } catch (JrsUnreachableException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new JobsException(
          JobsException.PRECHECK,
          "cannot list the server's report jobs: " + e.getMessage(),
          "check that the configured user may read the scheduler (GET /rest_v2/jobs)");
    }
  }

  private static String idList(List<String> ids) {
    return ids.isEmpty() ? "" : " (" + String.join(", ", ids) + ")";
  }

  /** A record taken on another server is normal (source to target); say so in the plan. */
  private static Optional<String> sourceWarning(JobsRecord record, ServerIdentity identity) {
    if (record.serverUrl().equals(identity.baseUrl().toString())) {
      return Optional.empty();
    }
    return Optional.of(
        "the record was taken on "
            + (record.serverUrl().isEmpty() ? "an unnamed server" : record.serverUrl())
            + (record.serverVersion().isEmpty() ? "" : " (" + record.serverVersion() + ")")
            + " at "
            + record.recordedAt()
            + "; its jobs are matched here by id, or by label, report and owner when the id"
            + " differs");
  }

  /**
   * A sidecar records the states at export time (ADR-0059); when every job was paused then, they
   * were most likely paused for the move, and resuming from the sidecar resumes nothing.
   */
  private static Optional<String> pausedAtExportWarning(Path from, JobsRecord record) {
    Optional<Path> sidecar = JobsRecord.sidecarOf(from);
    if (sidecar.isEmpty()
        || record.jobs().isEmpty()
        || !record.jobs().stream().allMatch(JobSummary::paused)) {
      return Optional.empty();
    }
    return Optional.of(
        "every job in "
            + sidecar.get()
            + " was paused when the archive was exported; if they were paused for the move,"
            + " resume from the `jobs record` file taken before the pause instead");
  }

  private static Optional<String> unmatchedWarning(JobSelection selection) {
    if (selection.unmatched().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        selection.unmatched().size()
            + " recorded job(s) are not matched on this server and are left alone: "
            + String.join(
                "; ",
                selection.unmatched().stream().map(JobSelection.Unmatched::describe).toList()));
  }

  private String sha256(Path file) {
    try {
      return services.platform().files().sha256(file);
    } catch (IOException e) {
      return "unreadable";
    }
  }
}
