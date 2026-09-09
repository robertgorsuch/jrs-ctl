package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotManifest;
import com.jaspersoft.jrsctl.core.state.Customization;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.ops.customizations.DefaultCustomizationOperations;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase D of spec §10.2, report-first: classify installed hotfixes against the upgraded server and
 * reconcile registered customizations by 3-way comparison. Invariants: {@code plan-hotfix-reapply}
 * only changes state when the plan embeds re-application steps (it marks those hotfixes {@code
 * SUPERSEDED} so their apply plan can record them afresh) and restores that state on compensation;
 * {@code plan-customization-reapply} copies a customized file over the new one only when the new
 * file still equals the registered original, snapshots the new file first, and otherwise reports a
 * CONFLICT with a unified diff and never blind-copies.
 */
final class ReconcileSteps {

  static final String PLAN_HOTFIX_REAPPLY = "plan-hotfix-reapply";
  static final String PLAN_CUSTOMIZATION_REAPPLY = "plan-customization-reapply";
  static final String REAPPLY_SNAPSHOT = "reapply-customizations";
  static final String SUPERSEDED_MARKER = PLAN_HOTFIX_REAPPLY + ".superseded";
  static final String AUDIT_REAPPLY = "hotfix.reapply-planned";
  static final String AUDIT_CUSTOMIZATION_APPLIED = "customizations.reapplied";
  static final String CONFLICT = "CONFLICT";

  private ReconcileSteps() {}

  static final class PlanHotfixReapply implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;
    private final List<String> embedded;

