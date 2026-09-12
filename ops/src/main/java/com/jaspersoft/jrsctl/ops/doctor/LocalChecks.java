package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Recovery;
import com.jaspersoft.jrsctl.core.engine.RunLock;
import com.jaspersoft.jrsctl.core.engine.RunRecord;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.LinuxInit;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.ServiceController;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.selfcheck.SelfCheck;
import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.core.state.StateStore;
import com.jaspersoft.jrsctl.core.state.StateStoreException;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@code doctor} checks that need no server connection (spec §12.1). Invariants: every method
 * is read-only; every non-PASS item names the next action; secret values are resolved only to
 * verify that they can be, and are closed immediately without being copied anywhere.
 */
final class LocalChecks {

  static final long GIB = 1L << 30;
  static final long DISK_WARN_BYTES = 5 * GIB;
  static final long DISK_FAIL_BYTES = 1 * GIB;
  static final String ELEVATED_SID = "S-1-16-12288";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

  private LocalChecks() {}

  static ReportItem runtime(Services s) {
    SelfCheck.Report report = new SelfCheck().run();
    String detail =
        report.items().stream()
            .map(i -> i.name() + "=" + i.status())
            .collect(Collectors.joining(", "));
    boolean warn = report.items().stream().anyMatch(i -> i.status() == SelfCheck.Status.WARN);
    if (!report.ok()) {
      String failing =
          report.items().stream()
              .filter(i -> i.status() == SelfCheck.Status.FAIL)
              .map(i -> i.name() + ": " + i.detail())
              .collect(Collectors.joining("; "));
      return ReportItem.fail("runtime", failing, "run jrsctl selfcheck and reinstall the archive");
    }
    return warn
        ? ReportItem.warn("runtime", detail, "run jrsctl selfcheck for details")
        : ReportItem.pass("runtime", detail);
  }

  static ReportItem config(Services s) {
    Path file = s.home().configFile();
    if (!Files.isRegularFile(file)) {
      return ReportItem.warn(
          "config", "no " + file + "; using defaults and JRSCTL_* overrides", "run jrsctl init");
    }
    Optional<java.net.URI> baseUrl = s.config().server().baseUrl();
    if (baseUrl.isEmpty()) {
      return ReportItem.fail(
          "config", file + " is valid but has no server.baseUrl", "run jrsctl init");
    }
    return ReportItem.pass("config", file + " valid; server " + baseUrl.get());
  }

