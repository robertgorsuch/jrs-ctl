package com.jaspersoft.jrsctl.core.config;

import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The effective configuration, mirroring {@code config.yaml} key for key (spec §5.1). Invariants:
 * every value the schema gives a default for is always present (auth mode, network mode, console
 * bind/port/auth mode, backup retention, service stop timeout); everything else is an {@link
 * Optional} so callers must decide what an absent key means; paths are {@link Path}s and secret
 * references are parsed {@link SecretRef}s, so an instance never holds an unvalidated string. The
 * tree is immutable; {@link ConfigLoader} builds it and {@link ConfigWriter} serialises it back so
 * that {@code init} output round-trips.
 */
public record Config(
    Server server,
    Service service,
    Database database,
    Vendor vendor,
    Network network,
    Console console,
    Backups backups,
    Smoke smoke) {

  public Config {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(vendor, "vendor");
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(console, "console");
    Objects.requireNonNull(backups, "backups");
    Objects.requireNonNull(smoke, "smoke");
  }

  /** A configuration holding nothing but the schema defaults. */
  public static Config defaults() {
    return new Config(
        Server.empty(),
        Service.empty(),
        Database.empty(),
        Vendor.empty(),
        Network.defaults(),
        Console.defaults(),
        Backups.defaults(),
        Smoke.empty());
  }

  /**
   * The {@code service:} block as the platform layer wants it.
   *
   * @throws ConfigException when {@code service.kind} is not set
   */
  public ServiceConfig toServiceConfig() {
    ServiceConfig.Kind kind =
        service
            .kind()
            .orElseThrow(
                () ->
                    new ConfigException(
                        "service.kind is not set",
                        "run jrsctl init or set service.kind in config.yaml"));
    return new ServiceConfig(
        kind,
        service.name(),
        service.scriptPath(),
        Duration.ofSeconds(service.stopTimeoutSeconds()));
  }

  /** {@code server.auth.mode}. */
  public enum AuthMode implements YamlValued {
    BASIC,
    FORM,
    TOKEN;

    public static final AuthMode DEFAULT = BASIC;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** {@code server.webappName}; explicit, never derived from the URL. */
  public enum WebappName implements YamlValued {
    JASPERSERVER("jasperserver"),
    JASPERSERVER_PRO("jasperserver-pro");

    private final String yaml;

    WebappName(String yaml) {
      this.yaml = yaml;
    }

    @Override
    public String yamlValue() {
      return yaml;
    }
  }

  /** {@code database.type}. */
  public enum DatabaseType implements YamlValued {
    POSTGRESQL,
    MYSQL,
    ORACLE,
    MSSQL,
    DB2;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** {@code network.mode}; isolated refuses every host but the server's. */
  public enum NetworkMode implements YamlValued {
    ISOLATED,
    PUBLIC;

    public static final NetworkMode DEFAULT = ISOLATED;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** {@code console.auth.mode}; the token is always required, local adds a password. */
  public enum ConsoleAuthMode implements YamlValued {
    TOKEN,
    LOCAL;

    public static final ConsoleAuthMode DEFAULT = TOKEN;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The {@code server:} block. */
  public record Server(
      Optional<URI> baseUrl,
      Optional<WebappName> webappName,
      Optional<Path> installDir,
      Optional<Path> tomcatDir,
      Optional<String> runAsUser,
      Auth auth) {

    public Server {
      Objects.requireNonNull(baseUrl, "baseUrl");
      Objects.requireNonNull(webappName, "webappName");
      Objects.requireNonNull(installDir, "installDir");
      Objects.requireNonNull(tomcatDir, "tomcatDir");
      Objects.requireNonNull(runAsUser, "runAsUser");
      Objects.requireNonNull(auth, "auth");
    }

    public static Server empty() {
      return new Server(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Auth.defaults());
    }
  }

  /** The {@code server.auth:} block. */
  public record Auth(AuthMode mode, Optional<String> username, Optional<SecretRef> passwordRef) {

    public Auth {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
    }

    public static Auth defaults() {
      return new Auth(AuthMode.DEFAULT, Optional.empty(), Optional.empty());
    }
  }

  /**
   * The {@code service:} block; {@code kind} spellings follow the YAML ({@code windows-service}).
   */
  public record Service(
      Optional<ServiceConfig.Kind> kind,
      Optional<String> name,
      Optional<Path> scriptPath,
      int stopTimeoutSeconds) {

    public static final int DEFAULT_STOP_TIMEOUT_SECONDS = 180;

    public Service {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(scriptPath, "scriptPath");
      if (stopTimeoutSeconds < 1) {
        throw new IllegalArgumentException("stopTimeoutSeconds must be >= 1");
      }
    }

    public static Service empty() {
      return new Service(
          Optional.empty(), Optional.empty(), Optional.empty(), DEFAULT_STOP_TIMEOUT_SECONDS);
    }

    /** YAML spelling of a service kind, e.g. {@code windows-service}. */
    public static String kindToYaml(ServiceConfig.Kind kind) {
      return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Inverse of {@link #kindToYaml}; empty for an unknown spelling. */
    public static Optional<ServiceConfig.Kind> kindFromYaml(String yaml) {
      for (ServiceConfig.Kind k : ServiceConfig.Kind.values()) {
        if (kindToYaml(k).equals(yaml)) {
          return Optional.of(k);
        }
      }
      return Optional.empty();
    }
  }

  /** The {@code database:} block; only needed for hotfixes that carry SQL. */
  public record Database(
      Optional<DatabaseType> type,
      Optional<String> url,
      Optional<String> username,
      Optional<SecretRef> passwordRef,
      Optional<Path> driverDir) {

    public Database {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
      Objects.requireNonNull(driverDir, "driverDir");
    }

    public static Database empty() {
      return new Database(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  /** The {@code vendor:} block: the JDK buildomatic runs with, never jrsctl's own runtime. */
  public record Vendor(Optional<Path> javaHome) {

    public Vendor {
      Objects.requireNonNull(javaHome, "javaHome");
    }

    public static Vendor empty() {
      return new Vendor(Optional.empty());
    }
  }

  /** The {@code network:} block. */
  public record Network(NetworkMode mode, Proxy proxy, TrustStore trustStore) {

    public Network {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(proxy, "proxy");
      Objects.requireNonNull(trustStore, "trustStore");
    }

    public static Network defaults() {
      return new Network(NetworkMode.DEFAULT, Proxy.empty(), TrustStore.empty());
    }
  }

  /** The {@code network.proxy:} block. */
  public record Proxy(
      Optional<String> host,
      Optional<Integer> port,
      Optional<String> username,
      Optional<SecretRef> passwordRef) {

    public Proxy {
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(port, "port");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
    }

    public static Proxy empty() {
      return new Proxy(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  /** The {@code network.trustStore:} block. */
  public record TrustStore(Optional<Path> path, Optional<SecretRef> passwordRef) {

    public TrustStore {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(passwordRef, "passwordRef");
    }

    public static TrustStore empty() {
      return new TrustStore(Optional.empty(), Optional.empty());
    }
  }

  /** The {@code console:} block. */
  public record Console(String bind, int port, Tls tls, ConsoleAuth auth) {

    public static final String DEFAULT_BIND = "127.0.0.1";
    public static final int DEFAULT_PORT = 7420;

    public Console {
      Objects.requireNonNull(bind, "bind");
      Objects.requireNonNull(tls, "tls");
      Objects.requireNonNull(auth, "auth");
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("console.port must be within 1..65535");
      }
    }

    public static Console defaults() {
      return new Console(DEFAULT_BIND, DEFAULT_PORT, Tls.disabled(), ConsoleAuth.defaults());
    }
  }

  /** The {@code console.tls:} block. */
  public record Tls(boolean enabled, Optional<Path> certPath, Optional<Path> keyPath) {

    public Tls {
      Objects.requireNonNull(certPath, "certPath");
      Objects.requireNonNull(keyPath, "keyPath");
    }

    public static Tls disabled() {
      return new Tls(false, Optional.empty(), Optional.empty());
    }
  }

  /** The {@code console.auth:} block. */
  public record ConsoleAuth(ConsoleAuthMode mode, Optional<SecretRef> passwordRef) {

    public ConsoleAuth {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(passwordRef, "passwordRef");
    }

    public static ConsoleAuth defaults() {
      return new ConsoleAuth(ConsoleAuthMode.DEFAULT, Optional.empty());
    }
  }

  /** The {@code backups:} block. */
  public record Backups(int retentionDays, int maxSnapshots) {

    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_MAX_SNAPSHOTS = 20;

    public Backups {
      if (retentionDays < 0) {
        throw new IllegalArgumentException("backups.retentionDays must be >= 0");
      }
      if (maxSnapshots < 1) {
        throw new IllegalArgumentException("backups.maxSnapshots must be >= 1");
      }
    }

    public static Backups defaults() {
      return new Backups(DEFAULT_RETENTION_DAYS, DEFAULT_MAX_SNAPSHOTS);
    }
  }

  /** The {@code smoke:} block. */
  public record Smoke(Optional<String> reportUri) {

    public Smoke {
      Objects.requireNonNull(reportUri, "reportUri");
    }

    public static Smoke empty() {
      return new Smoke(Optional.empty());
    }
  }

  /** An enumeration with a fixed YAML spelling. */
  public interface YamlValued {
    String yamlValue();

    /** The constant whose {@link #yamlValue()} equals {@code text}, or empty. */
    static <E extends Enum<E> & YamlValued> Optional<E> fromYaml(Class<E> type, String text) {
      for (E e : type.getEnumConstants()) {
        if (e.yamlValue().equals(text)) {
          return Optional.of(e);
        }
      }
      return Optional.empty();
    }
  }
}
