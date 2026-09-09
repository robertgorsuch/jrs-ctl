package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.compat.UnsupportedVersionException;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.jrs.vendor.VendorJava;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsctl.ops.doctor.DoctorReport;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase A of spec §10.2: doctor, target package verification and the {@code samedb} database backup
 * gate. Invariants: none of these steps mutates anything ({@code mutating() == false}); a failing
 * condition is reported from {@code precheck}, so the run ends with exit code 2 and the "nothing
 * changed" outcome; {@code execute} only logs what was verified.
 */
final class PreflightSteps {

  static final String DOCTOR = "doctor";
  static final String VERIFY_TARGET_PACKAGE = "verify-target-package";
  static final String CONFIRM_DB_BACKUP = "confirm-db-backup";
  static final String AUDIT_DB_BACKUP_CONFIRMED = "upgrade.db-backup-confirmed";

  /** Gate message of spec §10.1; the CLI prints the same sentence when the flag is missing. */
  static final String DB_BACKUP_GATE =
      "--mode samedb migrates the repository database in place and jrsctl cannot undo that;"
          + " back up the database yourself and pass --db-backup-confirmed";

  /**
   * Doctor items whose FAIL is judged against the current server, not the target: the compat and
   * vendor-java checks are repeated against the target version by verify-target-package.
   */
  static final Set<String> JUDGED_AGAINST_TARGET = Set.of(DoctorReport.COMPAT, "vendor-java");

  /** Doctor items that, inside a run, report the run's own pending row and lock. */
  static final Set<String> SELF_REFERENTIAL = Set.of("runs", "lock");

  private PreflightSteps() {}

  /** Read-only step skeleton: nothing to compensate. */
  abstract static class ReadOnly implements Step {
    final UpgradeRuntime rt;
    final UpgradeInput in;

