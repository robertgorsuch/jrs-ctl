package com.jaspersoft.jrsctl.ops.upgrade;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.Durability;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.MasterProperties;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase C of spec §10.2: stage {@code default_master.properties} into the target package's
 * buildomatic directory (spec §7.4) and run the vendor upgrade there. Invariants: the staged
 * properties never contain a password (spec §7.4, Q5); the vendor script runs with {@code
 * JAVA_HOME=vendor.javaHome}, its output streamed as redacted log events; the compensation of the
 * vendor run is the point-B restore of spec §10.1. Two markers in the run directory keep the script
 * from running twice: an attempt marker written and forced to disk <em>before</em> the script is
 * launched, and a done marker written after it reports success. A resume that finds the attempt
 * marker without the done marker cannot know whether the vendor script already changed the
 * repository database ({@code js-upgrade-samedb} migrates it in place, {@code js-upgrade-newdb}
 * drops and recreates it from the point-B full export; ADR-0012), which jrsctl cannot undo (spec
 * §10.1), so it refuses rather than guess. {@code js-upgrade-newdb} is always given the point-B
 * full export as its argument: the vendor wrapper refuses to run without one.
 */
final class VendorSteps {

  static final String WRITE_MASTER_PROPERTIES = "write-master-properties";
  static final String RUN_VENDOR_UPGRADE = "run-vendor-upgrade";
  static final String STOP_SERVICE = "stop-service";
  static final String START_SERVICE = "start-service";
  static final String WAIT_FOR_SERVER = "wait-for-server";
  static final String DONE_MARKER = RUN_VENDOR_UPGRADE + ".done";
  static final String ATTEMPT_MARKER = RUN_VENDOR_UPGRADE + ".attempted";
  static final String APP_SERVER_DIR = "appServerDir";
  static final String APP_SERVER_TYPE = "appServerType";
  static final String TOMCAT = "tomcat";

  /** Vendor script names of spec §10.2 step 10, tried first when the package ships them. */
  static final String SCRIPT_PREFIX = "js-upgrade-";

  /**
   * Ant target the vendor wrapper itself selects ({@code bin/do-js-upgrade}: {@code
   * upgrade-minimal-<ce|pro>} with {@code -Dstrategy=standard|inDatabase}), used through {@code
   * js-ant} when a package ships no wrapper script.
   */
  static final String ANT_TARGET_PREFIX = "upgrade-minimal-";

  /** The {@code -Dstrategy} value {@code do-js-upgrade} passes for each mode. */
  static final String STRATEGY_NEWDB = "standard";

  static final String STRATEGY_SAMEDB = "inDatabase";

  private VendorSteps() {}

