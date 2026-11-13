package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.ExportIndex;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.ops.db.DatabaseCatalog;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import com.jaspersoft.jrsctl.ops.repository.RepositoryOperations.LoadOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the load steps read, resolved once while planning: the options, the located
 * buildomatic (its {@code default_master.properties} without password keys), the edition its tree
 * ships ({@code pro} or {@code ce}, from {@code conf_source/iePro} or {@code conf_source/ieCe}),
 * the JDBC settings and the catalogue rule for the target database server, the export's SHA-256 and
 * what its sidecar and index say. Invariant: immutable; the key alias is the flag's, else the
 * sidecar's, else index.xml's only when it is the Legacy alias (#312).
 */
record LoadInput(
    LoadOptions options,
    Buildomatic buildomatic,
    String edition,
    JdbcSettings jdbc,
    DatabaseCatalog catalog,
    String exportSha256,
    Optional<Sidecar> sidecar,
    Optional<ExportIndex> index) {

  static final String JS_DB_NAME = "js.dbName";
  static final String APP_SERVER_TYPE = "appServerType";
  static final String SKIP_APP_SERVER_CHECK = "skipAppServerCheck";
  static final String PRO = "pro";
  static final String CE = "ce";

  LoadInput {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(buildomatic, "buildomatic");
    Objects.requireNonNull(edition, "edition");
    Objects.requireNonNull(jdbc, "jdbc");
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(exportSha256, "exportSha256");
    Objects.requireNonNull(sidecar, "sidecar");
    Objects.requireNonNull(index, "index");
  }

  String schema() {
    return options.schema();
  }

  Path export() {
    return options.export();
  }

  Optional<String> master(String key) {
    return Optional.ofNullable(buildomatic.masterProperties().get(key)).map(String::strip);
  }

  /**
   * The alias js-import decrypts with: the flag's, else the sidecar's, else index.xml's only when
   * it is the Legacy alias. A plain js-export names the server's own import/export key in
   * index.xml, which --keyalias does not accept (the vendor import fails in getCipher, #312);
   * without an alias the importing server uses that same default key, so nothing is passed. jrsctl
   * import applies the same rule.
   */
  Optional<String> keyAlias() {
    return options
        .keyAlias()
        .or(() -> sidecar.flatMap(s -> s.flags().keyAlias()))
        .or(
            () ->
                index
                    .flatMap(ExportIndex::keyAlias)
                    .filter(ExportRequest.PORTABLE_KEY_ALIAS::equals));
  }

  /** Where this buildomatic's {@code gen-config} renders: {@code build_conf/default}. */
  Path renderedDir() {
    return RenderedConfig.dir(buildomatic);
  }

  String initTarget() {
    return "init-js-db-" + edition;
  }

  String minimalTarget() {
    return "import-minimal-" + edition;
  }

  /** {@code js-import --input-zip <export> --include-server-settings [events] [--keyalias]}. */
  ImportRequest importRequest() {
    return new ImportRequest(
        export(),
        false,
        false,
        options.includeEvents(),
        options.includeEvents(),
        options.includeEvents(),
        true,
        false,
        Optional.empty(),
        Optional.empty(),
        BrokenDependencies.FAIL,
        keyAlias(),
        Optional.empty(),
        false);
  }

  /** {@code pro} or {@code ce} from the buildomatic's own tree; empty when it ships neither. */
  static Optional<String> edition(Buildomatic buildomatic) {
    Path conf = buildomatic.dir().resolve("conf_source");
    if (Files.isDirectory(conf.resolve("iePro"))) {
      return Optional.of(PRO);
    }
    if (Files.isDirectory(conf.resolve("ieCe"))) {
      return Optional.of(CE);
    }
    return Optional.empty();
  }
}
