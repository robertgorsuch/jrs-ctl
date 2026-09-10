package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotManifest;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What each file the hotfix touches looked like immediately before the swap, read from the run's
 * own pre-swap snapshot rather than from the plan.
 *
 * <p>The plan hashes its targets when it is built, which is the right moment for the fingerprint
 * that refuses a server that changed underneath it, and the wrong moment for anything the run has
 * to undo. {@code upgrade --reapply-hotfixes} builds the apply plan before the upgrade and runs it
 * after, so between the two the whole webapp is replaced: every plan-time hash then describes files
 * that no longer exist. Recording those as the "before" state leaves {@code hotfix_files} claiming
 * the previous version's bytes, and a later {@code hotfix rollback} restores the snapshot correctly
 * and then reports that every file differs from it.
 *
 * <p>Invariants: the snapshot is the run's own, taken by {@code snapshot} one step before the swap,
 * so it is the state the compensation will restore to and nothing else can be; a path the snapshot
 * does not list did not exist, which is exactly what an absent before-hash means in {@code
 * hotfix_files}; {@link #unknown()} is the one case where nothing can be said, and callers fall
 * back to the plan rather than treat every file as new.
 */
record PriorState(Map<Path, String> hashes, boolean known) {

  PriorState {
    hashes = Map.copyOf(hashes);
  }

  /**
   * No snapshot was found, so the caller must not read "absent from the map" as "did not exist".
   */
  static PriorState unknown() {
    return new PriorState(Map.of(), false);
  }

  /**
   * The state the run's pre-swap snapshot recorded. A snapshot that exists but lists nothing is a
   * definite answer: this hotfix only adds files.
   */
  static PriorState of(SnapshotStore snapshots, Context ctx, String stepId) throws IOException {
    Optional<Snapshot> snapshot = snapshots.find(ctx.runId(), stepId);
    if (snapshot.isEmpty()) {
      return unknown();
    }
    Map<Path, String> hashes = new LinkedHashMap<>();
    Path base = snapshot.get().manifest().baseDir();
    for (SnapshotManifest.Entry entry : snapshot.get().manifest().entries()) {
      hashes.put(normalise(base.resolve(entry.path())), entry.sha256());
    }
    return new PriorState(hashes, true);
  }

  /** Hash of {@code path} before the swap; empty when it did not exist. */
  Optional<String> before(Path path) {
    return Optional.ofNullable(hashes.get(normalise(path)));
  }

  private static Path normalise(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
