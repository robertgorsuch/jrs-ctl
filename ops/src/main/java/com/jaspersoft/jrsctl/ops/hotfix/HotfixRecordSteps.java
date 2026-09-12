package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.Trees;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The record phase of the apply plan (spec §8.2): the journal rows that make the hotfix visible to
 * {@code hotfix list} and to a later rollback. Invariant: the rows are written only after the swap
 * has verified what it landed, so nothing is recorded as installed that is not.
 *
 * <p>One phase of the plan per file, as the upgrade package does it (roadmap item 17). The ids, the
 * phase names and the helpers every phase shares stay in {@link ApplySteps}.
 */
final class HotfixRecordSteps {

  private HotfixRecordSteps() {}

  /** Step 12: record the installation in the state store. */
  static final class RecordInstalled implements Step {
    private final HotfixRuntime rt;
    private final ApplyInput in;

    RecordInstalled(HotfixRuntime rt, ApplyInput in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return ApplySteps.RECORD_INSTALLED;
    }

    @Override
    public String title() {
      return "record " + in.manifest().id() + " as installed";
    }

    @Override
    public String phase() {
      return ApplySteps.RECORD;
    }

    @Override
    public String detail() {
      return "hotfixes_installed, hotfix_files ("
          + rows(in.manifest().id(), planState()).size()
          + " rows), audit";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      StateStore store = rt.store();
      String id = in.manifest().id();
      PriorState before;
      try {
        PriorState fromSnapshot = PriorState.of(rt.snapshots(), ctx, ApplySteps.SNAPSHOT);
        before = fromSnapshot.known() ? fromSnapshot : planState();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot read the pre-swap snapshot of run " + ctx.runId() + ": " + e.getMessage(),
            "without it the recorded before-hashes would not match what rollback restores");
      }
      Optional<HotfixInstalled> existing = store.hotfix(id);
      if (existing.isPresent()) {
        HotfixInstalled h = existing.get();
        if (h.state() == HotfixState.INSTALLED) {
          if (h.installedRunId().equals(ctx.runId())) {
            return StepResult.ok();
          }
          return Failures.recoverable(
              id + " is already recorded as installed by run " + h.installedRunId(),
              "roll back the earlier installation first");
        }
        // The id is the primary key; a rolled-back row is superseded by this installation.
        store.deleteHotfix(id);
      }
      store.recordHotfixInstalled(
          new HotfixInstalled(
              id,
              in.manifest().version(),
              in.manifest().title(),
              ctx.runId(),
              Optional.of(ctx.runId() + "/" + ApplySteps.SNAPSHOT),
              HotfixState.INSTALLED,
              rt.clock().instant()),
          rows(id, before));
      store.audit(
          rt.actor(),
          ApplySteps.AUDIT_APPLIED,
          id + " version " + in.manifest().version() + " in run " + ctx.runId());
      try {
        // The swap moved every payload out of staging; what is left is empty directories.
        Trees.deleteRecursively(in.stagingDir(ctx));
      } catch (IOException e) {
        out.emit(
            new Event.Log(
                rt.clock().instant(),
                ctx.runId(),
                Optional.of(id()),
                phase(),
                Event.Log.Level.WARN,
                "staging left behind: " + e.getMessage()));
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      StateStore store = rt.store();
      String id = in.manifest().id();
      Optional<HotfixInstalled> existing = store.hotfix(id);
      if (existing.isPresent()
          && existing.get().state() == HotfixState.INSTALLED
          && existing.get().installedRunId().equals(ctx.runId())) {
        store.updateHotfixState(id, HotfixState.ROLLED_BACK);
        store.audit(
            rt.actor(),
            ApplySteps.AUDIT_ROLLED_BACK,
            id + " (compensation of run " + ctx.runId() + ")");
      }
      return StepResult.ok();
    }

    /**
     * The {@code hotfix_files} rows. Every before-hash comes from {@code before}, so what is
     * written here is what {@code hotfix rollback} will find after it restores the snapshot; a
     * sibling only gets a delete row when it actually existed to be deleted.
     */
    private List<HotfixFile> rows(String hotfixId, PriorState before) {
      List<HotfixFile> rows = new ArrayList<>();
      for (FileTarget t : in.targets()) {
        String action =
            switch (t.action()) {
              case ADD -> "add";
              case REPLACE -> "replace";
              case DELETE -> "delete";
            };
        rows.add(
            new HotfixFile(hotfixId, t.target(), action, before.before(t.target()), t.after()));
        for (FileTarget.Sibling s : t.replaces()) {
          Optional<String> was = before.before(s.path());
          if (was.isPresent()) {
            rows.add(new HotfixFile(hotfixId, s.path(), "delete", was, Optional.empty()));
          }
        }
      }
      return List.copyOf(rows);
    }

    /** The plan's own view, used for the summary line and when no snapshot was taken. */
    private PriorState planState() {
      Map<Path, String> hashes = new LinkedHashMap<>();
      for (FileTarget t : in.targets()) {
        t.before().ifPresent(h -> hashes.put(t.target().toAbsolutePath().normalize(), h));
        for (FileTarget.Sibling s : t.replaces()) {
          s.before().ifPresent(h -> hashes.put(s.path().toAbsolutePath().normalize(), h));
        }
      }
      return new PriorState(hashes, true);
    }
  }
}
