package com.jaspersoft.jrsctl.ops.passwords;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.core.event.Event;
import com.jaspersoft.jrsctl.core.event.EventSink;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsctl.jrs.vendor.VendorRun;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.JrsVersion;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semver4j.Semver;

/**
 * Builds the {@code passwords migrate} plan and the {@code passwords status} report of spec §10.6
 * (ADR-0053). Invariants: both refuse a configuration without the installation on this machine
 * (REST-only or tools-only), because the vendor's utility reads {@code
 * WEB-INF/js.password-storage-config.properties} of the deployed webapp; a server older than 10.1
 * is refused with exit 6, since the utility and its settings file arrived with 10.1; planning
 * mutates nothing; the plan always carries {@code preflight}, {@code migrate} and {@code verify},
 * and {@code cleanup} only when asked; the fingerprint covers the webapp, the buildomatic, the
 * settings file's hash, the server version and the cleanup flag.
 */
public final class DefaultPasswordOperations implements PasswordOperations {

  public static final String STRATEGY = "vendor-cli";
  public static final Semver MIGRATION_FROM = new Semver("10.1.0");

  /** The gate sentence the plan and the CLI share (installation guide 10.1 p.195). */
  public static final String DB_BACKUP_GATE = PasswordSteps.DB_BACKUP_GATE;

  /** Installation guide 10.1 p.195, carried by every migration plan. */
  public static final String IRREVERSIBLE_WARNING =
      "The migration is permanent: once a password is stored in the modern format it cannot be"
          + " returned to the legacy one, and the only way back is a database restore from the"
          + " backup --db-backup-confirmed vouches for (installation guide 10.1 p.195).";

  /** Installation guide 10.1 p.195: who the utility skips, so the counts are read right. */
  public static final String SKIPPED_USERS_WARNING =
      "anonymousUser and externally authenticated users are skipped by design, so one legacy"
          + " password is expected to remain and more may when the server authenticates against"
          + " a directory or an identity provider.";

  private final PasswordRuntime rt;

  public DefaultPasswordOperations(Services services) {
    this(
        new PasswordRuntime(
            services,
            s ->
                new VendorTools(
                    s.platform().processes(),
                    s.platform().files(),
                    s.redactor(),
                    VendorTools.DEFAULT_TIMEOUT,
                    s.config().envSecretNames())));
  }

  DefaultPasswordOperations(PasswordRuntime rt) {
    this.rt = Objects.requireNonNull(rt, "rt");
  }

  /** The installation, the buildomatic and the server version, or the refusal (exit 2 or 6). */
  private Resolved resolve(String command) {
    Config config = rt.config();
    Config.Server server = config.server();
    if (!server.namesInstallation()) {
      throw new PasswordException(
          PasswordException.PRECHECK,
          "passwords "
              + command
              + " needs the JasperReports Server installation on this machine: the vendor's utility"
              + " reads WEB-INF/"
              + PasswordInput.STORAGE_CONFIG
              + " of the deployed webapp (installation guide 10.1 p.196), and this configuration "
              + (server.namesToolsOnly()
                  ? "is tools-only (a buildomatic without an installation)"
                  : "names no installation (server.installDir)"),
          "run jrsctl on the server host, configured with jrsctl init");
    }
    Path installDir =
        server
            .installDir()
            .orElseThrow(
                () ->
                    new PasswordException(
                        PasswordException.PRECHECK,
                        "server.installDir is not configured",
                        "run jrsctl init, or set server.installDir"));
    TomcatLayout layout =
        rt.services()
            .platform()
            .detectTomcat(installDir)
            .orElseThrow(
                () ->
                    new PasswordException(
                        PasswordException.PRECHECK,
                        "no Tomcat layout (webapps/jasperserver[-pro]) under " + installDir,
                        "check server.installDir, or run jrsctl doctor"));
    Buildomatic buildomatic =
        switch (rt.locator().resolve(config)) {
          case BuildomaticResolution.Found found -> found.buildomatic();
          case BuildomaticResolution.NotFound missing ->
              throw new PasswordException(
                  PasswordException.PRECHECK, missing.detail(), missing.remediation());
        };
    String version = serverVersion(layout);
    Semver parsed = Semver.coerce(version);
    if (parsed == null || parsed.isLowerThan(MIGRATION_FROM)) {
      throw new PasswordException(
          PasswordException.UNSUPPORTED,
          "the server is "
              + version
              + "; the password migration utility and its "
              + PasswordInput.STORAGE_CONFIG
              + " arrived with JasperReports Server 10.1",
          "upgrade to 10.1 or later first (jrsctl upgrade), then migrate the passwords");
    }
    return new Resolved(layout, buildomatic, version);
  }

