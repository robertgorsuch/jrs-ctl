package com.jaspersoft.jrsctl.ops.passwords;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.state.AuditActor;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Steps;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsctl.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsctl.ops.doctor.DoctorReport;
import com.jaspersoft.jrsctl.ops.doctor.JavaVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The steps of {@code passwords migrate} (spec §10.6, ADR-0053; installation guide 10.1
 * pp.194-199). Invariants: the preflight steps mutate nothing and report every refusal from {@code
 * precheck}, so the run ends with exit 2 and nothing changed; {@code migrate-passwords} and {@code
 * cleanup-password-migration} are {@code irreversible()} with the guide's justification (a database
 * restore is the only way back; the cleanup removes tracking rows only) and idempotent through a
 * done marker in the run directory, the vendor utility itself skipping users already migrated; the
 * vendor tools run with {@code vendor.javaHome} and their output is streamed as redacted log events
 * and read back for the counts the guide names.
 */
final class PasswordSteps {

  static final String DOCTOR = "doctor";
  static final String VERIFY_TOOLS = "verify-password-tools";
  static final String CONFIRM_DB_BACKUP = "confirm-db-backup";
  static final String DRY_RUN = "migrate-passwords-dry-run";
  static final String MIGRATE = "migrate-passwords";
  static final String DETECT = "detect-password-strategy";
  static final String CLEANUP = "cleanup-password-migration";

  static final String AUDIT_DB_BACKUP_CONFIRMED = "passwords.db-backup-confirmed";
  static final String AUDIT_MIGRATED = "passwords.migrated";

  /** Installation guide 10.1 p.195, in the plan's own words; the CLI prints the same sentence. */
  static final String DB_BACKUP_GATE =
      "passwords migrate converts the stored passwords to the modern format permanently; the only"
          + " way back is a database restore (installation guide 10.1 p.195), so back up the"
          + " repository database yourself and pass --db-backup-confirmed";

  /** Doctor items that, inside a run, report the run's own pending row and lock. */
  static final Set<String> SELF_REFERENTIAL = Set.of("runs", "lock");

  private PasswordSteps() {}

  // ---------------------------------------------------------------- bases

  abstract static class Base implements Step {
    final PasswordRuntime rt;
    final PasswordInput in;

    Base(PasswordRuntime rt, PasswordInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    void info(Context ctx, EventSink out, String message) {
      Steps.info(rt.services(), ctx, out, this, message);
    }

    void warn(Context ctx, EventSink out, String message) {
      Steps.warn(rt.services(), ctx, out, this, message);
    }

    /** The checks every vendor step shares: js-ant and the Java it runs with. */
    CheckResult tools() {
      if (in.buildomatic().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "no js-ant in " + in.buildomatic().dir(),
            "restore the installed buildomatic; the password utility is its js-ant target");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; js-ant needs a JDK",
            "set vendor.javaHome in config.yaml (jrsctl config set vendor.javaHome <dir>)");
      }
      return CheckResult.pass();
    }

