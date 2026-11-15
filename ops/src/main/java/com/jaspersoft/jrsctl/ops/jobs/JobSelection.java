package com.jaspersoft.jrsctl.ops.jobs;

import com.jaspersoft.jrsctl.jrs.api.JobSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which jobs a {@code jobs pause} or {@code jobs resume} touches, computed from the server's
 * current jobs and, for {@code --from}, a {@link JobsRecord} (issue #304, ADR-0056). Pure: no I/O,
 * so the plan and the step compute the same answer from the same inputs.
 *
 * <p>Matching a recorded job to a current one: the same id counts only when the label, report and
 * owner agree too; otherwise the job is found by label + report + owner, and only when exactly one
 * current job has that key. No match, or several candidates, makes the entry unmatched: it is
 * reported and never guessed. Invariants: {@link #change()} holds each id once, in server order for
 * {@code --all} and record order for {@code --from}; a job already in the target state is never in
 * it; every recorded entry lands in exactly one of the lists.
 */
public record JobSelection(
    List<String> change,
    List<JobSummary> alreadyDone,
    List<JobSummary> leftPaused,
    List<JobSummary> errorState,
    List<Unmatched> unmatched) {

  /** Recorded states that mean the job was running on schedule (ADR-0056). */
  public static final Set<String> ACTIVE = Set.of("NORMAL", "EXECUTING", "WAITING");

  /** A recorded job no current job could be tied to, and why. */
  public record Unmatched(JobSummary recorded, String reason) {
    public Unmatched {
      Objects.requireNonNull(recorded, "recorded");
      Objects.requireNonNull(reason, "reason");
    }

    /** {@code id "label" (report, owner): reason}. */
    public String describe() {
      return JobSelection.describe(recorded) + ": " + reason;
    }
  }

  public JobSelection {
    change = List.copyOf(change);
    alreadyDone = List.copyOf(alreadyDone);
    leftPaused = List.copyOf(leftPaused);
    errorState = List.copyOf(errorState);
    unmatched = List.copyOf(unmatched);
  }

  /** {@code jobs pause --all}: every current job not already paused. */
  public static JobSelection pauseAll(List<JobSummary> current) {
    Set<String> change = new LinkedHashSet<>();
    List<JobSummary> done = new ArrayList<>();
    for (JobSummary job : current) {
      if (job.paused()) {
        done.add(job);
      } else {
        change.add(job.id());
      }
    }
    return new JobSelection(List.copyOf(change), done, List.of(), List.of(), List.of());
  }

  /** {@code jobs pause --from}: the recorded jobs, matched on the server, not already paused. */
  public static JobSelection pauseFrom(List<JobSummary> recorded, List<JobSummary> current) {
    Set<String> change = new LinkedHashSet<>();
    List<JobSummary> done = new ArrayList<>();
    List<Unmatched> unmatched = new ArrayList<>();
    Index index = new Index(current);
    for (JobSummary r : recorded) {
      Match m = index.find(r);
      switch (m) {
        case Found f -> {
          if (f.job().paused()) {
            done.add(f.job());
          } else {
            change.add(f.job().id());
          }
        }
        case NotFound n -> unmatched.add(new Unmatched(r, n.reason()));
      }
    }
    return new JobSelection(List.copyOf(change), done, List.of(), List.of(), unmatched);
  }

  /**
   * {@code jobs resume --from}: the jobs recorded as active, matched on the server and paused there
   * now. Jobs recorded as paused stay paused; jobs recorded in any other state are reported and
   * left alone.
   */
  public static JobSelection resumeFrom(List<JobSummary> recorded, List<JobSummary> current) {
    Set<String> change = new LinkedHashSet<>();
    List<JobSummary> done = new ArrayList<>();
    List<JobSummary> leftPaused = new ArrayList<>();
    List<JobSummary> errorState = new ArrayList<>();
    List<Unmatched> unmatched = new ArrayList<>();
    Index index = new Index(current);
    for (JobSummary r : recorded) {
      if (r.paused()) {
        leftPaused.add(r);
        continue;
      }
      if (!ACTIVE.contains(r.state())) {
        errorState.add(r);
        continue;
      }
      Match m = index.find(r);
      switch (m) {
        case Found f -> {
          if (f.job().paused()) {
            change.add(f.job().id());
          } else {
            done.add(f.job());
          }
        }
        case NotFound n -> unmatched.add(new Unmatched(r, n.reason()));
      }
    }
    return new JobSelection(List.copyOf(change), done, leftPaused, errorState, unmatched);
  }

  /** {@code id "label" (report, owner)} for plan and log lines. */
  public static String describe(JobSummary job) {
    return job.id()
        + " \""
        + job.label()
        + "\" ("
        + (job.reportUnitUri().isEmpty() ? "no report" : job.reportUnitUri())
        + ", "
        + (job.owner().isEmpty() ? "no owner" : job.owner())
        + (job.state().isEmpty() ? ", no state" : ", " + job.state())
        + ")";
  }

  private sealed interface Match permits Found, NotFound {}

  private record Found(JobSummary job) implements Match {}

  private record NotFound(String reason) implements Match {}

  /** The current jobs by id and by label + report + owner. */
  private static final class Index {
    private final Map<String, JobSummary> byId = new LinkedHashMap<>();
    private final Map<String, List<JobSummary>> byKey = new LinkedHashMap<>();

    Index(List<JobSummary> current) {
      for (JobSummary job : current) {
        byId.put(job.id(), job);
        byKey.computeIfAbsent(key(job), k -> new ArrayList<>()).add(job);
      }
    }

    Match find(JobSummary recorded) {
      Optional<JobSummary> same = Optional.ofNullable(byId.get(recorded.id()));
      if (same.isPresent() && key(same.get()).equals(key(recorded))) {
        return new Found(same.get());
      }
      List<JobSummary> candidates = byKey.getOrDefault(key(recorded), List.of());
      if (candidates.size() == 1) {
        return new Found(candidates.get(0));
      }
      if (candidates.isEmpty()) {
        return new NotFound("no job on the server has this id, or this label, report and owner");
      }
      return new NotFound(
          candidates.size()
              + " jobs on the server share this label, report and owner ("
              + String.join(", ", candidates.stream().map(JobSummary::id).toList())
              + "); jrsctl does not guess between them");
    }

    private static String key(JobSummary job) {
      return job.label() + "\u0000" + job.reportUnitUri() + "\u0000" + job.owner();
    }
  }
}
