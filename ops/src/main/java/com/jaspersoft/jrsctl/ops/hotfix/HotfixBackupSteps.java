package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.state.SnapshotRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The backup phase of the apply plan (spec §8.2): the snapshot every later step restores from.
 * Invariant: the snapshot is verified as it is created, and an existing snapshot for the same run
 * and step is reused rather than rewritten, so re-execution converges.
 *
 * <p>One phase of the plan per file, as the upgrade package does it (roadmap item 17). The ids, the
 * phase names and the helpers every phase shares stay in {@link ApplySteps}.
 */
final class HotfixBackupSteps {

  private HotfixBackupSteps() {}

  /** Step 5: snapshot every file that will be replaced or deleted. */
  static final class TakeSnapshot extends ApplySteps.ReadOnly {
    TakeSnapshot(HotfixRuntime rt, ApplyInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return ApplySteps.SNAPSHOT;
    }

    @Override
    public String title() {
      return "snapshot the files this hotfix replaces or deletes";
    }

    @Override
    public String phase() {
      return ApplySteps.BACKUP;
    }

    @Override
    public String detail() {
      return "up to "
          + in.touched().size()
          + " file(s) -> "
          + rt.home().snapshots().resolve("{runId}").resolve(ApplySteps.SNAPSHOT);
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    /**
     * Snapshots every path the plan touches that exists right now, not only those the plan expected
     * to find. The difference matters when the plan was built against a webapp that has since been
     * replaced: a target the plan saw as absent may now exist, and without this it would be
     * overwritten with nothing kept to put back.
     */
    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<Path> paths = in.touched().stream().filter(Files::isRegularFile).distinct().toList();
      try {
        Snapshot snapshot =
            rt.snapshots().create(ctx.runId(), ApplySteps.SNAPSHOT, paths, in.paths().commonBase());
        rt.store()
            .recordSnapshot(
                new SnapshotRecord(
                    ctx.runId() + "/" + ApplySteps.SNAPSHOT,
                    ctx.runId(),
                    ApplySteps.SNAPSHOT,
                    snapshot.dir(),
                    rt.files().sha256(snapshot.manifestFile()),
                    Optional.of(in.manifest().id())));
        log(ctx, out, Event.Log.Level.INFO, paths.size() + " file(s) saved to " + snapshot.dir());
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot snapshot: " + Failures.describe(e),
            "check free space and permissions under " + rt.home().snapshots());
      }
    }
  }
}
