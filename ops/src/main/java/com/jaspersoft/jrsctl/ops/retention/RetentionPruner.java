package com.jaspersoft.jrsctl.ops.retention;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retention pruning of the snapshot tree (spec §5.6, §14 phase 8): applies {@code
 * backups.retentionDays} and {@code backups.maxSnapshots} through {@link SnapshotStore#prune},
 * never touching a run named by {@link RetentionProtection}, then drops the {@code snapshots} rows
 * of every directory it deleted and writes one {@code runs.prune} audit row. Invariants: pruning is
 * not a journaled run, so {@code step_transitions} is never written; a dry run computes the same
 * candidates and mutates nothing, not even the audit trail; {@link #afterSuccessfulRun} is the
 * best-effort automatic form, which additionally protects the run that just finished, skips when
 * the run lock is held and never throws; {@link #prune} is the operator's explicit form and lets
 * I/O failures surface so the command can report them.
 */
public final class RetentionPruner {

  public static final String AUDIT_ACTION = "runs.prune";

  private static final Logger LOG = LoggerFactory.getLogger(RetentionPruner.class);

  /** One snapshot the pruner removed (or, in a dry run, would remove). */
  public record Removed(String id, String runId, String stepId, Path path) {}

  /**
   * Outcome of one pruning pass: {@code kept} snapshots remain on disk, of which {@code
   * protectedCount} belong to a protected run.
   */
  public record Result(boolean dryRun, List<Removed> removed, int kept, int protectedCount) {
    public Result {
      removed = List.copyOf(removed);
    }
  }

  private final Services services;
  private final SnapshotStore snapshots;
  private final String actor;

  public RetentionPruner(Services services, SnapshotStore snapshots, String actor) {
    this.services = Objects.requireNonNull(services, "services");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.actor = Objects.requireNonNull(actor, "actor");
  }

  /** A pruner over the home's snapshot tree, auditing as the current OS user. */
  public static RetentionPruner of(Services services) {
    return new RetentionPruner(
        services,
        new SnapshotStore(services.home(), services.platform().files(), services.clock()),
        System.getProperty("user.name", "unknown"));
  }

  /**
   * The operator's {@code runs prune}: prunes per {@code backups.*}, always writing an audit row
   * unless {@code dryRun}, in which case nothing changes and the result lists what would go.
   */
  public Result prune(boolean dryRun) throws IOException {
    return prune(dryRun, Set.of(), true);
  }

  /**
   * Automatic pruning at the end of the successful run {@code runId}: that run's own snapshots are
   * protected explicitly, the run lock is taken for the duration (and the pass is skipped when it
   * is held), an audit row is written only when something was removed, and any failure is logged as
   * a warning instead of thrown, so it can never change the run's outcome.
   */
  public Optional<Result> afterSuccessfulRun(String runId) {
    Objects.requireNonNull(runId, "runId");
    try (RunLock unusedLock =
        new RunLock(services.home(), runId + "-prune", services.clock().instant())) {
      return Optional.of(prune(false, Set.of(runId), false));
    } catch (LockHeldException held) {
      LOG.info(
          "retention pruning after run {} skipped: run lock held by {}", runId, held.holderRunId());
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      LOG.warn("retention pruning after run {} failed: {}", runId, e.toString());
      return Optional.empty();
    }
  }

  private Result prune(boolean dryRun, Set<String> alsoProtected, boolean auditAlways)
      throws IOException {
    StateStore store = services.stateStore().get();
    Config.Backups backups = services.config().backups();
    Set<String> protectedRuns = new HashSet<>(RetentionProtection.compute(store).runIds());
    protectedRuns.addAll(alsoProtected);
    Duration retention = Duration.ofDays(backups.retentionDays());
    int max = backups.maxSnapshots();

    List<Snapshot> all = snapshots.list();
    List<Snapshot> gone =
        dryRun
            ? snapshots.pruneCandidates(retention, max, protectedRuns)
            : snapshots.prune(retention, max, protectedRuns);
    Set<Path> goneDirs = new HashSet<>();
    List<Removed> removed = new ArrayList<>();
    for (Snapshot s : gone) {
      goneDirs.add(s.dir());
      removed.add(new Removed(s.runId() + "/" + s.stepId(), s.runId(), s.stepId(), s.dir()));
      if (!dryRun) {
        store.deleteSnapshot(s.runId(), s.stepId());
      }
    }
    if (!dryRun) {
      sweepStaleRows(store, protectedRuns);
    }
    int kept = 0;
    int protectedKept = 0;
    for (Snapshot s : all) {
      if (goneDirs.contains(s.dir())) {
        continue;
      }
      kept++;
      if (protectedRuns.contains(s.runId())) {
        protectedKept++;
      }
    }
    if (!dryRun && (auditAlways || !removed.isEmpty())) {
      store.audit(
          actor,
          AUDIT_ACTION,
          "removed "
              + removed.size()
              + " snapshot(s); kept "
              + kept
              + " ("
              + protectedKept
              + " protected); retentionDays="
              + backups.retentionDays()
              + " maxSnapshots="
              + max);
    }
    return new Result(dryRun, removed, kept, protectedKept);
  }

  /**
   * Drops {@code snapshots} rows of unprotected runs whose directory no longer exists (an earlier
   * pass that deleted the directory but died before the row went, or an operator's manual
   * clean-up).
   */
  private void sweepStaleRows(StateStore store, Set<String> protectedRuns) {
    for (SnapshotRecord row : store.snapshots()) {
      if (protectedRuns.contains(row.runId()) || Files.exists(row.path())) {
        continue;
      }
      LOG.info("dropping stale snapshot row {} ({} is gone)", row.id(), row.path());
      store.deleteSnapshot(row.runId(), row.stepId());
    }
  }
}
