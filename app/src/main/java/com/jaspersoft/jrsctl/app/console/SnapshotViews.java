package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.engine.LockHeldException;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotManifest;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.retention.RetentionProtection;
import com.jaspersoft.jrsctl.ops.retention.RetentionPruner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

final class SnapshotViews {

  private final Services services;
  private final SnapshotStore snapshotStore;

  SnapshotViews(
      Services services, RunManager runs, DoctorCache doctor, String bind, IntSupplier port) {
    this.services = Objects.requireNonNull(services, "services");
    this.snapshotStore =
        new SnapshotStore(services.home(), services.platform().files(), services.clock());
  }

  private StateStore store() {
    return services.stateStore().get();
  }

  SnapshotsDoc snapshots() {
    List<Snapshot> snapshots = List.of();
    try {
      snapshots = snapshotStore.list();
    } catch (IOException | RuntimeException e) {
      // snapshot directory may be empty or unreadable
    }
    StateStore store = store();
    RetentionProtection.Protected prot = RetentionProtection.compute(store);
    Map<String, String> protectedMap = prot.reasons();

    List<SnapshotsDoc.SnapshotItem> items = new ArrayList<>();
    long totalBytes = 0;
    for (Snapshot s : snapshots) {
      SnapshotManifest manifest = s.manifest();
      long bytes = manifest.totalSize();
      totalBytes += bytes;
      List<String> reasons = new ArrayList<>();
      if (protectedMap.containsKey(s.runId())) {
        reasons.add(protectedMap.get(s.runId()));
      }
      List<SnapshotsDoc.FileItem> files = new ArrayList<>();
      for (SnapshotManifest.Entry entry : manifest.entries()) {
        files.add(new SnapshotsDoc.FileItem(entry.path(), entry.sha256(), entry.size()));
      }
      items.add(
          new SnapshotsDoc.SnapshotItem(
              s.runId() + "/" + s.stepId(),
              s.runId(),
              s.stepId(),
              manifest.createdAt(),
              bytes,
              manifest.entries().size(),
              reasons,
              files));
    }

    return new SnapshotsDoc(
        items.size(),
        totalBytes,
        services.config().backups().retentionDays(),
        services.config().backups().maxSnapshots(),
        items);
  }

  /**
   * A dry run only reads; a real prune takes the run lock the CLI's {@code runs prune} takes, so it
   * cannot compute retention protection while another process's run has no row yet (spec §5.5;
   * assessment item S3). A held lock surfaces as {@link LockHeldException} for the API to map.
   */
  SnapshotsDoc.PruneResult prune(boolean dryRun) throws IOException {
    RetentionPruner pruner = RetentionPruner.of(services);
    RetentionPruner.Result res;
    if (dryRun) {
      res = pruner.prune(true);
    } else {
      String lockId = "prune-" + RunIds.next(services.clock());
      try (RunLock unusedLock = new RunLock(services.home(), lockId, services.clock().instant())) {
        res = pruner.prune(false);
      }
    }
    int remaining = snapshotStore.list().size();
    StateStore store = store();
    RetentionProtection.Protected prot = RetentionProtection.compute(store);
    return new SnapshotsDoc.PruneResult(res.removed().size(), 0L, remaining, prot.runIds().size());
  }
}
