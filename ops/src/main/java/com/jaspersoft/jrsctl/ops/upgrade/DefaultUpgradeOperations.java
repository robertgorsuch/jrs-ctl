package com.jaspersoft.jrsctl.ops.upgrade;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigException;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.engine.Sleeper;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.rest.RestException;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.hotfix.DefaultHotfixOperations;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixException;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsctl.ops.service.ServiceSteps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the upgrade and rollback plans of spec §10. Invariants: planning reads the installation,
 * the target package and the state store but mutates neither the server nor the installation (it
 * may re-pack a hotfix bundle copy under {@code runs/upgrade-reapply/} for embedding); the upgrade
 * plan always carries the five phases in spec order and the {@code samedb} warning sentence of spec
 * §10.1 when that mode is chosen; an upgrade path the compat matrix does not list is refused at
 * planning time with exit code 6 whenever the server is reachable; the fingerprint covers the
 * server identity, the target package contents, the resolved configuration and the target version
 * and mode.
 */
public final class DefaultUpgradeOperations implements UpgradeOperations {

  static final String STRATEGY = "vendor-cli";
  static final String REAPPLY_DIR = "upgrade-reapply";

  /** Spec §10.1, quoted verbatim. */
  public static final String SAMEDB_WARNING =
      "Rollback restores files only. Restore the database from your own backup before running"
          + " rollback.";

  public static final String NEWDB_WARNING =
      "Rollback to point B is a complete file and connection restore: webapp, keystore,"
          + " configuration and the connection back to the old database.";

  static final String PASSWORD_WARNING =
      "database passwords are not copied into the target default_master.properties (spec §7.4);"
          + " add them there before running if the vendor scripts need them";

  private final UpgradeRuntime rt;

  public DefaultUpgradeOperations(Services services) {
    this(
        new UpgradeRuntime(
            services,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            s -> new VendorTools(s.platform().processes(), s.platform().files(), s.redactor()),
            DefaultHotfixOperations::new,
            Sleeper.system()));
  }

  DefaultUpgradeOperations(UpgradeRuntime rt) {
    this.rt = Objects.requireNonNull(rt, "rt");
  }

