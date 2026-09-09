package com.jaspersoft.jrsctl.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.ServiceConfig;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds the effective {@link Config} (spec §5.1) with precedence flag &gt; env &gt; file &gt;
 * default. The YAML file (if any), then {@code JRSCTL_<UPPER_SNAKE_PATH>} variables, then dotted
 * flag keys are merged into one JSON tree which is validated against the bundled {@code
 * schema/config.schema.json} before being mapped to records. Invariants: a missing file is not an
 * error (everything defaults, and {@link #requireServer} is the place that insists on a server);
 * every schema violation is reported at once, as {@code path: message}, in one {@link
 * ConfigException}; only keys the schema knows are read from the environment, so unrelated {@code
 * JRSCTL_*} variables (HOME, PASSPHRASE) are never mistaken for configuration.
 */
public final class ConfigLoader {

  public static final String ENV_PREFIX = "JRSCTL_";
  static final String SCHEMA_RESOURCE = "/schema/config.schema.json";

  private final JsonSchema schema;
  private final Map<String, SchemaKeys.Type> leafKeys;
  private final YAMLMapper yaml = new YAMLMapper();

  public ConfigLoader() {
    JsonNode schemaNode;
    try (InputStream in = ConfigLoader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the jar");
      }
      schemaNode = new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + SCHEMA_RESOURCE, e);
    }
    this.schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    this.leafKeys = SchemaKeys.leafPaths(schemaNode);
  }

  /** Loads {@code home.configFile()} (optional) and overlays {@code env} then {@code flags}. */
  public Config load(JrsctlHome home, Map<String, String> env, Map<String, String> flags) {
    Objects.requireNonNull(home, "home");
    return load(home.configFile(), env, flags);
  }

  /** As {@link #load(JrsctlHome, Map, Map)} for an explicit file path. */
  public Config load(Path file, Map<String, String> env, Map<String, String> flags) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(flags, "flags");
    ObjectNode tree = readFile(file);
    for (Map.Entry<String, SchemaKeys.Type> key : leafKeys.entrySet()) {
      String value = env.get(envKey(key.getKey()));
      if (value != null) {
        put(tree, key.getKey(), coerce(value, key.getValue()));
      }
    }
    for (Map.Entry<String, String> flag : flags.entrySet()) {
      SchemaKeys.Type type = leafKeys.getOrDefault(flag.getKey(), SchemaKeys.Type.STRING);
      put(tree, flag.getKey(), coerce(flag.getValue(), type));
    }
    validate(tree);
    return toConfig(tree);
  }

  /** Validates an already merged tree and maps it; exposed for {@code doctor}. */
  public Config fromTree(JsonNode tree) {
    if (!(tree instanceof ObjectNode obj)) {
      throw new ConfigException("configuration root is not a mapping", "check config.yaml");
    }
    validate(obj);
    return toConfig(obj);
  }

  /** Every leaf key the schema knows, in schema order. */
  public Set<String> knownKeys() {
    return leafKeys.keySet();
  }

  /**
   * The server base URL, or a {@link ConfigException} telling the operator to run {@code jrsctl
   * init} when the configuration has no server.
   */
  public static URI requireServer(Config config) {
    return config
        .server()
        .baseUrl()
        .orElseThrow(
            () ->
                new ConfigException(
                    "server.baseUrl is not configured; no config.yaml was found or it has no"
                        + " server section",
                    "run jrsctl init"));
  }

  /** {@code server.auth.passwordRef} becomes {@code JRSCTL_SERVER_AUTH_PASSWORD_REF}. */
  public static String envKey(String dottedPath) {
    StringBuilder sb = new StringBuilder(ENV_PREFIX);
    boolean first = true;
    for (String segment : dottedPath.split("\\.", -1)) {
      if (!first) {
        sb.append('_');
      }
      first = false;
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        if (Character.isUpperCase(c) && i > 0) {
          sb.append('_');
        }
        if (c == '-') {
          sb.append('_');
        } else {
          sb.append(Character.toUpperCase(c));
        }
      }
    }
    return sb.toString().toUpperCase(Locale.ROOT);
  }

  // ---- tree assembly ----------------------------------------------------------------------------

  private ObjectNode readFile(Path file) {
    if (!Files.isRegularFile(file)) {
      return JsonNodeFactory.instance.objectNode();
    }
    JsonNode root;
    try (InputStream in = Files.newInputStream(file)) {
      root = yaml.readTree(in);
    } catch (IOException e) {
      throw new ConfigException(
          "cannot parse " + file + ": " + e.getMessage(), "fix the YAML syntax in " + file);
    }
    if (root == null || root.isNull() || root.isMissingNode()) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (!(root instanceof ObjectNode obj)) {
      throw new ConfigException(
          file + " does not contain a top-level mapping", "start the file with server: ...");
    }
    return obj;
  }

  private static void put(ObjectNode root, String dottedPath, JsonNode value) {
    String[] segments = dottedPath.split("\\.", -1);
    ObjectNode node = root;
    for (int i = 0; i < segments.length - 1; i++) {
      JsonNode child = node.get(segments[i]);
      if (child == null || child.isNull()) {
        child = node.putObject(segments[i]);
      } else if (!(child instanceof ObjectNode)) {
        throw new ConfigException(
            String.join(".", Arrays.copyOf(segments, i + 1))
                + " is not a mapping, so "
                + dottedPath
                + " cannot be set",
            "fix config.yaml");
      }
      node = (ObjectNode) child;
    }
    node.set(segments[segments.length - 1], value);
  }

  private static JsonNode coerce(String raw, SchemaKeys.Type type) {
    String v = raw.strip();
    return switch (type) {
      case STRING -> TextNode.valueOf(raw);
      case NULLABLE_STRING ->
          v.isEmpty() || v.equals("null") ? NullNode.getInstance() : new TextNode(raw);
      case INTEGER -> {
        try {
          yield IntNode.valueOf(Integer.parseInt(v));
        } catch (NumberFormatException e) {
          yield TextNode.valueOf(raw);
        }
      }
      case BOOLEAN -> {
        String lower = v.toLowerCase(Locale.ROOT);
        yield lower.equals("true") || lower.equals("false")
            ? BooleanNode.valueOf(lower.equals("true"))
            : TextNode.valueOf(raw);
      }
    };
  }

  private void validate(ObjectNode tree) {
    Set<ValidationMessage> messages = schema.validate(tree);
    if (messages.isEmpty()) {
      return;
    }
    List<String> violations = new ArrayList<>();
    for (ValidationMessage m : messages) {
      String location = m.getInstanceLocation().toString();
      String path = location.startsWith("$.") ? location.substring(2) : location;
      if (path.equals("$") || path.isEmpty()) {
        path = "(root)";
      }
      String text = m.getMessage();
      String prefix = location + ": ";
      if (text.startsWith(prefix)) {
        text = text.substring(prefix.length());
      }
      violations.add(path + ": " + text);
    }
    violations.sort(String::compareTo);
    throw ConfigException.violations(violations);
  }

  // ---- tree to records --------------------------------------------------------------------------

  private static Config toConfig(ObjectNode root) {
    JsonNode server = root.path("server");
    JsonNode auth = server.path("auth");
    JsonNode service = root.path("service");
    JsonNode database = root.path("database");
    JsonNode vendor = root.path("vendor");
    JsonNode network = root.path("network");
    JsonNode proxy = network.path("proxy");
    JsonNode trust = network.path("trustStore");
    JsonNode console = root.path("console");
    JsonNode tls = console.path("tls");
    JsonNode consoleAuth = console.path("auth");
    JsonNode backups = root.path("backups");
    JsonNode smoke = root.path("smoke");

    return new Config(
        new Config.Server(
            text(server, "baseUrl").map(v -> uri("server.baseUrl", v)),
            text(server, "webappName")
                .map(v -> yamlEnum("server.webappName", Config.WebappName.class, v)),
            text(server, "installDir").map(v -> path("server.installDir", v)),
            text(server, "tomcatDir").map(v -> path("server.tomcatDir", v)),
            text(server, "runAsUser"),
            new Config.Auth(
                text(auth, "mode")
                    .map(v -> yamlEnum("server.auth.mode", Config.AuthMode.class, v))
                    .orElse(Config.AuthMode.DEFAULT),
                text(auth, "username"),
                text(auth, "passwordRef").map(v -> secretRef("server.auth.passwordRef", v)))),
        new Config.Service(
            text(service, "kind").map(v -> serviceKind("service.kind", v)),
            text(service, "name"),
            text(service, "scriptPath").map(v -> path("service.scriptPath", v)),
            integer(service, "stopTimeoutSeconds")
                .orElse(Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS)),
        new Config.Database(
            text(database, "type")
                .map(v -> yamlEnum("database.type", Config.DatabaseType.class, v)),
            text(database, "url"),
            text(database, "username"),
            text(database, "passwordRef").map(v -> secretRef("database.passwordRef", v)),
            text(database, "driverDir").map(v -> path("database.driverDir", v))),
        new Config.Vendor(text(vendor, "javaHome").map(v -> path("vendor.javaHome", v))),
        new Config.Network(
            text(network, "mode")
                .map(v -> yamlEnum("network.mode", Config.NetworkMode.class, v))
                .orElse(Config.NetworkMode.DEFAULT),
            new Config.Proxy(
                text(proxy, "host"),
                integer(proxy, "port"),
                text(proxy, "username"),
                text(proxy, "passwordRef").map(v -> secretRef("network.proxy.passwordRef", v))),
            new Config.TrustStore(
                text(trust, "path").map(v -> path("network.trustStore.path", v)),
                text(trust, "passwordRef")
                    .map(v -> secretRef("network.trustStore.passwordRef", v)))),
        new Config.Console(
            text(console, "bind").orElse(Config.Console.DEFAULT_BIND),
            integer(console, "port").orElse(Config.Console.DEFAULT_PORT),
            new Config.Tls(
                bool(tls, "enabled").orElse(false),
                text(tls, "certPath").map(v -> path("console.tls.certPath", v)),
                text(tls, "keyPath").map(v -> path("console.tls.keyPath", v))),
            new Config.ConsoleAuth(
                text(consoleAuth, "mode")
                    .map(v -> yamlEnum("console.auth.mode", Config.ConsoleAuthMode.class, v))
                    .orElse(Config.ConsoleAuthMode.DEFAULT),
                text(consoleAuth, "passwordRef")
                    .map(v -> secretRef("console.auth.passwordRef", v)))),
        new Config.Backups(
            integer(backups, "retentionDays").orElse(Config.Backups.DEFAULT_RETENTION_DAYS),
            integer(backups, "maxSnapshots").orElse(Config.Backups.DEFAULT_MAX_SNAPSHOTS)),
        new Config.Smoke(text(smoke, "reportUri")));
  }

  private static Optional<String> text(JsonNode parent, String field) {
    JsonNode v = parent.get(field);
    return v == null || v.isNull() || v.isMissingNode()
        ? Optional.empty()
        : Optional.of(v.asText());
  }

  private static Optional<Integer> integer(JsonNode parent, String field) {
    JsonNode v = parent.get(field);
    return v == null || !v.isNumber() ? Optional.empty() : Optional.of(v.intValue());
  }

  private static Optional<Boolean> bool(JsonNode parent, String field) {
    JsonNode v = parent.get(field);
    return v == null || !v.isBoolean() ? Optional.empty() : Optional.of(v.booleanValue());
  }

  private static URI uri(String key, String value) {
    try {
      return new URI(value);
    } catch (URISyntaxException e) {
      throw invalid(key, "is not a valid URI (" + e.getReason() + ")");
    }
  }

  private static Path path(String key, String value) {
    try {
      return Path.of(value);
    } catch (InvalidPathException e) {
      throw invalid(key, "is not a valid path (" + e.getReason() + ")");
    }
  }

  private static SecretRef secretRef(String key, String value) {
    try {
      return SecretRef.parse(value);
    } catch (IllegalArgumentException e) {
      throw invalid(key, e.getMessage());
    }
  }

  private static <E extends Enum<E> & Config.YamlValued> E yamlEnum(
      String key, Class<E> type, String value) {
    return Config.YamlValued.fromYaml(type, value).orElseThrow(unknownValue(key, value));
  }

  private static ServiceConfig.Kind serviceKind(String key, String value) {
    return Config.Service.kindFromYaml(value).orElseThrow(unknownValue(key, value));
  }

  private static Supplier<ConfigException> unknownValue(String key, String value) {
    return () -> invalid(key, "has unsupported value '" + value + "'");
  }

  private static ConfigException invalid(String key, String problem) {
    return ConfigException.violations(List.of(key + ": " + problem));
  }
}
