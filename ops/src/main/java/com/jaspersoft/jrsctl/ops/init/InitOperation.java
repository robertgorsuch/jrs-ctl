package com.jaspersoft.jrsctl.ops.init;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigWriter;
import com.jaspersoft.jrsctl.core.platform.LinuxInit;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.platform.TomcatLayout;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code jrsctl init} (spec §12.0): detects the installation and proposes a {@code config.yaml}.
 * Invariants: {@link #detect} is read-only and never throws for a missing or odd installation (it
 * reports what it found and defaults the rest); every value in the report carries its source; the
 * only write is {@link #write}, which refuses to overwrite an existing file unless forced;
 * passwords are never read from anywhere, only {@code env:} placeholders are written; confirmation
 * is the caller's job.
 */
public final class InitOperation {

  public static final String DEFAULT_USERNAME = "jasperadmin";
  public static final String DEFAULT_PASSWORD_REF = "env:JRS_PASSWORD";
  public static final String DEFAULT_DB_PASSWORD_REF = "env:JRS_DB_PASSWORD";
  public static final String DEFAULT_SMOKE_REPORT = "/public/Samples/Reports/AllAccounts";
  public static final int DEFAULT_HTTP_PORT = 8080;

  private static final Logger LOG = LoggerFactory.getLogger(InitOperation.class);
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern SC_SERVICE_NAME =
      Pattern.compile("^\\s*SERVICE_NAME:\\s*(\\S.*?)\\s*$");
  private static final String SOURCE_DEFAULT = "default";

  /** Report key carrying what supervises services on this host (review 3.2). */
  static final String SERVICE_MANAGER = "service.manager";

  private final Services services;
  private final Supplier<Optional<String>> tomcatOwner;
  private final Supplier<LinuxInit.Detected> linuxInit;

  public InitOperation(Services services) {
    this(services, InitOperation::owningUserOfTomcat);
  }

  InitOperation(Services services, Supplier<Optional<String>> tomcatOwner) {
    this(services, tomcatOwner, LinuxInit::detect);
  }

  InitOperation(
      Services services,
      Supplier<Optional<String>> tomcatOwner,
      Supplier<LinuxInit.Detected> linuxInit) {
    this.services = Objects.requireNonNull(services, "services");
    this.tomcatOwner = Objects.requireNonNull(tomcatOwner, "tomcatOwner");
    this.linuxInit = Objects.requireNonNull(linuxInit, "linuxInit");
  }

  /** Detects the installation; {@code installDirHint} is tried before the platform candidates. */
  public InitReport detect(Optional<Path> installDirHint) {
    Objects.requireNonNull(installDirHint, "installDirHint");
    Platform platform = services.platform();
    List<InitReport.Detected> values = new ArrayList<>();
    Config defaults = Config.defaults();

    Optional<Located> located = locate(installDirHint, platform);
    if (located.isEmpty()) {
      values.add(
          new InitReport.Detected(
              "server.installDir",
              "(not detected)",
              installDirHint
                  .map(p -> "no Tomcat layout under " + p)
                  .orElse("no candidate install dir found; pass --install-dir")));
      values.add(new InitReport.Detected("server.auth.username", DEFAULT_USERNAME, SOURCE_DEFAULT));
      values.add(
          new InitReport.Detected("server.auth.passwordRef", DEFAULT_PASSWORD_REF, SOURCE_DEFAULT));
      values.add(new InitReport.Detected("smoke.reportUri", DEFAULT_SMOKE_REPORT, SOURCE_DEFAULT));
      Config config =
          new Config(
              defaults.server(),
              defaults.service(),
              defaults.database(),
              defaults.vendor(),
              defaults.network(),
              defaults.console(),
              defaults.backups(),
              new Config.Smoke(Optional.of(DEFAULT_SMOKE_REPORT)));
      return new InitReport(config, values);
    }

    TomcatLayout layout = located.get().layout();
    values.add(
        new InitReport.Detected(
            "server.installDir", layout.installDir().toString(), located.get().source()));
    values.add(
        new InitReport.Detected(
            "server.tomcatDir", layout.tomcatDir().toString(), "detected from Tomcat layout"));
    values.add(
        new InitReport.Detected(
            "server.webappName", layout.webappName(), "detected from " + layout.webappDir()));
    int port = layout.httpPort().orElse(DEFAULT_HTTP_PORT);
    String portSource =
        layout.httpPort().isPresent() ? "detected from conf/server.xml" : SOURCE_DEFAULT;
    URI baseUrl = URI.create("http://localhost:" + port + "/" + layout.webappName());
    values.add(new InitReport.Detected("server.baseUrl", baseUrl.toString(), portSource));

    Optional<String> runAsUser = probeTomcatOwner();
    runAsUser.ifPresent(
        u ->
            values.add(
                new InitReport.Detected("server.runAsUser", u, "owner of the Tomcat process")));
    values.add(new InitReport.Detected("server.auth.username", DEFAULT_USERNAME, SOURCE_DEFAULT));
    values.add(
        new InitReport.Detected("server.auth.passwordRef", DEFAULT_PASSWORD_REF, SOURCE_DEFAULT));

    Config.Service service = probeService(layout, values);

    DefaultMasterProperties master = readDefaultMaster(layout);
    Config.Database database = database(master, values);

    Optional<Path> javaHome = layout.bundledJavaHome();
    javaHome.ifPresent(
        j ->
            values.add(
                new InitReport.Detected(
                    "vendor.javaHome", j.toString(), "bundled JDK in install dir")));
    values.add(new InitReport.Detected("smoke.reportUri", DEFAULT_SMOKE_REPORT, SOURCE_DEFAULT));
    values.add(
        new InitReport.Detected(
            "network.mode", defaults.network().mode().yamlValue(), SOURCE_DEFAULT));

    Config config =
        new Config(
            new Config.Server(
                Optional.of(baseUrl),
                Config.YamlValued.fromYaml(Config.WebappName.class, layout.webappName()),
                Optional.of(layout.installDir()),
                Optional.of(layout.tomcatDir()),
                runAsUser,
                new Config.Auth(
                    Config.AuthMode.DEFAULT,
                    Optional.of(DEFAULT_USERNAME),
                    Optional.of(SecretRef.parse(DEFAULT_PASSWORD_REF)))),
            service,
            database,
            new Config.Vendor(javaHome),
            defaults.network(),
            defaults.console(),
            defaults.backups(),
            new Config.Smoke(Optional.of(DEFAULT_SMOKE_REPORT)));
    return new InitReport(config, values);
  }

  /** The configuration the report proposes. */
  public Config toConfig(InitReport report) {
    return Objects.requireNonNull(report, "report").config();
  }

  /**
   * Writes {@code config} to {@code home.configFile()}.
   *
   * @throws FileAlreadyExistsException when the file exists and {@code force} is false
   */
  public Path write(Config config, boolean force) throws IOException {
    Path file = services.home().configFile();
    if (Files.exists(file) && !force) {
      throw new FileAlreadyExistsException(
          file.toString(), null, "config.yaml already exists; pass --force to overwrite it");
    }
    ConfigWriter.write(config, file);
    return file;
  }

  // ---- detection helpers ------------------------------------------------------------------------

  private record Located(TomcatLayout layout, String source) {}

  private static Optional<Located> locate(Optional<Path> hint, Platform platform) {
    if (hint.isPresent()) {
      Optional<TomcatLayout> fromHint = platform.detectTomcat(hint.get());
      if (fromHint.isPresent()) {
        return Optional.of(new Located(fromHint.get(), "from --install-dir"));
      }
      return Optional.empty();
    }
    List<Path> candidates;
    try {
      candidates = platform.candidateInstallDirs();
    } catch (RuntimeException e) {
      LOG.debug("candidate install dir probe failed", e);
      candidates = List.of();
    }
    for (Path candidate : candidates) {
      Optional<TomcatLayout> layout = platform.detectTomcat(candidate);
      if (layout.isPresent()) {
        return Optional.of(new Located(layout.get(), "detected candidate " + candidate));
      }
    }
    return Optional.empty();
  }

  private Optional<String> probeTomcatOwner() {
    try {
      return tomcatOwner.get();
    } catch (RuntimeException e) {
      LOG.debug("tomcat owner probe failed", e);
      return Optional.empty();
    }
  }

  private static Optional<String> owningUserOfTomcat() {
    return ProcessHandle.allProcesses()
        .filter(
            p ->
                p.info()
                    .commandLine()
                    .map(c -> c.toLowerCase(Locale.ROOT))
                    .filter(c -> c.contains("catalina") && c.contains("java"))
                    .isPresent())
        .map(p -> p.info().user())
        .flatMap(Optional::stream)
        .findFirst();
  }

  private Config.Service probeService(TomcatLayout layout, List<InitReport.Detected> values) {
    Platform platform = services.platform();
    // review 3.2: name the Linux service manager instead of assuming systemd, and never probe
    // systemctl on a host that process 1 says is supervised some other way
    Optional<LinuxInit.Detected> init =
        platform.os() == Platform.OsFamily.LINUX ? Optional.of(linuxInit.get()) : Optional.empty();
    init.ifPresent(
        d ->
            values.add(
                new InitReport.Detected(
                    SERVICE_MANAGER, d.kind().name().toLowerCase(Locale.ROOT), d.detail())));
    Optional<String> managed =
        switch (platform.os()) {
          case WINDOWS -> windowsService(platform.processes());
          case LINUX ->
              init.orElseThrow().supervised()
                  ? Optional.empty()
                  : systemdUnit(platform.processes());
        };
    ServiceConfig.Kind managedKind =
        switch (platform.os()) {
          case WINDOWS -> ServiceConfig.Kind.WINDOWS_SERVICE;
          case LINUX -> ServiceConfig.Kind.SYSTEMD;
        };
    String probeName =
        switch (platform.os()) {
          case WINDOWS -> "sc.exe query";
          case LINUX -> "systemctl list-units";
        };
    if (managed.isPresent()) {
      values.add(
          new InitReport.Detected(
              "service.kind", Config.Service.kindToYaml(managedKind), "detected via " + probeName));
      values.add(
          new InitReport.Detected("service.name", managed.get(), "detected via " + probeName));
      return new Config.Service(
          Optional.of(managedKind),
          managed,
          Optional.empty(),
          Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS);
    }
    String ext =
        switch (platform.os()) {
          case WINDOWS -> ".bat";
          case LINUX -> ".sh";
        };
    Path ctlscript = layout.installDir().resolve("ctlscript" + ext);
    if (Files.isRegularFile(ctlscript)) {
      warnIfSupervised(init, values);
      return scriptService(ServiceConfig.Kind.CTLSCRIPT, ctlscript, values);
    }
    Path catalina = layout.tomcatDir().resolve("bin").resolve("catalina" + ext);
    if (Files.isRegularFile(catalina)) {
      warnIfSupervised(init, values);
      return scriptService(ServiceConfig.Kind.CATALINA, catalina, values);
    }
    values.add(
        new InitReport.Detected(
            "service.kind",
            Config.Service.kindToYaml(ServiceConfig.Kind.MANUAL),
            "no service, ctlscript or catalina script found"));
    return new Config.Service(
        Optional.of(ServiceConfig.Kind.MANUAL),
        Optional.empty(),
        Optional.empty(),
        Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS);
  }

  /**
   * Records that a script stop can be undone by the supervisor that owns process 1, so the operator
   * sees it before confirming the proposed configuration (review 3.2).
   */
  private static void warnIfSupervised(
      Optional<LinuxInit.Detected> init, List<InitReport.Detected> values) {
    init.filter(LinuxInit.Detected::supervised)
        .ifPresent(
            d ->
                values.add(
                    new InitReport.Detected(
                        SERVICE_MANAGER,
                        "warning",
                        "a script stop bypasses "
                            + d.pid1().orElse(d.kind().name().toLowerCase(Locale.ROOT))
                            + ", which may restart the server mid-run; set service.kind to manual"
                            + " or stop the server through its supervisor")));
  }

  private static Config.Service scriptService(
      ServiceConfig.Kind kind, Path script, List<InitReport.Detected> values) {
    values.add(
        new InitReport.Detected(
            "service.kind", Config.Service.kindToYaml(kind), "detected " + script.getFileName()));
    values.add(new InitReport.Detected("service.scriptPath", script.toString(), "detected"));
    return new Config.Service(
        Optional.of(kind),
        Optional.empty(),
        Optional.of(script),
        Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS);
  }

  private static Optional<String> windowsService(ProcessRunner runner) {
    List<String> names = new ArrayList<>();
    run(
        runner,
        List.of("sc.exe", "query", "state=", "all"),
        line -> {
          Matcher m = SC_SERVICE_NAME.matcher(line);
          if (m.matches()) {
            names.add(m.group(1));
          }
        });
    return pickServiceName(names);
  }

  private static Optional<String> systemdUnit(ProcessRunner runner) {
    List<String> names = new ArrayList<>();
    run(
        runner,
        List.of("systemctl", "list-units", "--type=service", "--all", "--no-legend", "--plain"),
        line -> {
          String stripped = line.strip();
          if (!stripped.isEmpty()) {
            int space = stripped.indexOf(' ');
            names.add(space < 0 ? stripped : stripped.substring(0, space));
          }
        });
    return pickServiceName(names);
  }

  /**
   * Prefers the application-server service: a name mentioning both jasper and tomcat (the bundled
   * installer registers {@code jasperreportsTomcat} next to {@code jasperreportsPostgreSQL}), then
   * any tomcat, then any jasper name that is not the database service.
   */
  static Optional<String> pickServiceName(List<String> names) {
    List<String> lower = names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
    for (int i = 0; i < names.size(); i++) {
      if (lower.get(i).contains("jasper") && lower.get(i).contains("tomcat")) {
        return Optional.of(names.get(i));
      }
    }
    for (int i = 0; i < names.size(); i++) {
      if (lower.get(i).contains("tomcat")) {
        return Optional.of(names.get(i));
      }
    }
    for (int i = 0; i < names.size(); i++) {
      String n = lower.get(i);
      if (n.contains("jasper") && !n.contains("postgres") && !n.contains("sql")) {
        return Optional.of(names.get(i));
      }
    }
    return Optional.empty();
  }

  private static void run(
      ProcessRunner runner, List<String> command, java.util.function.Consumer<String> onStdout) {
    try {
      runner.run(
          new ProcessRunner.Request(command, Optional.empty(), Map.of(), PROBE_TIMEOUT),
          line -> {
            if (line.stream() == ProcessRunner.OutputLine.Stream.STDOUT) {
              onStdout.accept(line.text());
            }
          });
    } catch (RuntimeException e) {
      LOG.debug("service probe {} failed", command.get(0), e);
    }
  }

  private static DefaultMasterProperties readDefaultMaster(TomcatLayout layout) {
    Optional<Path> buildomatic = layout.buildomaticDir();
    if (buildomatic.isEmpty()) {
      return DefaultMasterProperties.empty();
    }
    try {
      return DefaultMasterProperties.parse(buildomatic.get().resolve("default_master.properties"));
    } catch (IOException e) {
      LOG.debug("cannot read default_master.properties", e);
      return DefaultMasterProperties.empty();
    }
  }

  private static Config.Database database(
      DefaultMasterProperties master, List<InitReport.Detected> values) {
    Optional<Config.DatabaseType> type = master.databaseType();
    if (type.isEmpty()) {
      return Config.Database.empty();
    }
    String source = "from default_master.properties";
    values.add(new InitReport.Detected("database.type", type.get().yamlValue(), source));
    Optional<String> url = master.jdbcUrl();
    url.ifPresent(u -> values.add(new InitReport.Detected("database.url", u, source)));
    Optional<String> user = master.dbUsername();
    user.ifPresent(u -> values.add(new InitReport.Detected("database.username", u, source)));
    values.add(
        new InitReport.Detected("database.passwordRef", DEFAULT_DB_PASSWORD_REF, SOURCE_DEFAULT));
    return new Config.Database(
        type, url, user, Optional.of(SecretRef.parse(DEFAULT_DB_PASSWORD_REF)), Optional.empty());
  }
}