  private record Resolved(TomcatLayout layout, Buildomatic buildomatic, String version) {}

  /** The deployed webapp's version from its jar names, else what the running server says. */
  private String serverVersion(TomcatLayout layout) {
    Optional<String> fromWebapp = JrsVersion.ofWebapp(layout.webappDir());
    if (fromWebapp.isPresent()) {
      return fromWebapp.get();
    }
    try {
      return rt.services().adapter().get().identity().version();
    } catch (RuntimeException e) {
      throw new PasswordException(
          PasswordException.PRECHECK,
          "the server version is unknown: no jasperserver-*.jar under "
              + layout.webInfLib()
              + " and the server did not answer ("
              + e.getMessage()
              + ")",
          "start the server, or check server.installDir");
    }
  }

  @Override
  public PasswordStatusReport status() {
    Resolved r = resolve("status");
    List<ReportItem> items = new ArrayList<>();
    Path storage = PasswordInput.storageConfig(r.layout());
    Optional<String> strategy = PasswordInput.strategy(storage);
    if (!Files.isRegularFile(storage)) {
      items.add(
          ReportItem.fail(
              "password-config",
              "no " + storage + "; the utility reads its settings there",
              "the deployed webapp must be 10.1 or later and ship "
                  + PasswordInput.STORAGE_CONFIG));
    } else if (strategy.filter(PasswordInput.MODERN::equals).isPresent()) {
      items.add(
          ReportItem.pass(
              "password-config", storage + ": " + PasswordInput.STRATEGY_KEY + "=modern"));
    } else {
      items.add(
          ReportItem.warn(
              "password-config",
              storage
                  + ": "
                  + PasswordInput.STRATEGY_KEY
                  + "="
                  + strategy.orElse("(unset)")
                  + "; new logins keep the legacy format",
              "set "
                  + PasswordInput.STRATEGY_KEY
                  + "=modern and restart the server before or after migrating (installation"
                  + " guide 10.1 p.194)"));
    }
    if (r.buildomatic().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()
        || rt.config().vendor().javaHome().isEmpty()) {
      items.add(
          ReportItem.fail(
              "vendor",
              r.buildomatic().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()
                  ? "no js-ant in " + r.buildomatic().dir()
                  : "vendor.javaHome is not set; js-ant needs a JDK",
              "restore the installed buildomatic and set vendor.javaHome"));
      items.add(
          ReportItem.skip(
              "strategy", "not asked: js-ant could not run", "fix the vendor item first"));
      return PasswordStatusReport.of(items, Optional.empty(), strategy);
    }
    items.add(ReportItem.pass("vendor", "js-ant in " + r.buildomatic().dir()));
    List<String> lines = new ArrayList<>();
    EventSink collecting =
        event -> {
          if (event instanceof Event.Log log) {
            lines.add(log.message());
          }
        };
    VendorRun run =
        rt.tools()
            .ant(
                r.buildomatic(),
                PasswordSteps.DETECT,
                List.of(),
                rt.config().vendor().javaHome(),
                collecting,
                new VendorTools.LogScope("status", Optional.empty(), "status"));
    Optional<PasswordCounts> counts = Optional.empty();
    switch (run) {
      case VendorRun.Completed c -> {
        counts = PasswordCounts.parse(lines);
        if (!c.ok()) {
          items.add(
              ReportItem.fail(
                  "strategy",
                  "js-ant "
                      + PasswordSteps.DETECT
                      + " "
                      + c.summary()
                      + ": "
                      + String.join(" | ", c.tail()),
                  "read the buildomatic log under " + r.buildomatic().dir()));
        } else if (counts.isEmpty()) {
          items.add(
              ReportItem.fail(
                  "strategy",
                  "js-ant " + PasswordSteps.DETECT + " printed no counts",
                  "run it by hand in " + r.buildomatic().dir() + " and read its output"));
        } else if (counts.get().complete()) {
          items.add(ReportItem.pass("strategy", "migration complete: " + counts.get().describe()));
        } else {
          items.add(
              ReportItem.warn(
                  "strategy",
                  counts.get().describe()
                      + "; "
                      + (counts.get().legacy() - 1)
                      + " user(s) beyond anonymousUser still on legacy passwords",
                  "jrsctl passwords migrate --db-backup-confirmed converts them (externally"
                      + " authenticated users are skipped by design)"));
        }
      }
      case VendorRun.TimedOut t ->
          items.add(
              ReportItem.fail(
                  "strategy",
                  "js-ant " + PasswordSteps.DETECT + " did not finish within " + t.timeout(),
                  "check for a hung buildomatic process"));
      case VendorRun.NotStarted n ->
          items.add(ReportItem.fail("strategy", n.reason(), n.remediation()));
    }
    return PasswordStatusReport.of(items, counts, strategy);
  }

