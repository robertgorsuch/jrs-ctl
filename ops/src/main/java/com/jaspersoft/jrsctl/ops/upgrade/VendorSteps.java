package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.engine.StepResult;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.snapshot.Snapshot;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.MasterProperties;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * JAVA_HOME=vendor.javaHome}, its output streamed as redacted log events; a successful vendor run
 * leaves a marker in the run directory so that re-execution after a crash does not run the script
 * twice; the compensation of the vendor run is the point-B restore of spec §10.1.
 */
final class VendorSteps {

  static final String WRITE_MASTER_PROPERTIES = "write-master-properties";
  static final String RUN_VENDOR_UPGRADE = "run-vendor-upgrade";
  static final String STOP_SERVICE = "stop-service";
  static final String START_SERVICE = "start-service";
  static final String WAIT_FOR_SERVER = "wait-for-server";
  static final String DONE_MARKER = RUN_VENDOR_UPGRADE + ".done";
  static final String APP_SERVER_DIR = "appServerDir";
  static final String APP_SERVER_TYPE = "appServerType";
  static final String TOMCAT = "tomcat";

  /** Vendor script names of spec §10.2 step 10, tried first when the package ships them. */
  static final String SCRIPT_PREFIX = "js-upgrade-";

  /** Ant target names used through {@code js-ant} when no wrapper script exists. */
  static final String ANT_TARGET_PREFIX = "upgrade-";

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
      return ANT_TARGET_PREFIX + in.options().mode().vendorSuffix();
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(DONE_MARKER);
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
      return scriptName()
          + " if shipped, else js-ant "
          + antTarget()
          + "; JAVA_HOME="
          + rt.config().vendor().javaHome().map(Path::toString).orElse("<unset>")
          + "; timeout "
          + rt.tools().timeout().toMinutes()
          + "m";
    }

    /*
     * Not irreversible: the vendor script cannot be undone in place, but spec §10.1 defines its
     * undo as the restore of rollback point B, and that is exactly what compensate() performs.
     * For --mode samedb the database migration stays; the plan summary says so in plain text.
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
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "vendor upgrade already completed in this run; skipping");
        return StepResult.ok();
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
                    new VendorTools.Invocation(withWrapper, scriptName(), List.of(), javaHome),
                    out,
                    Logs.scope(ctx, this));
      } else {
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "no " + wrapper.getFileName() + " in the package; using js-ant " + antTarget());
        run = rt.tools().ant(b, antTarget(), List.of(), javaHome, out, Logs.scope(ctx, this));
      }
      return switch (run) {
        case VendorRun.Completed c -> c.ok() ? done(ctx, out) : failed(ctx, c);
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "vendor upgrade did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process; the run is rolled back to point B",
                List.of(in.webappDir()),
                List.of(in.snapshots(ctx).dir()));
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }

    private StepResult done(Context ctx, EventSink out) {
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "done", StandardCharsets.UTF_8);
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
      try {
        Files.deleteIfExists(marker(ctx));
      } catch (IOException e) {
        problems.add("marker: " + e.getMessage());
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