    PlanHotfixReapply(UpgradeRuntime rt, UpgradeInput in, List<String> embeddedHotfixIds) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
      this.embedded = List.copyOf(embeddedHotfixIds);
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(SUPERSEDED_MARKER);
    }

    @Override
    public String id() {
      return PLAN_HOTFIX_REAPPLY;
    }

    @Override
    public String title() {
      return "classify installed hotfixes: REAPPLICABLE or SUPERSEDED";
    }

    @Override
    public String phase() {
      return Phases.RECONCILE;
    }

    @Override
    public String detail() {
      return embedded.isEmpty()
          ? "report only; re-application needs --reapply-hotfixes"
          : "re-applies " + String.join(", ", embedded) + " in the steps that follow";
    }

    @Override
    public boolean mutating() {
      return !embedded.isEmpty();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      ServerIdentity identity;
      try {
        identity = rt.identity();
      } catch (JrsUnreachableException | RestException | ConfigException e) {
        return Failures.recoverable(
            "cannot read the upgraded server's identity: " + e.getMessage(),
            "check the server; the run is rolled back to point B");
      }
      List<HotfixReconciler.Classification> all = HotfixReconciler.classify(rt, in, identity);
      if (all.isEmpty()) {
        Logs.info(rt, ctx, out, this, "no installed hotfixes to reconcile");
      }
      for (HotfixReconciler.Classification c : all) {
        ctx.cancel().checkpoint();
        Event.Log.Level level =
            c.status() == HotfixReconciler.Status.REAPPLICABLE
                ? Event.Log.Level.INFO
                : Event.Log.Level.WARN;
        Logs.emit(rt, ctx, out, this, level, c.describe());
      }
      if (embedded.isEmpty()) {
        return StepResult.ok();
      }
      StateStore store = rt.store();
      List<String> marked = new ArrayList<>();
      for (String id : embedded) {
        store.updateHotfixState(id, HotfixState.SUPERSEDED);
        store.audit(rt.actor(), AUDIT_REAPPLY, id + " will be re-applied in run " + ctx.runId());
        marked.add(id);
      }
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), String.join("\n", marked), StandardCharsets.UTF_8);
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write " + marker(ctx) + ": " + e.getMessage(),
            "check that the run directory is writable");
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        if (Files.isRegularFile(marker(ctx))) {
          for (String id : Files.readAllLines(marker(ctx), StandardCharsets.UTF_8)) {
            if (!id.isBlank()) {
              rt.store().updateHotfixState(id.strip(), HotfixState.INSTALLED);
            }
          }
          Files.deleteIfExists(marker(ctx));
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore hotfix states: " + Failures.describe(e),
            "check jrsctl hotfix list and correct the states");
      }
    }
  }

  static final class PlanCustomizationReapply implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    PlanCustomizationReapply(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return PLAN_CUSTOMIZATION_REAPPLY;
    }

    @Override
    public String title() {
      return "reconcile registered customizations (3-way)";
    }

    @Override
    public String phase() {
      return Phases.RECONCILE;
    }

    @Override
    public String detail() {
      return "new == original -> re-applied (snapshotted first); otherwise CONFLICT with a diff";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<Customization> registered = rt.store().customizations();
      if (registered.isEmpty()) {
        Logs.info(rt, ctx, out, this, "no registered customizations");
        return StepResult.ok();
      }
      List<Path> autoApply = new ArrayList<>();
      List<Path> sources = new ArrayList<>();
      int conflicts = 0;
      for (Customization c : registered) {
        ctx.cancel().checkpoint();
        Path file = c.path().toAbsolutePath().normalize();
        Optional<Snapshot> snapshot;
        try {
          snapshot =
              rt.snapshots()
                  .find(
                      DefaultCustomizationOperations.runIdFor(file),
                      DefaultCustomizationOperations.STEP);
          if (snapshot.isPresent()) {
            rt.snapshots().verify(snapshot.get());
          }
        } catch (IOException | RuntimeException e) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              CONFLICT + " " + file + ": snapshot unreadable: " + Failures.describe(e));
          conflicts++;
          continue;
        }
        if (snapshot.isEmpty() || snapshot.get().manifest().entries().isEmpty()) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              CONFLICT + " " + file + ": registered snapshot is missing; re-register it");
          conflicts++;
          continue;
        }
        SnapshotManifest.Entry entry = snapshot.get().manifest().entries().get(0);
        Path customized = snapshot.get().payloadFile(entry);
        if (!Files.isRegularFile(file)) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              CONFLICT
                  + " "
                  + file
                  + ": absent after the upgrade; customized copy at "
                  + customized);
          conflicts++;
          continue;
        }
        String current;
        try {
          current = rt.files().sha256(file);
        } catch (IOException e) {
          Logs.warn(rt, ctx, out, this, CONFLICT + " " + file + ": unreadable: " + e.getMessage());
          conflicts++;
          continue;
        }
        if (current.equals(entry.sha256())) {
          Logs.info(rt, ctx, out, this, file + ": customization already in place");
          continue;
        }
        if (current.equals(c.originalSha256())) {
          autoApply.add(file);
          sources.add(customized);
          continue;
        }
        conflicts++;
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            CONFLICT
                + " "
                + file
                + ": the upgrade changed this file (original "
                + c.originalSha256()
                + ", now "
                + current
                + "); customized copy at "
                + customized);
        try {
          for (String line :
              DefaultCustomizationOperations.diffLines(
                  customized, file, "customized:" + file, "upgraded:" + file)) {
            Logs.warn(rt, ctx, out, this, "  " + line);
          }
        } catch (IOException e) {
          Logs.warn(rt, ctx, out, this, "  (diff unavailable: " + e.getMessage() + ")");
        }
      }
      if (!autoApply.isEmpty()) {
        try {
          Snapshot before =
              rt.snapshots()
                  .create(ctx.runId(), REAPPLY_SNAPSHOT, autoApply, in.paths().commonBase());
          for (int i = 0; i < autoApply.size(); i++) {
            ctx.cancel().checkpoint();
            copyOver(sources.get(i), autoApply.get(i));
            rt.store()
                .audit(
                    rt.actor(),
                    AUDIT_CUSTOMIZATION_APPLIED,
                    autoApply.get(i) + " in run " + ctx.runId());
            Logs.info(
                rt,
                ctx,
                out,
                this,
                autoApply.get(i)
                    + ": customization re-applied (new file equalled the original; saved to "
                    + before.dir()
                    + ")");
          }
        } catch (IOException | RuntimeException e) {
          return Failures.recoverable(
              "cannot re-apply customizations: " + Failures.describe(e),
              "re-apply them by hand from " + rt.home().snapshots(),
              autoApply,
              List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(REAPPLY_SNAPSHOT)));
        }
      }
      Logs.info(
          rt,
          ctx,
          out,
          this,
          registered.size()
              + " customization(s): "
              + autoApply.size()
              + " re-applied, "
              + conflicts
              + " conflict(s) left for the operator");
      return StepResult.ok();
    }

    private void copyOver(Path source, Path target) throws IOException {
      Path staged = target.resolveSibling("." + target.getFileName() + ".jrsctl-reapply");
      byte[] buffer = new byte[64 * 1024];
      try (InputStream inStream = Files.newInputStream(source);
          OutputStream outStream = Files.newOutputStream(staged)) {
        int read;
        while ((read = inStream.read(buffer)) != -1) {
          outStream.write(buffer, 0, read);
        }
      }
      rt.files().atomicReplace(staged, target);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Optional<Snapshot> before = rt.snapshots().find(ctx.runId(), REAPPLY_SNAPSHOT);
        if (before.isPresent()) {
          rt.snapshots().restore(before.get());
          Logs.info(rt, ctx, out, this, "upgraded files restored from " + before.get().dir());
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore the upgraded files: " + Failures.describe(e),
            "restore by hand from "
                + rt.home().snapshots().resolve(ctx.runId()).resolve(REAPPLY_SNAPSHOT));
      }
    }
  }
}
