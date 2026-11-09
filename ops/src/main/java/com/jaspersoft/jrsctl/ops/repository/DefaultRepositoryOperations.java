package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.RunIds;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.strategy.ExportIndex;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import com.jaspersoft.jrsctl.ops.BuildomaticDefaults;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.DatabaseCatalog;
import com.jaspersoft.jrsctl.ops.db.DefaultJdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds the {@code repository load} plan of spec §10.5 (ADR-0052). Invariants: planning reads the
 * configuration, the buildomatic, the export and its sidecar and mutates nothing; the plan's
 * preflight ends with {@code render-config}, which renders the buildomatic's configuration and
 * fails closed when the rendered JDBC URL is not the schema on the server the plan was made for
 * (issue #279 item 6); every refusal of the configuration's shape (not tools-only, no buildomatic,
 * no catalogue rule for the database type, no database password reference, a schema name that is
 * the configured server's own database) happens here, before a plan exists; the plan always carries
 * the five phases in spec order and names the schema drop as the rollback of the three mutating
 * phases; the fingerprint covers the schema name, the export's hash, the buildomatic directory and
 * its {@code default_master.properties} without password keys, so a staged file edited after
 * planning is caught before the run.
 */
public final class DefaultRepositoryOperations implements RepositoryOperations {

  public static final String STRATEGY = "vendor-cli";

  /** The rollback of preflight: only {@code render-config} touches disk there (issue #279). */
  public static final String PREFLIGHT_ROLLBACK =
      "js-ant clean-config removes what render-config rendered under the buildomatic's"
          + " build_conf/default, its own regenerable output; nothing else is touched";

  /** What the plan leaves to the operator, in the summary (spec §10.5). */
  public static final String OUTSIDE_THE_PLAN_WARNING =
      "Outside this plan: deploying the server that will use the schema; the superuser and"
          + " jasperadmin passwords, which stay as the minimal import set them (the vendor"
          + " defaults); the site's own tables; jrsctl import verify against the running server"
          + " afterwards; and pausing the imported report jobs before its scheduler starts (POST"
          + " /rest_v2/jobs/pause).";

  /** Upgrade guide 10.1 p.80: js-import leaves the events behind unless asked. */
  public static final String EVENTS_LEFT_BEHIND_WARNING =
      "The access, audit and monitoring events the export may hold are not imported; pass"
          + " --include-events to load them with the export.";

  /** The rollback of the create, initialise and load phases; {@code %s} is the schema. */
  public static final String ROLLBACK_SENTENCE =
      "drop schema %s, only if this run created it (schema.created) and default_master.properties"
          + " still names it; otherwise exit 4 names the DBA's statement";

  private final LoadRuntime rt;

  public DefaultRepositoryOperations(Services services) {
    this(
        new LoadRuntime(
            services,
            s ->
                new VendorTools(
                    s.platform().processes(),
                    s.platform().files(),
                    s.redactor(),
                    VendorTools.DEFAULT_TIMEOUT,
                    s.config().envSecretNames()),
            new DefaultJdbcConnector()));
  }

  DefaultRepositoryOperations(LoadRuntime rt) {
    this.rt = Objects.requireNonNull(rt, "rt");
  }

  @Override
  public Plan planLoad(LoadOptions options) {
    Objects.requireNonNull(options, "options");
    Config config = rt.config();
    Config.Server server = config.server();
    if (!server.namesToolsOnly()) {
      throw new RepositoryException(
          RepositoryException.PRECHECK,
          "repository load runs from a tools-only configuration (server.buildomaticDir and no"
              + " installation on this machine); this configuration "
              + (server.namesLocalInstallation()
                  ? "names an installation (server.installDir or server.tomcatDir): on a server"
                      + " host, jrsctl upgrade is the command"
                  : "names no buildomatic"),
          "write one with jrsctl init --tools-only --remote <url> --buildomatic-dir <dir>, or use"
              + " another jrsctl home (--home) for it");
    }
    if (!DatabaseCatalog.validName(options.schema())) {
      throw new RepositoryException(
          RepositoryException.USAGE,
          "schema name '"
              + options.schema()
              + "' is not a plain identifier (letters, digits and underscores, up to 63"
              + " characters)",
          "pass --schema with the name js.dbName has in default_master.properties");
    }
    Buildomatic buildomatic =
        switch (rt.locator().resolve(config)) {
          case BuildomaticResolution.Found found -> found.buildomatic();
          case BuildomaticResolution.NotFound missing ->
              throw new RepositoryException(
                  RepositoryException.PRECHECK, missing.detail(), missing.remediation());
        };
    String edition =
        LoadInput.edition(buildomatic)
            .orElseThrow(
                () ->
                    new RepositoryException(
                        RepositoryException.PRECHECK,
                        buildomatic.dir()
                            + " has neither conf_source/iePro nor conf_source/ieCe, so the edition"
                            + " of its init-js-db and import-minimal targets is unknown",
                        "point server.buildomaticDir at the buildomatic directory of an unpacked"
                            + " distribution"));
    if (!Files.isRegularFile(options.export())) {
      throw new RepositoryException(
          RepositoryException.PRECHECK,
          options.export() + " does not exist",
          "pass --from <export>, a js-export --everything archive or a jrsctl export --full-server"
              + " archive");
    }
    JdbcSettings jdbc =
        JdbcSettings.from(config, rt.services().platform())
            .orElseThrow(
                () ->
                    new RepositoryException(
                        RepositoryException.PRECHECK,
                        "database.type and database.url are not known; they are read from "
                            + buildomatic.dir().resolve(Buildomatic.MASTER_PROPERTIES)
                            + " (dbType, dbHost, dbPort, js.dbName) unless config.yaml sets them",
                        "set dbType, dbHost and js.dbName in default_master.properties, or"
                            + " database.type and database.url in config.yaml"));
    DatabaseCatalog catalog =
        DatabaseCatalog.of(jdbc)
            .orElseThrow(
                () ->
                    new RepositoryException(
                        RepositoryException.UNSUPPORTED,
                        "repository load supports PostgreSQL and MySQL; "
                            + jdbc.type().yamlValue()
                            + " has no catalogue rule here, and without one the plan cannot"
                            + " check that the schema does not exist or drop the one it created",
                        "create, initialise and load the schema with the vendor targets by hand"
                            + " (upgrade guide 10.1 p.62), or ask for the database type as a"
                            + " GitHub issue"));
    if (jdbc.passwordRef().isEmpty()) {
      throw new RepositoryException(
          RepositoryException.PRECHECK,
          "database.passwordRef is not set; the plan asks the database server's catalogue whether "
              + options.schema()
              + " exists before creating it, and drops it on failure, both through JDBC",
          "store the database password (jrsctl secrets set JRS_DB_PASSWORD) and set"
              + " database.passwordRef enc:JRS_DB_PASSWORD");
    }
    refuseCollision(config, jdbc, catalog, options.schema());
    String sha;
    try {
      sha = rt.files().sha256(options.export());
    } catch (IOException e) {
      throw new RepositoryException(
          RepositoryException.PRECHECK,
          "cannot read " + options.export() + ": " + e.getMessage(),
          "check the file and its permissions");
    }
    ExportIndex index;
    try {
      index = ExportIndex.read(options.export());
    } catch (IOException | IllegalArgumentException e) {
      throw new RepositoryException(
          RepositoryException.PRECHECK,
          options.export() + " is not a JasperReports Server export: " + e.getMessage(),
          "pass a js-export --everything archive or a jrsctl export --full-server archive");
    }
    List<String> warnings = new ArrayList<>();
    Optional<Sidecar> sidecar = readSidecar(options, warnings);
    LoadInput in =
        new LoadInput(
            options, buildomatic, edition, jdbc, catalog, sha, sidecar, Optional.of(index));

    warnings.add(OUTSIDE_THE_PLAN_WARNING);
    if (!options.includeEvents()) {
      warnings.add(EVENTS_LEFT_BEHIND_WARNING);
    }
    sidecar.ifPresent(
        s ->
            warnings.add(
                options.export().getFileName()
                    + " was exported from "
                    + s.serverIdentity()
                    + " (version "
                    + s.serverVersion()
                    + ")"
                    + (s.sha256().equals(sha) ? "" : "; its sidecar records another hash")));
    if (sidecar.isEmpty()) {
      warnings.add(
          "no sidecar beside "
              + options.export().getFileName()
              + ": where it was exported from is not checked"
              + index.jsVersion().map(v -> "; its index.xml says version " + v).orElse(""));
    }
    in.keyAlias()
        .ifPresent(
            a ->
                warnings.add(
                    "js-import decrypts with key alias "
                        + a
                        + " ("
                        + (options.keyAlias().isPresent()
                            ? "--key-alias"
                            : sidecar.flatMap(s -> s.flags().keyAlias()).isPresent()
                                ? "from the sidecar"
                                : "from the archive's index.xml")
                        + ")"));

    List<Step> steps =
        List.of(
            new LoadSteps.Doctor(rt, in),
            new LoadSteps.VerifyBuildomatic(rt, in),
            new LoadSteps.VerifyTargetSchema(rt, in),
            new LoadSteps.AdoptExport(rt, in),
            new LoadSteps.RenderConfig(rt, in),
            new LoadSteps.CreateJsDb(rt, in),
            new LoadSteps.InitJsDb(rt, in),
            new LoadSteps.ImportMinimal(rt, in),
            new LoadSteps.ImportExport(rt, in),
            new LoadSteps.RecordLoad(rt, in));
    Map<String, String> rollback = new LinkedHashMap<>();
    rollback.put(LoadPhases.PREFLIGHT, PREFLIGHT_ROLLBACK);
    String drop = ROLLBACK_SENTENCE.formatted(options.schema());
    rollback.put(LoadPhases.CREATE, drop);
    rollback.put(LoadPhases.INITIALISE, drop);
    rollback.put(LoadPhases.LOAD, drop);
    rollback.put(LoadPhases.VERIFY, "nothing further: the loaded schema stays");
    PlanSummary summary =
        new PlanSummary(
            LOAD_OPERATION,
            options.schema() + " on " + catalog.adminUrl() + " via " + buildomatic.dir(),
            List.of(),
            List.of(
                "database " + options.schema(),
                "buildomatic " + buildomatic.dir().resolve("build_conf").resolve("default")),
            false,
            List.of(),
            rollback,
            STRATEGY + " (js-ant and js-import of " + buildomatic.dir() + ")",
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("schema", options.schema());
    inputs.put("export", options.export().toString());
    inputs.put("exportSha256", sha);
    inputs.put("buildomatic", buildomatic.dir().toString());
    inputs.put("masterProperties", describe(buildomatic.masterProperties()));
    inputs.put("edition", edition);
    inputs.put("includeEvents", Boolean.toString(options.includeEvents()));
    in.keyAlias().ifPresent(a -> inputs.put("keyAlias", a));
    return new Plan(
        "repository-load-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  /**
   * ADR-0052: the one mistake the command exists to make impossible. When config.yaml sets a {@code
   * database.url} of its own, different from the one buildomatic's properties imply, it names the
   * configured server's repository; a {@code --schema} equal to that database is exit 6.
   */
  private void refuseCollision(
      Config config, JdbcSettings jdbc, DatabaseCatalog catalog, String schema) {
    Map<String, String> differ =
        BuildomaticDefaults.disagreements(config, rt.services().platform());
    if (!differ.containsKey("database.url")) {
      return;
    }
    if (catalog.schemaName().equals(schema)) {
      throw new RepositoryException(
          RepositoryException.UNSUPPORTED,
          "--schema "
              + schema
              + " is the database that config.yaml's database.url names ("
              + jdbc.url()
              + "), the configured server's own repository; repository load never creates or"
              + " drops that database",
          "pass a new schema name and set js.dbName to it in default_master.properties; the"
              + " server's own database stays untouched");
    }
  }

  /** The sidecar beside the export, or empty with a warning when it exists but cannot be read. */
  private static Optional<Sidecar> readSidecar(LoadOptions options, List<String> warnings) {
    try {
      return Sidecar.read(Sidecar.pathFor(options.export()));
    } catch (IOException | RuntimeException e) {
      warnings.add(
          "the sidecar beside "
              + options.export()
              + " cannot be read ("
              + e.getMessage()
              + "); where the export came from is not checked");
      return Optional.empty();
    }
  }

  /** The properties without password keys, sorted, for the fingerprint. */
  private static String describe(Map<String, String> master) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : new TreeMap<>(master).entrySet()) {
      sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
    }
    return sb.toString();
  }
}
