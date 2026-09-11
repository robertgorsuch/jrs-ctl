package com.jaspersoft.jrsctl.ops.retention;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Trees;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.LockHeldException;
import com.jaspersoft.jrsctl.core.state.RunLock;
import com.jaspersoft.jrsctl.core.state.RunRecord;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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

  /** Where {@code import} keeps its pre-import zips: {@code snapshots/pre-import/}. */
  static final String PRE_IMPORT_DIR = "pre-import";

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
    Set<String> prunedRuns = new HashSet<>();
    List<Removed> removed = new ArrayList<>();
    for (Snapshot s : gone) {
      goneDirs.add(s.dir());
      prunedRuns.add(s.runId());
      removed.add(new Removed(s.runId() + "/" + s.stepId(), s.runId(), s.stepId(), s.dir()));
      if (!dryRun) {
        store.deleteSnapshot(s.runId(), s.stepId());
      }
    }
    int kept = 0;
    int protectedKept = 0;
    Set<String> runsWithSnapshots = new HashSet<>();
    for (Snapshot s : all) {
      if (goneDirs.contains(s.dir())) {
        continue;
      }
      runsWithSnapshots.add(s.runId());
      kept++;
      if (protectedRuns.contains(s.runId())) {
        protectedKept++;
      }
    }
    Set<Path> snapshotDirs = new HashSet<>();
    for (Snapshot s : all) {
      snapshotDirs.add(s.dir());
    }
    Loose loose =
        looseArtefacts(
            store, protectedRuns, prunedRuns, runsWithSnapshots, snapshotDirs, retention, dryRun);
    removed.addAll(loose.removed());
    kept += loose.kept();
    protectedKept += loose.protectedKept();
    if (!dryRun) {
      sweepStaleRows(store, protectedRuns);
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

  private record Loose(List<Removed> removed, int kept, int protectedKept) {}

  /**
   * Review finding 1.19: two kinds of backup live under the snapshots root without a manifest and
   * so were invisible to {@link SnapshotStore#list}: upgrade sets ({@code <runId>/backup-webapp},
   * the full export and {@code upgrade.json}) and pre-import zips ({@code pre-import/*.zip}). An
   * upgrade set follows its run: it goes when the run's step snapshots went in this pass, or when
   * the run is unprotected, holds no step snapshot any more and started before the retention
   * cut-off. A pre-import zip names no run, so it goes by age alone and never while any run is
   * pending recovery, whose rollback may re-import it.
   */
  private Loose looseArtefacts(
      StateStore store,
      Set<String> protectedRuns,
      Set<String> prunedRuns,
      Set<String> runsWithSnapshots,
      Set<Path> snapshotDirs,
      Duration retention,
      boolean dryRun)
      throws IOException {
    Path root = services.home().snapshots();
    if (!Files.isDirectory(root)) {
      return new Loose(List.of(), 0, 0);
    }
    Optional<Instant> cutoff =
        retention.isZero() || retention.isNegative()
            ? Optional.empty()
            : Optional.of(services.clock().instant().minus(retention));
    List<Removed> removed = new ArrayList<>();
    int kept = 0;
    int protectedKept = 0;
    boolean pending = !store.pendingRuns().isEmpty();
    List<Path> entries;
    try (Stream<Path> listing = Files.list(root)) {
      entries = listing.sorted().toList();
    }
    for (Path entry : entries) {
      String name = entry.getFileName().toString();
      if (name.equals(PRE_IMPORT_DIR) && Files.isDirectory(entry)) {
        List<Path> zips;
        try (Stream<Path> listing = Files.list(entry)) {
          zips = listing.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path zip : zips) {
          boolean expired =
              cutoff.isPresent()
                  && Files.getLastModifiedTime(zip).toInstant().isBefore(cutoff.get());
          if (expired && !pending) {
            removed.add(
                new Removed(
                    PRE_IMPORT_DIR + "/" + zip.getFileName(),
                    PRE_IMPORT_DIR,
                    zip.getFileName().toString(),
                    zip));
            if (!dryRun) {
              LOG.info("pruning pre-import snapshot {}", zip);
              Files.delete(zip);
            }
          } else {
            kept++;
            if (expired) {
              protectedKept++;
            }
          }
        }
        continue;
      }
      if (!Files.isDirectory(entry)
          || runsWithSnapshots.contains(name)
          || !holdsLooseContent(entry, snapshotDirs)) {
        continue;
      }
      if (protectedRuns.contains(name)) {
        kept++;
        protectedKept++;
        continue;
      }
      boolean expired = prunedRuns.contains(name);
      if (!expired && cutoff.isPresent()) {
        Instant started =
            store
                .run(name)
                .map(RunRecord::startedAt)
                .orElse(Files.getLastModifiedTime(entry).toInstant());
        expired = started.isBefore(cutoff.get());
      }
      if (!expired) {
        kept++;
        continue;
      }
      removed.add(new Removed(name + "/*", name, "*", entry));
      if (!dryRun) {
        LOG.info("pruning the backups of run {} under {}", name, entry);
        Trees.deleteRecursively(entry);
      }
    }
    return new Loose(removed, kept, protectedKept);
  }

  /** True when {@code runDir} holds anything besides manifest snapshot directories. */
  private static boolean holdsLooseContent(Path runDir, Set<Path> snapshotDirs) throws IOException {
    try (Stream<Path> children = Files.list(runDir)) {
      return children.anyMatch(child -> !snapshotDirs.contains(child.toAbsolutePath().normalize()));
    }
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