  @Override
  public Plan planUpgrade(UpgradeOptions options) {
    Objects.requireNonNull(options, "options");
    Config config = rt.config();
    HotfixPaths paths = paths(config);
    String webappName = webappName(config, paths);
    TargetPackage target = TargetPackage.inspect(options.packageDir(), rt.locator());
    Optional<ServerIdentity> identity = identity();
    List<String> warnings = new ArrayList<>();
    if (identity.isPresent()) {
      String current = identity.get().version();
      if (!rt.services().matrix().upgradePathSupported(current, options.toVersion())) {
        throw new UpgradeException(
            UpgradeException.UNSUPPORTED,
            "upgrade path "
                + current
                + " -> "
                + options.toVersion()
                + " is not in the compatibility matrix",
            "choose a supported target version");
      }
    } else {
      warnings.add(
          "server unreachable at plan time; the upgrade path is verified by "
              + PreflightSteps.VERIFY_TARGET_PACKAGE);
    }
    Map<String, String> installedMaster =
        rt.locator().locate(paths.installDir()).map(Buildomatic::masterProperties).orElse(Map.of());
    UpgradeInput in =
        new UpgradeInput(
            options,
            paths,
            webappName,
            target,
            identity,
            VendorSteps.masterOverrides(installedMaster, paths.tomcatDir()));
    List<String> problems = target.problems(rt.locator().scriptExtension());
    if (!problems.isEmpty()) {
      warnings.add(
          "target package: "
              + String.join("; ", problems)
              + " ("
              + PreflightSteps.VERIFY_TARGET_PACKAGE
              + " will refuse)");
    }
    warnings.add(
        switch (options.mode()) {
          case SAMEDB -> SAMEDB_WARNING;
          case NEWDB -> NEWDB_WARNING;
        });
    warnings.add(PASSWORD_WARNING);

    List<Step> steps = new ArrayList<>();
    steps.add(new PreflightSteps.Doctor(rt, in));
    steps.add(new PreflightSteps.VerifyTargetPackage(rt, in));
    if (options.mode() == Mode.SAMEDB) {
      steps.add(new PreflightSteps.ConfirmDbBackup(rt, in));
    }
    steps.add(ServiceSteps.stop(rt, Phases.BACKUP, BackupSteps.FULL_EXPORT + "-stop-service"));
    steps.add(new BackupSteps.FullExport(rt, in));
    steps.add(ServiceSteps.start(rt, Phases.BACKUP, BackupSteps.FULL_EXPORT + "-start-service"));
    steps.add(
        ServiceSteps.waitForServer(
            rt, Phases.BACKUP, BackupSteps.FULL_EXPORT + "-wait-for-server"));
    steps.add(new BackupSteps.BackupKeystore(rt, in));
    steps.add(new BackupSteps.BackupWebapp(rt, in));
    steps.add(new BackupSteps.BackupConfig(rt, in));
    steps.add(new VendorSteps.WriteMasterProperties(rt, in));
    steps.add(ServiceSteps.stop(rt, Phases.VENDOR_UPGRADE, VendorSteps.STOP_SERVICE));
    steps.add(new VendorSteps.RunVendorUpgrade(rt, in));
    steps.add(ServiceSteps.start(rt, Phases.VENDOR_UPGRADE, VendorSteps.START_SERVICE));
    steps.add(ServiceSteps.waitForServer(rt, Phases.VENDOR_UPGRADE, VendorSteps.WAIT_FOR_SERVER));
    List<Step> embedded = new ArrayList<>();
    List<String> embeddedIds = new ArrayList<>();
    if (options.reapplyHotfixes()) {
      embed(in, identity, embedded, embeddedIds, warnings);
    }
    steps.add(new ReconcileSteps.PlanHotfixReapply(rt, in, embeddedIds));
    steps.addAll(embedded);
    // A customization under WEB-INF/lib or WEB-INF/classes is re-applied with the service stopped,
    // the same rule every hotfix obeys (spec §5.3); anything else is re-applied live.
    boolean stopForCustomizations =
        rt.store().customizations().stream()
            .anyMatch(c -> HotfixPaths.requiresServiceStop(c.path().toString()));
    String reapply = ReconcileSteps.PLAN_CUSTOMIZATION_REAPPLY;
    if (stopForCustomizations) {
      warnings.add(
          "a registered customization lives under WEB-INF; the service is stopped again while it"
              + " is re-applied");
      steps.add(ServiceSteps.stop(rt, Phases.RECONCILE, reapply + "-stop-service"));
    }
    steps.add(new ReconcileSteps.PlanCustomizationReapply(rt, in));
    if (stopForCustomizations) {
      steps.add(ServiceSteps.start(rt, Phases.RECONCILE, reapply + "-start-service"));
      steps.add(ServiceSteps.waitForServer(rt, Phases.RECONCILE, reapply + "-wait-for-server"));
    }
    steps.add(new VerifySteps.Smoke(rt, in));
    steps.add(new VerifySteps.RecordUpgrade(rt, in));

    Path snapshotDir = SnapshotSet.placeholder(rt.home());
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(Phases.PREFLIGHT, "nothing mutated");
    rollbackPoints.put(
        Phases.BACKUP, "point B: backups written under " + snapshotDir + ", server untouched");
    rollbackPoints.put(
        Phases.VENDOR_UPGRADE,
        "point C = restore point B (webapp, buildomatic, configuration, keystore)");
    rollbackPoints.put(Phases.RECONCILE, "restore point B");
    rollbackPoints.put(
        Phases.VERIFY,
        "smoke failure offers rollback to point B: " + VerifySteps.rollbackCommand("{runId}"));
    List<Path> touched = new ArrayList<>();
    touched.add(in.webappDir());
    touched.add(in.installedBuildomatic());
    in.targetBuildomatic().ifPresent(b -> touched.add(b.resolve(Buildomatic.MASTER_PROPERTIES)));
    touched.addAll(in.configFiles());
    PlanSummary summary =
        new PlanSummary(
            UPGRADE_OPERATION,
            identity.map(ServerIdentity::version).orElse("?")
                + " -> "
                + options.toVersion()
                + " ("
                + options.mode().name().toLowerCase(java.util.Locale.ROOT)
                + ")",
            touched,
            List.of(),
            true,
            List.of(snapshotDir),
            rollbackPoints,
            STRATEGY,
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("package", target.contentHash());
    inputs.put("config", configHash(config));
    inputs.put("to", options.toVersion());
    inputs.put("mode", options.mode().name());
    inputs.put("reapplyHotfixes", Boolean.toString(options.reapplyHotfixes()));
    return new Plan(
        "upgrade-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  private void embed(
      UpgradeInput in,
      Optional<ServerIdentity> identity,
      List<Step> embedded,
      List<String> embeddedIds,
      List<String> warnings) {
    if (identity.isEmpty()) {
      warnings.add("--reapply-hotfixes ignored: server unreachable at plan time");
      return;
    }
    ServerIdentity target = PreflightSteps.targetIdentity(identity.get(), in.options().toVersion());
    for (HotfixReconciler.Classification c : HotfixReconciler.classify(rt, in, target)) {
      if (c.status() != HotfixReconciler.Status.REAPPLICABLE) {
        warnings.add(c.describe());
        continue;
      }
      if (!c.bundleAvailable()) {
        warnings.add(c.hotfix().id() + ": bundle no longer available, re-apply manually");
        continue;
      }
      Path zip =
          rt.home()
              .runs()
              .resolve(REAPPLY_DIR)
              .resolve(HotfixReconciler.slug(c.hotfix().id()) + ".zip");
      try {
        BundleZips.zip(c.bundleDir().orElseThrow(), zip);
        Plan apply = rt.hotfixes().planApply(zip, new HotfixOperations.ApplyOptions(false));
        for (Step s : apply.steps()) {
          embedded.add(new EmbeddedStep(s, c.hotfix().id()));
        }
        embeddedIds.add(c.hotfix().id());
        warnings.addAll(
            apply.summary().warnings().stream().map(w -> c.hotfix().id() + ": " + w).toList());
      } catch (IOException | HotfixException e) {
        warnings.add(
            c.hotfix().id()
                + ": cannot plan re-application ("
                + e.getMessage()
                + "); re-apply manually");
      }
    }
  }

  @Override
  public Plan planRollback(String runId, RollbackPoint point) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(point, "point");
    RunRecord run =
        rt.store()
            .run(runId)
            .orElseThrow(
                () ->
                    new UpgradeException(
                        UpgradeException.PRECHECK,
                        "unknown run " + runId,
                        "run jrsctl runs list to find the upgrade run id"));
    if (!run.operation().equals(UPGRADE_OPERATION)) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          runId + " is a " + run.operation() + " run, not an upgrade",
          "pass the id of an upgrade run");
    }
    SnapshotSet set = SnapshotSet.of(rt.home(), runId, rt.services().platform().os());
    PointBIntegrity.Report pointB =
        PointBIntegrity.check(rt.store(), rt.snapshots(), rt.files(), set, runId);
    if (!pointB.whole()) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          "rollback point B of run "
              + runId
              + " is incomplete: "
              + String.join("; ", pointB.problems()),
          "restore "
              + set.dir()
              + " from a backup, or roll this installation back by hand;"
              + " a partial restore would leave the old webapp against the new database");
    }
    Config config = rt.config();
    HotfixPaths paths = paths(config);
    RestoreSteps.Input in =
        new RestoreSteps.Input(runId, point, set, paths, webappName(config, paths));
    List<Step> steps = new ArrayList<>();
    steps.add(ServiceSteps.stop(rt, Phases.ROLLBACK, RestoreSteps.STOP_SERVICE));
    steps.add(new RestoreSteps.RestoreWebapp(rt, in));
    steps.add(new RestoreSteps.RestoreBuildomatic(rt, in));
    steps.add(new RestoreSteps.RestoreConfig(rt, in));
    steps.add(new RestoreSteps.RestoreKeystore(rt, in));
    steps.add(ServiceSteps.start(rt, Phases.ROLLBACK, RestoreSteps.START_SERVICE));
    steps.add(ServiceSteps.waitForServer(rt, Phases.ROLLBACK, RestoreSteps.WAIT_FOR_SERVER));
    steps.add(new RestoreSteps.RecordRollback(rt, in));
    List<String> warnings = new ArrayList<>();
    warnings.add(SAMEDB_WARNING);
    warnings.add(
        "point C restores the same point-B artefacts (spec §10.2: rollback point C = restore B)");
    Optional<String> mode = readMode(set);
    mode.ifPresent(
        m ->
            warnings.add(
                "the upgrade ran in "
                    + m
                    + " mode"
                    + (m.equals(Mode.NEWDB.name())
                        ? "; the restored configuration points back at the old database"
                        : "")));
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(
        Phases.ROLLBACK,
        "replaced trees are kept under runs/{runId}/"
            + PointB.ASIDE_DIR
            + " and put back by compensation");
    Optional<ServerIdentity> identity = identity();
    PlanSummary summary =
        new PlanSummary(
            ROLLBACK_OPERATION,
            "run " + runId + " to point " + point,
            List.of(in.webappDir(), in.installedBuildomatic()),
            List.of(),
            true,
            List.of(set.dir()),
            rollbackPoints,
            STRATEGY,
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("config", configHash(config));
    inputs.put("run", runId);
    inputs.put("point", point.name());
    try {
      inputs.put("webappArchive", PointB.readSha(set.webappArchive()).orElse("unrecorded"));
    } catch (IOException e) {
      inputs.put("webappArchive", "unreadable");
    }
    return new Plan(
        "upgrade-rollback-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  private static Optional<String> readMode(SnapshotSet set) {
    if (!Files.isRegularFile(set.manifest())) {
      return Optional.empty();
    }
    try {
      for (String line : Files.readAllLines(set.manifest(), StandardCharsets.UTF_8)) {
        String t = line.strip();
        if (t.startsWith("\"mode\"")) {
          int q = t.indexOf(':');
          return Optional.of(t.substring(q + 1).replace("\"", "").replace(",", "").strip());
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private HotfixPaths paths(Config config) {
    try {
      return HotfixPaths.from(config, rt.services().platform());
    } catch (HotfixException e) {
      throw new UpgradeException(UpgradeException.PRECHECK, e.getMessage(), e.remediation(), e);
    }
  }

  private String webappName(Config config, HotfixPaths paths) {
    return config
        .server()
        .webappName()
        .map(Config.WebappName::yamlValue)
        .or(
            () ->
                rt.services()
                    .platform()
                    .detectTomcat(paths.installDir())
                    .map(TomcatLayout::webappName))
        .orElseThrow(
            () ->
                new UpgradeException(
                    UpgradeException.PRECHECK,
                    "server.webappName is not set and no webapp was detected under "
                        + paths.installDir(),
                    "set server.webappName in config.yaml"));
  }

  private Optional<ServerIdentity> identity() {
    try {
      return Optional.of(rt.identity());
    } catch (JrsUnreachableException | RestException | ConfigException e) {
      return Optional.empty();
    }
  }

  static String configHash(Config config) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(md.digest(ConfigWriter.render(config).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