  static Map<String, String> masterOverrides(Map<String, String> installed, Path tomcatDir) {
    Map<String, String> overrides = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : installed.entrySet()) {
      if (!MasterProperties.isPasswordKey(e.getKey())) {
        overrides.put(e.getKey(), e.getValue());
      }
    }
    overrides.put(APP_SERVER_TYPE, TOMCAT);
    overrides.put(APP_SERVER_DIR, tomcatDir.toAbsolutePath().normalize().toString());
    return Map.copyOf(overrides);
  }

  static final class WriteMasterProperties implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    WriteMasterProperties(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private Path buildomaticDir() {
      return in.targetBuildomatic().orElse(in.target().dir().resolve(UpgradeInput.BUILDOMATIC));
    }

    @Override
    public String id() {
      return WRITE_MASTER_PROPERTIES;
    }

    @Override
    public String title() {
      return "write default_master.properties into the target buildomatic";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES)
          + " ("
          + in.masterOverrides().size()
          + " keys, no passwords; existing file snapshotted)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isDirectory(buildomaticDir())) {
        return CheckResult.fail(
            "target buildomatic " + buildomaticDir() + " does not exist",
            "point --package at the unpacked distribution");
      }
      if (!rt.files().isWritable(buildomaticDir())) {
        return CheckResult.fail(
            buildomaticDir() + " is not writable",
            "grant jrsctl write access to the target package");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path runDir = ctx.home().runDir(ctx.runId());
      try {
        MasterProperties.Staged staged =
            MasterProperties.stage(buildomaticDir(), in.masterOverrides(), runDir);
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "wrote "
                + staged.file()
                + staged.backup().map(b -> " (previous copy kept at " + b + ")").orElse(""));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write "
                + buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES)
                + ": "
                + e.getMessage(),
            "check permissions on the target package");
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      Path file = buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES);
      return Files.isRegularFile(file)
          ? CheckResult.pass()
          : CheckResult.fail(file + " was not written", "run again");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        MasterProperties.restore(buildomaticDir(), ctx.home().runDir(ctx.runId()));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot restore "
                + buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES)
                + ": "
                + e.getMessage(),
            "restore the file by hand from "
                + MasterProperties.backupFor(ctx.home().runDir(ctx.runId())));
      }
    }
  }

  static final class RunVendorUpgrade implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    RunVendorUpgrade(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    String scriptName() {
      return SCRIPT_PREFIX + in.options().mode().vendorSuffix();
    }

    String antTarget() {
      return ANT_TARGET_PREFIX + edition();
    }

    /** {@code ce} or {@code pro}: from the server identity, else from the webapp name. */
    String edition() {
      ServerIdentity.Edition edition =
          in.identity().map(ServerIdentity::edition).orElse(ServerIdentity.Edition.UNKNOWN);
      return switch (edition) {
        case CE -> "ce";
        case PRO -> "pro";
        case UNKNOWN -> in.webappName().endsWith("-pro") ? "pro" : "ce";
      };
    }

    String strategy() {
      return switch (in.options().mode()) {
        case NEWDB -> STRATEGY_NEWDB;
        case SAMEDB -> STRATEGY_SAMEDB;
      };
    }

    /** The point-B full export, the one argument {@code js-upgrade-newdb} requires (ADR-0012). */
    private Path fullExport(Context ctx) {
      return in.snapshots(ctx).fullExport().toAbsolutePath().normalize();
    }

    /** Arguments for the vendor wrapper: the full export for newdb, nothing for samedb. */
    List<String> wrapperArgs(Context ctx) {
      return switch (in.options().mode()) {
        case NEWDB -> List.of(fullExport(ctx).toString());
        case SAMEDB -> List.of();
      };
    }

    /** What the wrapper would pass to {@code js-ant}, for a package that ships no wrapper. */
    List<String> antArgs(Context ctx) {
      return switch (in.options().mode()) {
        case NEWDB -> List.of("-Dstrategy=" + STRATEGY_NEWDB, "-DimportFile=" + fullExport(ctx));
        case SAMEDB -> List.of("-Dstrategy=" + STRATEGY_SAMEDB);
      };
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(DONE_MARKER);
    }

    private Path attemptMarker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(ATTEMPT_MARKER);
    }

    @Override
    public String id() {
      return RUN_VENDOR_UPGRADE;
    }

    @Override
    public String title() {
      return "run the vendor upgrade (" + scriptName() + ")";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      boolean newdb = in.options().mode() == UpgradeOperations.Mode.NEWDB;
      return scriptName()
          + (newdb ? " <point-B full export>" : "")
          + " if shipped, else js-ant "
          + antTarget()
          + " -Dstrategy="
          + strategy()
          + (newdb ? " -DimportFile=<point-B full export>" : "")
          + "; JAVA_HOME="
          + rt.config().vendor().javaHome().map(Path::toString).orElse("<unset>")
          + "; timeout "
          + rt.tools().timeout().toMinutes()
          + "m";
    }

    /*
     * Not irreversible: the vendor script cannot be undone in place, but spec §10.1 defines its
     * undo as the restore of rollback point B, and that is exactly what compensate() performs.
     * The repository database change stays in both modes (samedb migrates it, newdb drops and
     * recreates it; ADR-0012); the plan summary says so in plain text and the run does not start
     * without --db-backup-confirmed.
     */
    @Override
    public boolean irreversible() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.targetBuildomatic().isEmpty()) {
        return CheckResult.fail(
            "no buildomatic directory in " + in.target().dir(), "check the target package");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set", "set vendor.javaHome to the JDK the target needs");
      }
      if (in.options().mode() == UpgradeOperations.Mode.NEWDB
          && !Files.isRegularFile(fullExport(ctx))) {
        return CheckResult.fail(
            "the point-B full export "
                + fullExport(ctx)
                + " is missing; js-upgrade-newdb rebuilds the repository database from it",
            "resume the run so the backup phase writes it, or start the upgrade again");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "vendor upgrade already completed in this run; skipping");
        return StepResult.ok();
      }
      if (Files.isRegularFile(attemptMarker(ctx))) {
        return interrupted(ctx);
      }
      try {
        recordAttempt(ctx);
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write " + attemptMarker(ctx) + ": " + e.getMessage(),
            "free space in the run directory and re-run; without this marker a crash during the"
                + " vendor upgrade cannot be told apart from one before it");
      }
      Buildomatic b = in.target().buildomatic().orElseThrow();
      Optional<Path> javaHome = rt.config().vendor().javaHome();
      String ext = rt.locator().scriptExtension();
      Path wrapper = b.dir().resolve(scriptName() + ext);
      VendorRun run;
      if (Files.isRegularFile(wrapper)) {
        Map<String, Path> scripts = new LinkedHashMap<>(b.scripts());
        scripts.put(scriptName(), wrapper);
        Buildomatic withWrapper =
            new Buildomatic(b.dir(), scripts, b.masterPropertiesFile(), b.masterProperties());
        run =
            rt.tools()
                .run(
                    new VendorTools.Invocation(
                        withWrapper, scriptName(), wrapperArgs(ctx), javaHome),
                    out,
                    Logs.scope(ctx, this));
      } else {
        List<String> antArgs = antArgs(ctx);
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "no "
                + wrapper.getFileName()
                + " in the package; using js-ant "
                + antTarget()
                + " "
                + String.join(" ", antArgs));
        run = rt.tools().ant(b, antTarget(), antArgs, javaHome, out, Logs.scope(ctx, this));
      }
      return switch (run) {
        case VendorRun.Completed c -> c.ok() ? done(ctx, out) : failed(ctx, c);
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "vendor upgrade did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process; the run is rolled back to point B",
                List.of(in.webappDir()),
                List.of(in.snapshots(ctx).dir()));
        case VendorRun.NotStarted n -> notStarted(ctx, out, n);
      };
    }

    /**
     * Writes and forces the marker that says the script is about to run. Forced, because the whole
     * point is to survive the power cut that a resume has to reason about.
     */
    private void recordAttempt(Context ctx) throws IOException {
      Path file = attemptMarker(ctx);
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          scriptName() + " started at " + rt.clock().instant() + System.lineSeparator(),
          UTF_8);
      Durability.sync(file);
      Durability.syncDirectory(file.getParent());
    }

    /**
     * Refuses a resume that cannot tell whether the database change ran. {@code samedb} rewrites
     * the repository schema in place and {@code newdb} drops and recreates the database; neither is
     * idempotent, so the operator has to look at the buildomatic log and say which side of the
     * crash they are on.
     */
    private StepResult interrupted(Context ctx) {
      return Failures.recoverable(
          scriptName()
              + " was started in run "
              + ctx.runId()
              + " and never reported back; whether the repository database was already changed"
              + " (migrated by samedb, dropped and recreated by newdb) is unknown",
          "read the buildomatic log under "
              + in.target().dir()
              + "; if the upgrade did not run, delete "
              + attemptMarker(ctx)
              + " and resume; if it did, roll back with jrsctl upgrade rollback "
              + ctx.runId()
              + " --to-point B",
          List.of(in.webappDir()),
          List.of(in.snapshots(ctx).dir()));
    }

    /** The launch itself failed, so nothing ran and the attempt marker must not outlive it. */
    private StepResult notStarted(Context ctx, EventSink out, VendorRun.NotStarted n) {
      try {
        Files.deleteIfExists(attemptMarker(ctx));
      } catch (IOException e) {
        Logs.warn(
            rt, ctx, out, this, "cannot remove " + attemptMarker(ctx) + ": " + e.getMessage());
      }
      return Failures.recoverable(n.reason(), n.remediation());
    }

    private StepResult done(Context ctx, EventSink out) {
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "done", UTF_8);
      } catch (IOException e) {
        Logs.warn(rt, ctx, out, this, "cannot write " + marker(ctx) + ": " + e.getMessage());
      }
      Logs.info(rt, ctx, out, this, "vendor upgrade finished");
      return StepResult.ok();
    }

    private StepResult failed(Context ctx, VendorRun.Completed c) {
      return Failures.recoverable(
          "vendor upgrade exited with " + c.exitCode() + ": " + String.join(" | ", c.tail()),
          "read the buildomatic log; the run is rolled back to point B",
          List.of(in.webappDir()),
          List.of(in.snapshots(ctx).dir()));
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      SnapshotSet set = in.snapshots(ctx);
      Path aside = ctx.home().runDir(ctx.runId()).resolve(PointB.ASIDE_DIR);
      List<String> problems = new ArrayList<>();
      try {
        if (Files.isRegularFile(set.webappArchive())) {
          PointB.verifyArchive(set.webappArchive(), rt.files().sha256(set.webappArchive()));
          PointB.restoreDir(
              set.os(), set.webappArchive(), in.webappDir(), aside.resolve("webapp"), ctx.cancel());
          Logs.info(rt, ctx, out, this, "webapp restored from " + set.webappArchive());
        } else {
          problems.add("no webapp archive at " + set.webappArchive());
        }
      } catch (IOException e) {
        problems.add("webapp: " + e.getMessage());
      }
      try {
        if (Files.isRegularFile(set.buildomaticArchive())) {
          PointB.verifyArchive(
              set.buildomaticArchive(), rt.files().sha256(set.buildomaticArchive()));
          PointB.restoreDir(
              set.os(),
              set.buildomaticArchive(),
              in.installedBuildomatic(),
              aside.resolve("buildomatic"),
              ctx.cancel());
          Logs.info(rt, ctx, out, this, "buildomatic restored from " + set.buildomaticArchive());
        }
      } catch (IOException e) {
        problems.add("buildomatic: " + e.getMessage());
      }
      for (String stepId : List.of(SnapshotSet.CONFIG_STEP, SnapshotSet.KEYSTORE_STEP)) {
        try {
          Optional<Snapshot> snapshot = rt.snapshots().find(ctx.runId(), stepId);
          if (snapshot.isPresent()) {
            rt.snapshots().restore(snapshot.get());
            Logs.info(rt, ctx, out, this, stepId + " restored from " + snapshot.get().dir());
          }
        } catch (IOException | RuntimeException e) {
          problems.add(stepId + ": " + Failures.describe(e));
        }
      }
      for (Path file : List.of(marker(ctx), attemptMarker(ctx))) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          problems.add(file.getFileName() + ": " + e.getMessage());
        }
      }
      if (!problems.isEmpty()) {
        return Failures.recoverable(
            "point B restore incomplete: " + String.join("; ", problems),
            "restore by hand from "
                + set.dir()
                + " or run jrsctl upgrade rollback "
                + ctx.runId()
                + " --to-point B",
            List.of(in.webappDir(), in.installedBuildomatic()),
            List.of(set.dir()));
      }
      return StepResult.ok();
    }
  }
}
