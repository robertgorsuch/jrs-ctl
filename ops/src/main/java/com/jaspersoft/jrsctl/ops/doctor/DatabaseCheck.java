package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.DefaultJdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import com.jaspersoft.jrsctl.ops.db.JdbcSettings;
import java.util.Optional;

/**
 * The {@code doctor} database connectivity check (spec §12.1). Invariants: the JDBC driver is
 * loaded from the configured {@code database.driverDir} or buildomatic's bundled driver directory
 * through a {@link JdbcConnector} (throw-away class loader, nothing registered with {@code
 * DriverManager}); the password is resolved into the connection properties and cleared right after
 * the attempt; the only statement run is a constant {@code SELECT 1}.
 */
final class DatabaseCheck {

  static final String NAME = "database";

  private DatabaseCheck() {}

  static ReportItem check(Services s) {
    return check(s, new DefaultJdbcConnector());
  }

  static ReportItem check(Services s, JdbcConnector connector) {
    Config.Database db = s.config().database();
    if (db.type().isEmpty()) {
      return ReportItem.skip(
          NAME,
          "not configured; needed only for hotfixes with SQL",
          "set database.type, database.url, database.username and database.passwordRef before"
              + " applying a hotfix that carries SQL");
    }
    Config.DatabaseType type = db.type().get();
    Optional<JdbcSettings> settings = JdbcSettings.from(s.config());
    if (settings.isEmpty()) {
      return ReportItem.fail(NAME, "database.url is not configured", "set database.url");
    }
    if (settings.get().driverDir().isEmpty()) {
      return ReportItem.fail(
          NAME,
          "no JDBC driver directory (database.driverDir or"
              + " <installDir>/buildomatic/conf_source/db/"
              + JdbcSettings.buildomaticDir(type)
              + "/jdbc)",
          "set database.driverDir to a directory holding the " + type.yamlValue() + " JDBC jar");
    }
    try (JdbcConnector.Session session = settings.get().open(connector, s.secrets())) {
      String product = session.product();
      if (!session.queryHasRow(settings.get().probeSql())) {
        return ReportItem.fail(
            NAME, "probe query returned no row from " + product, "check the database");
      }
      return ReportItem.pass(
          NAME, "connected to " + product + db.username().map(u -> " as " + u).orElse(""));
    } catch (SecretException e) {
      return ReportItem.fail(NAME, e.getMessage(), "fix " + db.passwordRef().get().render());
    } catch (JdbcException e) {
      return ReportItem.fail(
          NAME, e.getMessage(), remediation(e, type, settings.get().driverDir().get()));
    }
  }

  private static String remediation(
      JdbcException e, Config.DatabaseType type, java.nio.file.Path driverDir) {
    return switch (e.kind()) {
      case NO_DRIVER_JARS -> "copy the " + type.yamlValue() + " JDBC driver jar into " + driverDir;
      case NO_MATCHING_DRIVER -> "check database.url and that the driver jar matches database.type";
      case CONNECT_FAILED ->
          "check database.url, database.username and the password behind database.passwordRef";
      case SQL_FAILED -> "check the database";
      case IO -> "check the driver directory " + driverDir;
    };
  }
}