    /**
     * Runs one js-ant target, collecting the lines the utility printed for this step; returns the
     * failure to report, or empty when the target succeeded.
     */
    Optional<StepResult> ant(Context ctx, EventSink out, String target, List<String> lines) {
      EventSink collecting =
          event -> {
            out.emit(event);
            if (event instanceof Event.Log log && log.stepId().equals(Optional.of(id()))) {
              lines.add(log.message());
            }
          };
      VendorRun run =
          rt.tools()
              .ant(
                  in.buildomatic(),
                  target,
                  List.of(),
                  rt.config().vendor().javaHome(),
                  collecting,
                  Steps.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            c.ok()
                ? Optional.empty()
                : Optional.of(
                    Steps.recoverable(
                        "js-ant "
                            + target
                            + " "
                            + c.summary()
                            + ": "
                            + String.join(" | ", c.tail()),
                        "read the buildomatic log under "
                            + in.buildomatic().dir()
                            + "; js-ant "
                            + DETECT
                            + " shows what is left, and the migration can be run again (users"
                            + " already migrated are skipped)"));
        case VendorRun.TimedOut t ->
            Optional.of(
                Steps.recoverable(
                    "js-ant "
                        + target
                        + " did not finish within "
                        + t.timeout().toMinutes()
                        + " minutes",
                    "check for a hung buildomatic process, then run again"));
        case VendorRun.NotStarted n -> Optional.of(Steps.recoverable(n.reason(), n.remediation()));
      };
    }
  }

  /** Read-only step skeleton: nothing to compensate. */
  abstract static class ReadOnly extends Base {
    ReadOnly(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
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

  /** A vendor target that cannot be undone, run once per run. */
  abstract static class Irreversible extends Base {
    Irreversible(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    Path done(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(id() + ".done");
    }

    @Override
    public boolean irreversible() {
      return true;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return tools();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      // nothing to put back; see irreversible() on each step
      return StepResult.ok();
    }

    StepResult markDone(Context ctx, EventSink out, String message) {
      try {
        Files.createDirectories(done(ctx).getParent());
        Files.writeString(done(ctx), "done", StandardCharsets.UTF_8);
      } catch (IOException e) {
        warn(ctx, out, "cannot write " + done(ctx) + ": " + e.getMessage());
      }
      info(ctx, out, message);
      return StepResult.ok();
    }
  }

  // ---------------------------------------------------------------- preflight

  /** The doctor, strict: the server is up and the installation is what it says. */
  static final class Doctor extends ReadOnly {
    private Optional<DoctorReport> last = Optional.empty();

    Doctor(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DOCTOR;
    }

    @Override
    public String title() {
      return "run doctor against the installation";
    }

    @Override
    public String phase() {
      return PasswordPhases.PREFLIGHT;
    }

    @Override
    public String detail() {
      return "every check must pass; the run's own pending row and lock are not counted";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      DoctorReport report;
      try {
        report = new DoctorOperation(rt.services()).run(DoctorOptions.DEFAULT);
      } catch (RuntimeException e) {
        return CheckResult.fail(
            "doctor could not run: " + Steps.describe(e), "run jrsctl doctor and fix it first");
      }
      last = Optional.of(report);
      List<String> failing = new ArrayList<>();
      for (ReportItem item : report.items()) {
        boolean aboutThisRun =
            SELF_REFERENTIAL.contains(item.name()) && item.detail().contains(ctx.runId());
        if (item.status() == ReportItem.Status.FAIL && !aboutThisRun) {
          failing.add(item.name() + ": " + item.detail());
        }
      }
      if (!failing.isEmpty()) {
        return CheckResult.fail(
            "doctor reports " + failing.size() + " failing check(s): " + String.join("; ", failing),
            "run jrsctl doctor and fix every FAIL before migrating");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      DoctorReport report =
          last.orElseGet(() -> new DoctorOperation(rt.services()).run(DoctorOptions.DEFAULT));
      for (ReportItem item : report.items()) {
        Event.Log.Level level =
            switch (item.status()) {
              case PASS, SKIP -> Event.Log.Level.INFO;
              case WARN, FAIL -> Event.Log.Level.WARN;
            };
        Steps.emit(
            rt.services(),
            ctx,
            out,
            this,
            level,
            "doctor " + item.name() + " " + item.status() + ": " + item.detail());
      }
      return StepResult.ok();
    }
  }

  /** The vendor's utility, its Java, and the file it reads its settings from. */
  static final class VerifyTools extends ReadOnly {
    VerifyTools(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return VERIFY_TOOLS;
    }

    @Override
    public String title() {
      return "verify the password utility of " + in.buildomatic().dir();
    }

    @Override
    public String phase() {
      return PasswordPhases.PREFLIGHT;
    }

    @Override
    public String detail() {
      return "js-ant and vendor.javaHome run; "
          + in.storageConfig()
          + " exists (the utility reads its settings there); server "
          + in.serverVersion();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      CheckResult tools = tools();
      if (tools instanceof CheckResult.Fail) {
        return tools;
      }
      Path javaHome = rt.config().vendor().javaHome().orElseThrow();
      JavaVersion.Probe probe = JavaVersion.probe(rt.services().platform().processes(), javaHome);
      if (probe.feature().isEmpty()) {
        return CheckResult.fail(probe.detail(), "point vendor.javaHome at a working JDK");
      }
      if (!Files.isRegularFile(in.storageConfig())) {
        return CheckResult.fail(
            "no "
                + in.storageConfig()
                + "; the migration utility reads its settings from that file (installation guide"
                + " 10.1 p.196)",
            "the deployed webapp must be 10.1 or later and ship " + PasswordInput.STORAGE_CONFIG);
      }
      Optional<String> strategy = PasswordInput.strategy(in.storageConfig());
      if (strategy.isEmpty() || !strategy.get().equals(PasswordInput.MODERN)) {
        return CheckResult.warn(
            in.storageConfig()
                + " has "
                + PasswordInput.STRATEGY_KEY
                + "="
                + strategy.orElse("(unset)")
                + ": the utility re-hashes with the configured algorithm, and new logins keep the"
                + " legacy format until the server is configured for modern storage and"
                + " restarted (installation guide 10.1 p.194)");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      info(
          ctx,
          out,
          "password utility verified: "
              + in.storageConfig()
              + " ("
              + PasswordInput.STRATEGY_KEY
              + "="
              + PasswordInput.strategy(in.storageConfig()).orElse("(unset)")
              + ")");
      return StepResult.ok();
    }
  }

  /** The gate of installation guide 10.1 p.195: a database backup exists, says the operator. */
  static final class ConfirmDbBackup extends ReadOnly {
    ConfirmDbBackup(PasswordRuntime rt, PasswordInput in) {
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
    public String phase() {
      return PasswordPhases.PREFLIGHT;
    }

    @Override
    public String detail() {
      return "requires --db-backup-confirmed (audited); a database restore is the only way back";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!in.options().dbBackupConfirmed()) {
        return CheckResult.fail(DB_BACKUP_GATE, "re-run with --db-backup-confirmed");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      rt.store()
          .audit(
              AuditActor.current(),
              AUDIT_DB_BACKUP_CONFIRMED,
              "password migration in run "
                  + ctx.runId()
                  + "; the operator confirmed a database backup exists");
      info(ctx, out, "database backup confirmed by the operator (audited)");
      return StepResult.ok();
    }
  }

  /** {@code js-ant migrate-passwords-dry-run}: the baseline, nothing changed. */
  static final class DryRun extends ReadOnly {
    DryRun(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DRY_RUN;
    }

    @Override
    public String title() {
      return "count the users to migrate (js-ant " + DRY_RUN + ")";
    }

    @Override
    public String phase() {
      return PasswordPhases.PREFLIGHT;
    }

    @Override
    public String detail() {
      return "the vendor's dry run: database type, total users, users still on legacy passwords;"
          + " changes nothing";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return tools();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<String> lines = new ArrayList<>();
      Optional<StepResult> failure = ant(ctx, out, DRY_RUN, lines);
      if (failure.isPresent()) {
        return failure.get();
      }
      info(
          ctx,
          out,
          PasswordCounts.parse(lines)
              .map(c -> "before the migration: " + c.describe())
              .orElse("dry run finished; the utility printed no counts"));
      return StepResult.ok();
    }
  }

  // ---------------------------------------------------------------- migrate

  /** {@code js-ant migrate-passwords}: permanent, by the vendor's own account. */
  static final class Migrate extends Irreversible {
    Migrate(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return MIGRATE;
    }

    @Override
    public String title() {
      return "migrate the stored passwords to the modern format (js-ant " + MIGRATE + ")";
    }

    @Override
    public String phase() {
      return PasswordPhases.MIGRATE;
    }

    @Override
    public String detail() {
      return "every internal user still on a legacy password is re-hashed in batches; anonymousUser"
          + " and externally authenticated users are skipped; cannot be undone except by a"
          + " database restore";
    }

    // installation guide 10.1 p.195: "the only way to undo this migration and return to the old
    // format is to restore your database from a backup", which --db-backup-confirmed vouched for
    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(done(ctx))) {
        info(ctx, out, "passwords already migrated in this run; skipping");
        return StepResult.ok();
      }
      List<String> lines = new ArrayList<>();
      Optional<StepResult> failure = ant(ctx, out, MIGRATE, lines);
      if (failure.isPresent()) {
        return failure.get();
      }
      Optional<PasswordCounts> counts = PasswordCounts.parse(lines);
      rt.store()
          .audit(
              AuditActor.current(),
              AUDIT_MIGRATED,
              counts.map(PasswordCounts::describe).orElse("counts not printed")
                  + " ("
                  + ctx.runId()
                  + ")");
      return markDone(
          ctx,
          out,
          counts
              .map(c -> "stored passwords migrated; after the migration: " + c.describe())
              .orElse("stored passwords migrated; the utility printed no counts"));
    }
  }

  // ---------------------------------------------------------------- verify

  /** {@code js-ant detect-password-strategy}: read-only, the guide's completion rule. */
  static final class Detect extends ReadOnly {
    Detect(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DETECT;
    }

    @Override
    public String title() {
      return "verify the distribution of passwords (js-ant " + DETECT + ")";
    }

    @Override
    public String phase() {
      return PasswordPhases.VERIFY;
    }

    @Override
    public String detail() {
      return "complete when at most one legacy password (anonymousUser) remains; more is a warning,"
          + " since externally authenticated users are skipped by design";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return tools();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<String> lines = new ArrayList<>();
      Optional<StepResult> failure = ant(ctx, out, DETECT, lines);
      if (failure.isPresent()) {
        return failure.get();
      }
      Optional<PasswordCounts> counts = PasswordCounts.parse(lines);
      if (counts.isEmpty()) {
        warn(ctx, out, "the utility printed no counts; read its output above");
      } else if (counts.get().complete()) {
        info(ctx, out, "migration complete: " + counts.get().describe());
      } else {
        warn(
            ctx,
            out,
            "migration not complete: "
                + counts.get().describe()
                + "; externally authenticated users are skipped by design, otherwise run"
                + " passwords migrate again");
      }
      return StepResult.ok();
    }
  }

  // ---------------------------------------------------------------- cleanup

  /** {@code js-ant cleanup-password-migration}: tracking rows only, on request. */
  static final class Cleanup extends Irreversible {
    Cleanup(PasswordRuntime rt, PasswordInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return CLEANUP;
    }

    @Override
    public String title() {
      return "remove completed rows from the migration tracking table (js-ant " + CLEANUP + ")";
    }

    @Override
    public String phase() {
      return PasswordPhases.CLEANUP;
    }

    @Override
    public String detail() {
      return "JIPasswordMigration rows with a completed status; in-progress, partial and failed rows"
          + " stay; no password changes";
    }

    // installation guide 10.1 p.197: the cleanup deletes completed tracking rows and nothing else;
    // there is nothing to put back that the migration itself did not already make permanent
    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(done(ctx))) {
        info(ctx, out, "tracking table already cleaned in this run; skipping");
        return StepResult.ok();
      }
      List<String> lines = new ArrayList<>();
      Optional<StepResult> failure = ant(ctx, out, CLEANUP, lines);
      if (failure.isPresent()) {
        return failure.get();
      }
      return markDone(ctx, out, "completed migration tracking rows removed");
    }
  }
}