    ReadOnly(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String phase() {
      return Phases.PREFLIGHT;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  static final class Doctor extends ReadOnly {
    private Optional<DoctorReport> last = Optional.empty();

    Doctor(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DOCTOR;
    }

    @Override
    public String title() {
      return "run doctor against the current server";
    }

    @Override
    public String detail() {
      return "every check must pass; compat and vendor-java are re-judged against the target";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      DoctorReport report;
      try {
        report = new DoctorOperation(rt.services()).run(DoctorOptions.DEFAULT);
      } catch (RuntimeException e) {
        return CheckResult.fail(
            "doctor could not run: " + Failures.describe(e), "run jrsctl doctor and fix it first");
      }
      last = Optional.of(report);
      List<String> failing = new ArrayList<>();
      for (ReportItem item : report.items()) {
        if (item.status() == ReportItem.Status.FAIL
            && !JUDGED_AGAINST_TARGET.contains(item.name())
            && !aboutThisRun(item, ctx)) {
          failing.add(item.name() + ": " + item.detail());
        }
      }
      if (!failing.isEmpty()) {
        return CheckResult.fail(
            "doctor reports " + failing.size() + " failing check(s): " + String.join("; ", failing),
            "run jrsctl doctor and fix every FAIL before upgrading");
      }
      return CheckResult.pass();
    }

    /**
     * The doctor runs inside this very run: its "runs" and "lock" checks see the current run as
     * pending and the lock as held by it. Those two findings are about us, not about the host.
     */
    private static boolean aboutThisRun(ReportItem item, Context ctx) {
      return SELF_REFERENTIAL.contains(item.name()) && item.detail().contains(ctx.runId());
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      DoctorReport report =
          last.orElseGet(() -> new DoctorOperation(rt.services()).run(DoctorOptions.DEFAULT));
      for (ReportItem item : report.items()) {
        Event.Log.Level level =
            switch (item.status()) {
              case PASS, SKIP -> Event.Log.Level.INFO;
              case WARN -> Event.Log.Level.WARN;
              case FAIL -> Event.Log.Level.WARN;
            };
        String suffix =
            item.status() == ReportItem.Status.FAIL
                ? " (judged against the target by " + VERIFY_TARGET_PACKAGE + ")"
                : "";
        Logs.emit(
            rt,
            ctx,
            out,
            this,
            level,
            item.status() + " " + item.name() + ": " + item.detail() + suffix);
      }
      Logs.info(rt, ctx, out, this, "doctor: " + report.counts().summary());
      return StepResult.ok();
    }
  }

  static final class VerifyTargetPackage extends ReadOnly {

    VerifyTargetPackage(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return VERIFY_TARGET_PACKAGE;
    }

    @Override
    public String title() {
      return "verify the target package for " + in.options().toVersion();
    }

    @Override
    public String detail() {
      return in.target().dir()
          + ": vendor scripts, webapp, upgrade path in the compat matrix, vendor.javaHome";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems = in.target().problems(rt.locator().scriptExtension());
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            "target package is not usable: " + String.join("; ", problems),
            "point --package at the unpacked JasperReports Server distribution for this OS");
      }
      String to = in.options().toVersion();
      Optional<String> discovered = in.target().discoveredVersion();
      if (discovered.isPresent() && !discovered.get().equals(to)) {
        return CheckResult.fail(
            "the package names version " + discovered.get() + " but --to says " + to,
            "pass --to " + discovered.get() + " or point --package at the right distribution");
      }
      String current;
      try {
        current = rt.identity().version();
      } catch (JrsUnreachableException | RestException | ConfigException e) {
        return CheckResult.fail(
            "server unreachable, current version unknown: " + e.getMessage(),
            "start the server; the upgrade path is checked against its reported version");
      }
      if (!rt.services().matrix().upgradePathSupported(current, to)) {
        return CheckResult.fail(
            "upgrade path " + current + " -> " + to + " is not in the compatibility matrix",
            "choose a supported target version; see the compat matrix in the operator guide");
      }
      int required;
      try {
        required = rt.services().matrix().javaRequiredFor(to);
      } catch (UnsupportedVersionException e) {
        return CheckResult.fail(
            "no compatibility matrix entry for " + to, "choose a supported target version");
      }
      Optional<Path> javaHome = rt.config().vendor().javaHome();
      if (javaHome.isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; the vendor upgrade scripts need a Java "
                + required
                + " JDK",
            "set vendor.javaHome in config.yaml to a Java " + required + " JDK");
      }
      if (!Files.isDirectory(javaHome.get())) {
        return CheckResult.fail(
            "vendor.javaHome " + javaHome.get() + " is not a directory",
            "point vendor.javaHome at an installed Java " + required + " JDK");
      }
      Optional<Integer> found;
      try {
        found = VendorJava.detect(javaHome.get(), rt.services().platform().processes());
      } catch (RuntimeException e) {
        found = Optional.empty();
      }
      if (found.isEmpty()) {
        return CheckResult.fail(
            "cannot determine the Java version of vendor.javaHome " + javaHome.get(),
            "point vendor.javaHome at a working Java " + required + " JDK");
      }
      if (found.get() != required) {
        return CheckResult.fail(
            "vendor.javaHome "
                + javaHome.get()
                + " is Java "
                + found.get()
                + "; JasperReports Server "
                + to
                + " needs Java "
                + required,
            "set vendor.javaHome to a Java " + required + " JDK");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      TargetPackage t = in.target();
      Logs.info(
          rt,
          ctx,
          out,
          this,
          "target "
              + t.dir()
              + ": buildomatic "
              + t.buildomatic().map(b -> b.dir().toString()).orElse("-")
              + ", webapp "
              + t.webappDir()
                  .map(Path::toString)
                  .or(() -> t.warFile().map(Path::toString))
                  .orElse("-")
              + ", version "
              + t.discoveredVersion().orElse(in.options().toVersion() + " (from --to)"));
      return StepResult.ok();
    }
  }

  static final class ConfirmDbBackup extends ReadOnly {

    ConfirmDbBackup(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return CONFIRM_DB_BACKUP;
    }

    @Override
    public String title() {
      return "confirm the operator backed up the repository database";
    }

    @Override
    public String detail() {
      return "samedb only: requires --db-backup-confirmed (audited)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.options().mode() != Mode.SAMEDB) {
        return CheckResult.pass();
      }
      if (!in.options().dbBackupConfirmed()) {
        return CheckResult.fail(DB_BACKUP_GATE, "re-run with --db-backup-confirmed");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      rt.store()
          .audit(
              rt.actor(),
              AUDIT_DB_BACKUP_CONFIRMED,
              "samedb upgrade to "
                  + in.options().toVersion()
                  + " in run "
                  + ctx.runId()
                  + "; the operator confirmed a database backup exists");
      Logs.info(rt, ctx, out, this, "database backup confirmed by the operator (audited)");
      return StepResult.ok();
    }
  }

  static ServerIdentity targetIdentity(ServerIdentity current, String toVersion) {
    return new ServerIdentity(
        current.baseUrl(),
        toVersion,
        current.edition(),
        current.tenancy(),
        current.features(),
        current.build(),
        current.dateFormat());
  }
}