  static ReportItem secrets(Services s) {
    Map<String, SecretRef> refs = configuredRefs(s.config());
    if (refs.isEmpty()) {
      return ReportItem.pass("secrets", "no secret references configured");
    }
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, SecretRef> ref : refs.entrySet()) {
      try (Secret unused = s.secrets().resolve(ref.getValue())) {
        // resolvable: file is owner-only, env is set, store unlocks
      } catch (SecretException e) {
        problems.add(ref.getKey() + " (" + ref.getValue().render() + "): " + e.getMessage());
      }
    }
    if (!problems.isEmpty()) {
      return ReportItem.fail(
          "secrets",
          String.join("; ", problems),
          "fix the reference, restrict the file to its owner, set the variable, or run jrsctl"
              + " secrets set");
    }
    return ReportItem.pass(
        "secrets", refs.size() + " reference(s) resolvable: " + String.join(", ", refs.keySet()));
  }

  static Map<String, SecretRef> configuredRefs(Config c) {
    Map<String, SecretRef> refs = new LinkedHashMap<>();
    c.server().auth().passwordRef().ifPresent(r -> refs.put("server.auth.passwordRef", r));
    c.database().passwordRef().ifPresent(r -> refs.put("database.passwordRef", r));
    c.network().proxy().passwordRef().ifPresent(r -> refs.put("network.proxy.passwordRef", r));
    c.network()
        .trustStore()
        .passwordRef()
        .ifPresent(r -> refs.put("network.trustStore.passwordRef", r));
    c.console().auth().passwordRef().ifPresent(r -> refs.put("console.auth.passwordRef", r));
    return refs;
  }

  static Optional<TomcatLayout> layout(Services s) {
    return s.config().server().installDir().flatMap(d -> s.platform().detectTomcat(d));
  }

  static ReportItem layout(Services s, Optional<TomcatLayout> layout) {
    Optional<Path> installDir = s.config().server().installDir();
    if (installDir.isEmpty()) {
      return ReportItem.fail("layout", "server.installDir is not configured", "run jrsctl init");
    }
    if (layout.isEmpty()) {
      return ReportItem.fail(
          "layout",
          "no Tomcat layout (tomcat dir with webapps/jasperserver[-pro]) under " + installDir.get(),
          "point server.installDir at the JasperReports Server installation root");
    }
    TomcatLayout l = layout.get();
    String webapp = s.config().server().webappName().map(Config.WebappName::yamlValue).orElse("");
    if (!webapp.isEmpty() && !webapp.equals(l.webappName())) {
      return ReportItem.fail(
          "layout",
          "server.webappName is " + webapp + " but the layout has " + l.webappName(),
          "set server.webappName to " + l.webappName());
    }
    return ReportItem.pass(
        "layout",
        "tomcat "
            + l.tomcatDir()
            + ", webapp "
            + l.webappName()
            + l.httpPort().map(p -> ", http port " + p).orElse("")
            + (l.buildomaticDir().isPresent() ? ", buildomatic present" : ", no buildomatic"));
  }

  static ReportItem service(Services s) {
    Optional<ServiceConfig.Kind> kind = s.config().service().kind();
    if (kind.isEmpty()) {
      return ReportItem.fail("service", "service.kind is not configured", "run jrsctl init");
    }
    if (kind.get() == ServiceConfig.Kind.MANUAL) {
      return ReportItem.warn(
          "service",
          "service.kind is manual: stop/start are operator instructions",
          "manual kind needs an interactive session; configure a service or script kind to"
              + " automate it");
    }
    try {
      ServiceController controller = s.platform().services(s.config().toServiceConfig());
      ServiceController.State state = controller.state();
      String detail = controller.describe() + " is " + state;
      return state == ServiceController.State.UNKNOWN
          ? ReportItem.warn(
              "service",
              detail,
              "check service.name / service.scriptPath and that jrsctl may query it")
          : ReportItem.pass("service", detail);
    } catch (RuntimeException e) {
      return ReportItem.fail(
          "service",
          "cannot query the service: " + e.getMessage(),
          "check service.kind, service.name and service.scriptPath in config.yaml");
    }
  }

  /** Report name of the service-manager check (review 3.2). */
  static final String SERVICE_MANAGER = "service-manager";

  /**
   * What supervises services on this host (review 3.2). A Linux box whose process 1 is not systemd
   * is named, so an operator does not discover during a hotfix that the supervisor restarted the
   * server behind a {@code catalina.sh} stop.
   */
  static ReportItem serviceManager(Services s) {
    // the check is about the machine doctor runs on, not the platform the configuration names
    if (Platforms.osFamily(System.getProperty("os.name", "")).orElse(null)
        == Platform.OsFamily.WINDOWS) {
      return ReportItem.pass(SERVICE_MANAGER, "Windows service control manager");
    }
    return serviceManagerItem(s.config().service().kind(), LinuxInit.detect());
  }

  static ReportItem serviceManagerItem(Optional<ServiceConfig.Kind> kind, LinuxInit.Detected init) {
    if (init.systemd()) {
      return ReportItem.pass(SERVICE_MANAGER, init.detail());
    }
    if (init.kind() == LinuxInit.Kind.UNKNOWN) {
      return ReportItem.warn(
          SERVICE_MANAGER,
          init.detail(),
          "confirm how this host starts Tomcat; service.kind is taken on trust while process 1"
              + " cannot be read");
    }
    if (kind.filter(k -> k == ServiceConfig.Kind.SYSTEMD).isPresent()) {
      return ReportItem.fail(
          SERVICE_MANAGER,
          "service.kind is systemd but " + init.detail(),
          "set service.kind to the way this host really starts Tomcat, or to manual");
    }
    if (init.supervised()) {
      return ReportItem.warn(
          SERVICE_MANAGER,
          init.detail(),
          "a script stop bypasses the supervisor, which may restart the server mid-run; stop the"
              + " server through its supervisor or set service.kind to manual");
    }
    return ReportItem.warn(
        SERVICE_MANAGER,
        init.detail(),
        "confirm how this host starts Tomcat; with no service manager a script stop is the only"
            + " way and nothing will restart the server for you");
  }

  static ReportItem permissions(Services s, Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip("permissions", "no install layout", "fix the layout check first");
    }
    List<Path> targets = new ArrayList<>();
    targets.add(layout.get().webInfLib());
    targets.add(layout.get().webInfClasses());
    layout.get().buildomaticDir().ifPresent(targets::add);
    FileOps files = s.platform().files();
    List<String> readOnly = new ArrayList<>();
    for (Path target : targets) {
      if (!Files.isDirectory(target)) {
        readOnly.add(target + " (missing)");
      } else if (!files.isWritable(target)) {
        readOnly.add(target.toString());
      }
    }
    if (!readOnly.isEmpty()) {
      return ReportItem.fail(
          "permissions",
          "not writable: " + String.join(", ", readOnly),
          "run jrsctl as the account that owns the installation or grant it write access");
    }
    return ReportItem.pass("permissions", targets.size() + " target dirs writable");
  }

  static ReportItem disk(Services s) {
    Path probe = s.config().server().installDir().orElse(s.home().root());
    long free;
    try {
      free = s.platform().files().freeSpaceBytes(Files.exists(probe) ? probe : s.home().root());
    } catch (IOException e) {
      return ReportItem.fail(
          "disk", "cannot read free space of " + probe + ": " + e.getMessage(), "check the path");
    }
    String snapshots;
    try {
      snapshots = ", snapshots use " + human(snapshotStore(s).totalBytes());
    } catch (IOException | RuntimeException e) {
      snapshots = ", snapshot size unknown";
    }
    String detail = human(free) + " free on " + probe + snapshots;
    if (free < DISK_FAIL_BYTES) {
      return ReportItem.fail(
          "disk", detail, "free at least 1 GB (5 GB recommended) or prune snapshots");
    }
    if (free < DISK_WARN_BYTES) {
      return ReportItem.warn("disk", detail, "5 GB recommended for backups and staging");
    }
    return ReportItem.pass("disk", detail);
  }

  static ReportItem vendor(Services s, Optional<TomcatLayout> layout) {
    if (layout.isEmpty()) {
      return ReportItem.skip("vendor", "no install layout", "fix the layout check first");
    }
    Optional<Path> buildomatic = layout.get().buildomaticDir();
    if (buildomatic.isEmpty()) {
      return ReportItem.fail(
          "vendor",
          "no buildomatic directory under " + layout.get().installDir(),
          "install the buildomatic scripts from the JasperReports Server distribution");
    }
    String ext =
        switch (s.platform().os()) {
          case WINDOWS -> ".bat";
          case LINUX -> ".sh";
        };
    List<String> missing = new ArrayList<>();
    for (String script : List.of("js-export", "js-import", "js-ant")) {
      if (!Files.isRegularFile(buildomatic.get().resolve(script + ext))) {
        missing.add(script + ext);
      }
    }
    if (!missing.isEmpty()) {
      return ReportItem.fail(
          "vendor",
          "missing in " + buildomatic.get() + ": " + String.join(", ", missing),
          "restore the vendor scripts; export/import and upgrade need them");
    }
    return ReportItem.pass(
        "vendor", "js-export, js-import, js-ant present in " + buildomatic.get());
  }

  /**
   * {@code PRAGMA quick_check} on {@code state.db} (review finding 1.18). The store refuses to open
   * a damaged file, so the failure surfaces here with the way out rather than in the first check
   * that needs the store.
   */
  static ReportItem state(Services s) {
    Path db = s.home().stateDb();
    if (!Files.exists(db)) {
      return ReportItem.pass("state", "no state.db yet; created on first use");
    }
    try {
      StateStore store = s.stateStore().get();
      String check = store.integrity();
      if ("ok".equals(check)) {
        return ReportItem.pass(
            "state", "quick_check ok, schema v" + store.schemaVersion() + ", " + db);
      }
      return ReportItem.fail(
          "state", "quick_check: " + check, StateStore.corruptionRemediation(db));
    } catch (StateStoreException e) {
      return ReportItem.fail(
          "state", "cannot open state.db: " + e.getMessage(), StateStore.corruptionRemediation(db));
    }
  }

  static ReportItem runs(Services s) {
    List<RunRecord> pending = Recovery.pendingRuns(s.stateStore().get());
    if (pending.isEmpty()) {
      return ReportItem.pass("runs", "no pending runs");
    }
    String ids = pending.stream().map(RunRecord::runId).collect(Collectors.joining(", "));
    return ReportItem.fail(
        "runs",
        pending.size() + " pending run(s): " + ids,
        "run jrsctl runs recover " + pending.get(0).runId() + " --resume or --rollback");
  }

  static ReportItem lock(Services s) {
    Optional<RunLock.Holder> holder = RunLock.readHolder(s.home().runLock());
    if (holder.isEmpty()) {
      return ReportItem.pass("lock", "run lock free");
    }
    return ReportItem.fail(
        "lock",
        "held by run " + holder.get().runId() + " (pid " + holder.get().pid() + ")",
        "wait for that jrsctl process to finish; if it is gone, delete " + s.home().runLock());
  }

  static ReportItem snapshots(Services s) {
    try {
      SnapshotStore store = snapshotStore(s);
      int count = store.list().size();
      return ReportItem.pass("snapshots", count + " snapshot(s), " + human(store.totalBytes()));
    } catch (IOException e) {
      return ReportItem.fail(
          "snapshots",
          "snapshot store unreadable: " + e.getMessage(),
          "inspect " + s.home().snapshots() + " and remove corrupt entries");
    }
  }

  static ReportItem network(Services s) {
    Config.Network network = s.config().network();
    boolean proxy = network.proxy().host().isPresent();
    if (network.mode() == Config.NetworkMode.ISOLATED && proxy) {
      return ReportItem.warn(
          "network",
          "network.mode is isolated but a proxy is configured; the proxy is never used",
          "remove network.proxy or set network.mode to public");
    }
    return ReportItem.pass(
        "network", "mode " + network.mode().yamlValue() + (proxy ? ", proxy configured" : ""));
  }

  static ReportItem elevated(Services s) {
    Platform platform = s.platform();
    boolean elevated =
        switch (platform.os()) {
          case LINUX -> "root".equals(System.getProperty("user.name", ""));
          case WINDOWS -> windowsElevated(platform.processes());
        };
    if (elevated) {
      return ReportItem.warn(
          "elevated",
          "running elevated; doctor needs no elevation",
          "run jrsctl as the account that owns the installation unless a step requires more");
    }
    return ReportItem.pass("elevated", "not running elevated");
  }

  private static boolean windowsElevated(ProcessRunner runner) {
    List<String> lines = new ArrayList<>();
    try {
      runner.run(
          new ProcessRunner.Request(
              List.of("whoami", "/groups"), Optional.empty(), Map.of(), PROBE_TIMEOUT),
          line -> lines.add(line.text()));
    } catch (RuntimeException e) {
      return false;
    }
    return lines.stream().anyMatch(l -> l.toUpperCase(Locale.ROOT).contains(ELEVATED_SID));
  }

  private static SnapshotStore snapshotStore(Services s) {
    return new SnapshotStore(s.home(), s.platform().files(), s.clock());
  }

  static String human(long bytes) {
    if (bytes >= GIB) {
      return String.format(Locale.ROOT, "%.1f GB", bytes / (double) GIB);
    }
    long mib = 1L << 20;
    if (bytes >= mib) {
      return String.format(Locale.ROOT, "%.1f MB", bytes / (double) mib);
    }
    return bytes + " B";
  }
}
