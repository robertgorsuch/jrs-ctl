package com.jaspersoft.jrsctl.ops.hotfix;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.core.state.HotfixFile;
import com.jaspersoft.jrsctl.core.state.HotfixInstalled;
import com.jaspersoft.jrsctl.core.state.HotfixState;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The steps of the rollback plan (spec §8.3) other than the service steps. Invariants: {@code
 * RestoreSnapshot} snapshots the current files first so that its own compensation can put the
 * hotfix back; hashes are verified before and after the restore; {@code RunSqlRollback} exists only
 * when the installed run's bundle copy still holds rollback scripts; {@code RecordRolledBack} flips
 * the state-store row and its compensation flips it back.
 */
final class RollbackSteps {

  static final String PHASE = "rollback";
  static final String RESTORE_SNAPSHOT = "restore-snapshot";
  static final String RUN_SQL_ROLLBACK = "run-sql-rollback";
  static final String RECORD_ROLLED_BACK = "record-rolled-back";
  static final String PRE_ROLLBACK_PREFIX = "pre-rollback-";

  private RollbackSteps() {}

  /** One hotfix to roll back, with what the state store and the run directory know about it. */
  record Input(
      HotfixInstalled hotfix,
      List<HotfixFile> files,
      Optional<Manifest> manifest,
      Path bundleDir,
      List<Manifest.SqlEntry> sqlScripts,
      String phase,
      String suffix) {

    Input {
      Objects.requireNonNull(hotfix, "hotfix");
      files = List.copyOf(files);
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(bundleDir, "bundleDir");
      sqlScripts = List.copyOf(sqlScripts);
    }

    String id() {
      return hotfix.id();
    }

    /** True when any owned path lies under WEB-INF/lib or WEB-INF/classes. */
    boolean needsServiceStop() {
      return files.stream().anyMatch(f -> HotfixPaths.requiresServiceStop(f.path().toString()));
    }

    /** Rollback scripts, newest first. */
    List<String> rollbackScripts() {
      List<String> out = new ArrayList<>();
      for (int i = sqlScripts.size() - 1; i >= 0; i--) {
        sqlScripts.get(i).rollbackFile().ifPresent(out::add);
      }
      return out;
    }

    List<String> forwardScripts() {
      return sqlScripts.stream().map(Manifest.SqlEntry::file).toList();
    }

    String preRollbackStepId() {
      return PRE_ROLLBACK_PREFIX + hotfix.id();
    }

    List<Path> touched() {
      return files.stream().map(HotfixFile::path).toList();
    }
  }

  /** Restores every file from the installation snapshot and deletes what the hotfix added. */
  static final class RestoreSnapshot implements Step {
    private final HotfixRuntime rt;
    private final Input in;

    RestoreSnapshot(HotfixRuntime rt, Input in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return RESTORE_SNAPSHOT + in.suffix();
    }

    @Override
    public String title() {
      return "restore the files of " + in.id() + " from the snapshot";
    }

    @Override
    public String phase() {
      return in.phase();
    }