  @Override
  public Plan planMigrate(MigrateOptions options) {
    Objects.requireNonNull(options, "options");
    Resolved r = resolve("migrate");
    PasswordInput in = new PasswordInput(options, r.buildomatic(), r.layout(), r.version());
    List<Step> steps = new ArrayList<>();
    steps.add(new PasswordSteps.Doctor(rt, in));
    steps.add(new PasswordSteps.VerifyTools(rt, in));
    steps.add(new PasswordSteps.ConfirmDbBackup(rt, in));
    steps.add(new PasswordSteps.DryRun(rt, in));
    steps.add(new PasswordSteps.Migrate(rt, in));
    steps.add(new PasswordSteps.Detect(rt, in));
    if (options.cleanup()) {
      steps.add(new PasswordSteps.Cleanup(rt, in));
    }
    List<String> warnings = new ArrayList<>();
    warnings.add(IRREVERSIBLE_WARNING);
    warnings.add(SKIPPED_USERS_WARNING);
    Optional<String> strategy = PasswordInput.strategy(in.storageConfig());
    if (strategy.isEmpty() || !strategy.get().equals(PasswordInput.MODERN)) {
      warnings.add(
          in.storageConfig()
              + " has "
              + PasswordInput.STRATEGY_KEY
              + "="
              + strategy.orElse("(unset)")
              + ": new logins keep the legacy format until the server is configured for modern"
              + " storage and restarted (installation guide 10.1 p.194).");
    }
    Map<String, String> rollback = new LinkedHashMap<>();
    rollback.put(PasswordPhases.PREFLIGHT, "nothing mutated");
    rollback.put(
        PasswordPhases.MIGRATE, "irreversible: restore the repository database from your backup");
    rollback.put(PasswordPhases.VERIFY, "nothing further");
    if (options.cleanup()) {
      rollback.put(PasswordPhases.CLEANUP, "irreversible: tracking rows only, no passwords");
    }
    PlanSummary summary =
        new PlanSummary(
            MIGRATE_OPERATION,
            r.layout().webappDir() + " (server " + r.version() + ")",
            List.of(),
            List.of("JIUser.password, password_strategy, password_algorithm of the repository"),
            false,
            List.of(),
            rollback,
            STRATEGY + " (js-ant of " + r.buildomatic().dir() + ")",
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("webapp", r.layout().webappDir().toString());
    inputs.put("buildomatic", r.buildomatic().dir().toString());
    inputs.put("serverVersion", r.version());
    inputs.put("cleanup", Boolean.toString(options.cleanup()));
    try {
      inputs.put(
          "storageConfigSha256",
          Files.isRegularFile(in.storageConfig())
              ? rt.files().sha256(in.storageConfig())
              : "absent");
    } catch (IOException e) {
      inputs.put("storageConfigSha256", "unreadable");
    }
    return new Plan(
        "passwords-migrate-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }
}