    @Override
    public String detail() {
      return "snapshot "
          + in.hotfix().installedRunId()
          + "/"
          + ApplySteps.SNAPSHOT
          + "; hashes verified before and after; "
          + in.files().size()
          + " file(s)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      FileOps files = rt.files();
      List<String> problems = new ArrayList<>();
      boolean needsSnapshot = in.files().stream().anyMatch(f -> f.beforeSha256().isPresent());
      try {
        Optional<Snapshot> snapshot = snapshot();
        if (needsSnapshot && snapshot.isEmpty()) {
          problems.add(
              "snapshot "
                  + in.hotfix().installedRunId()
                  + "/"
                  + ApplySteps.SNAPSHOT
                  + " is missing under "
                  + rt.home().snapshots());
        }
        if (snapshot.isPresent()) {
          rt.snapshots().verify(snapshot.get());
        }
      } catch (IOException | RuntimeException e) {
        problems.add("snapshot unusable: " + Failures.describe(e));
      }
      for (Path p : in.touched()) {
        if (Files.isRegularFile(p) && files.isLocked(p)) {
          problems.add(p + " is locked" + files.lockHolder(p).map(h -> " by " + h).orElse(""));
        }
      }
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            String.join("; ", problems),
            "restore the snapshot from a backup or end the process holding the files");
      }
      // review 3.3: a blind scan is a warning, not a pass
      return files
          .lockInspectionLimit()
          .map(limit -> CheckResult.warn("no locked file found, but " + limit))
          .orElseGet(CheckResult::pass);
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      FileOps files = rt.files();
      try {
        List<Path> current = in.touched().stream().filter(Files::isRegularFile).toList();
        Optional<Path> base =
            HotfixPaths.commonAncestor(current.stream().map(Path::getParent).toList());
        if (base.isPresent()) {
          rt.snapshots().create(ctx.runId(), in.preRollbackStepId(), current, base.get());
        }
        for (HotfixFile f : in.files()) {
          ctx.cancel().checkpoint();
          if (f.beforeSha256().isEmpty()) {
            Files.deleteIfExists(f.path());
          }
        }
        Optional<Snapshot> snapshot = snapshot();
        if (snapshot.isPresent()) {
          rt.snapshots().restore(snapshot.get());
        }
        List<String> mismatches = new ArrayList<>();
        for (HotfixFile f : in.files()) {
          if (f.beforeSha256().isPresent()) {
            Optional<String> actual = FileTarget.hashOf(files, f.path());
            if (!actual.equals(f.beforeSha256())) {
              mismatches.add(f.path() + " is " + actual.orElse("absent"));
            }
          }
        }
        if (!mismatches.isEmpty()) {
          return Failures.recoverable(
              "files differ from the snapshot after restore: " + String.join(", ", mismatches),
              "restore the snapshot by hand",
              in.touched(),
              backups());
        }
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot restore: " + e.getMessage(),
            "restore the snapshot by hand",
            in.touched(),
            backups());
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Optional<Snapshot> pre = rt.snapshots().find(ctx.runId(), in.preRollbackStepId());
        if (pre.isPresent()) {
          rt.snapshots().restore(pre.get());
        }
        for (HotfixFile f : in.files()) {
          if (f.afterSha256().isEmpty()) {
            Files.deleteIfExists(f.path());
          }
        }
        return StepResult.ok();
      } catch (IOException | UncheckedIOException e) {
        return Failures.recoverable(
            "cannot re-apply " + in.id() + ": " + e.getMessage(),
            "restore the pre-rollback snapshot by hand",
            in.touched(),
            List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(in.preRollbackStepId())));
      }
    }

    private Optional<Snapshot> snapshot() throws IOException {
      return rt.snapshots().find(in.hotfix().installedRunId(), ApplySteps.SNAPSHOT);
    }

    private List<Path> backups() {
      return List.of(
          rt.home().snapshots().resolve(in.hotfix().installedRunId()).resolve(ApplySteps.SNAPSHOT));
    }
  }

  /**
   * Runs the rollback scripts newest first; compensation re-applies the forward script of each
   * rollback script that started, and only those.
   */
  static final class RunSqlRollback implements Step {
    private final HotfixRuntime rt;
    private final Input in;

    RunSqlRollback(HotfixRuntime rt, Input in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return RUN_SQL_ROLLBACK + in.suffix();
    }

    @Override
    public String title() {
      return "run " + in.rollbackScripts().size() + " SQL rollback script(s) of " + in.id();
    }

    @Override
    public String phase() {
      return in.phase();
    }

    @Override
    public String detail() {
      return "via JDBC, newest script first; scripts from " + in.bundleDir();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      for (String script : in.rollbackScripts()) {
        if (!Files.isRegularFile(in.bundleDir().resolve(script))) {
          return CheckResult.fail(
              "rollback script " + script + " is missing under " + in.bundleDir(),
              "restore the run directory of " + in.hotfix().installedRunId());
        }
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      return SqlRunner.run(
          rt,
          ctx,
          out,
          id(),
          phase(),
          in.bundleDir(),
          in.rollbackScripts(),
          SqlProgress.of(ctx, id()));
    }

    /**
     * Puts back what the rollback undid. Only the forward script of a rollback script that actually
     * started is re-applied: re-applying the others would install changes this hotfix's rollback
     * never removed, in a run whose whole purpose was to remove them.
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      List<String> started;
      try {
        started = SqlProgress.of(ctx, id()).started();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot read which SQL rollback scripts ran in run "
                + ctx.runId()
                + ": "
                + e.getMessage(),
            "check the run directory, then re-apply the hotfix SQL by hand from the bundle");
      }
      List<String> forward = new ArrayList<>();
      for (Manifest.SqlEntry entry : in.sqlScripts()) {
        if (entry.rollbackFile().map(started::contains).orElse(false)) {
          forward.add(entry.file());
        }
      }
      return SqlRunner.run(
          rt, ctx, out, id(), phase(), in.bundleDir(), forward, SqlProgress.none());
    }
  }

  /** Marks the hotfix as rolled back; compensation marks it installed again. */
  static final class RecordRolledBack implements Step {
    private final HotfixRuntime rt;
    private final Input in;

    RecordRolledBack(HotfixRuntime rt, Input in) {
      this.rt = rt;
      this.in = in;
    }

    @Override
    public String id() {
      return RECORD_ROLLED_BACK + in.suffix();
    }

    @Override
    public String title() {
      return "record " + in.id() + " as rolled back";
    }

    @Override
    public String phase() {
      return in.phase();
    }

    @Override
    public String detail() {
      return "hotfixes_installed.state = ROLLED_BACK, audit";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      StateStore store = rt.store();
      Optional<HotfixInstalled> row = store.hotfix(in.id());
      if (row.isEmpty()) {
        return Failures.recoverable(
            in.id() + " is no longer in the state store", "run jrsctl hotfix list");
      }
      if (row.get().state() != HotfixState.ROLLED_BACK) {
        store.updateHotfixState(in.id(), HotfixState.ROLLED_BACK);
        store.audit(rt.actor(), ApplySteps.AUDIT_ROLLED_BACK, in.id() + " in run " + ctx.runId());
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      StateStore store = rt.store();
      if (store.hotfix(in.id()).map(h -> h.state() == HotfixState.ROLLED_BACK).orElse(false)) {
        store.updateHotfixState(in.id(), HotfixState.INSTALLED);
      }
      return StepResult.ok();
    }
  }
}
