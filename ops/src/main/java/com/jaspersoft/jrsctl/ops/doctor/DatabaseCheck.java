package com.jaspersoft.jrsctl.ops.doctor;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The {@code doctor} database connectivity check (spec §12.1). Invariants: the JDBC driver is
 * loaded from the configured {@code database.driverDir} or buildomatic's bundled driver directory
 * in a throw-away class loader and connected through {@link Driver#connect}, so nothing is ever
 * registered with {@code DriverManager}; the password is resolved into the connection properties
 * and cleared right after the attempt; the only statement run is a constant {@code SELECT 1}.
 */
final class DatabaseCheck {

  static final String NAME = "database";

  private DatabaseCheck() {}

  static ReportItem check(Services s) {
    Config.Database db = s.config().database();
    if (db.type().isEmpty()) {
      return ReportItem.skip(
          NAME,
          "not configured; needed only for hotfixes with SQL",
          "set database.type, database.url, database.username and database.passwordRef before"
              + " applying a hotfix that carries SQL");
    }
    Config.DatabaseType type = db.type().get();
    if (db.url().isEmpty()) {
      return ReportItem.fail(NAME, "database.url is not configured", "set database.url");
    }
    Optional<Path> driverDir = db.driverDir().or(() -> bundledDriverDir(s, type));
    if (driverDir.isEmpty() || !Files.isDirectory(driverDir.get())) {
      return ReportItem.fail(
          NAME,
          "no JDBC driver directory (database.driverDir or"
              + " <installDir>/buildomatic/conf_source/db/"
              + buildomaticDir(type)
              + "/jdbc)",
          "set database.driverDir to a directory holding the " + type.yamlValue() + " JDBC jar");
    }
    List<URL> jars;
    try {
      jars = jars(driverDir.get());
    } catch (IOException e) {
      return ReportItem.fail(
          NAME, "cannot list " + driverDir.get() + ": " + e.getMessage(), "check the directory");
    }
    if (jars.isEmpty()) {
      return ReportItem.fail(
          NAME,
          "no *.jar in " + driverDir.get(),
          "copy the " + type.yamlValue() + " JDBC driver jar into " + driverDir.get());
    }
    String url = db.url().get();
    try (URLClassLoader loader =
        new URLClassLoader(jars.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
      Optional<Driver> driver = driverFor(loader, url);
      if (driver.isEmpty()) {
        return ReportItem.fail(
            NAME,
            "no driver in " + driverDir.get() + " accepts " + url,
            "check database.url and that the driver jar matches database.type");
      }
      return connect(s, db, driver.get(), url);
    } catch (IOException e) {
      return ReportItem.fail(
          NAME, "driver class loader: " + e.getMessage(), "check the driver jars");
    }
  }

  private static ReportItem connect(Services s, Config.Database db, Driver driver, String url) {
    Properties props = new Properties();
    db.username().ifPresent(u -> props.setProperty("user", u));
    if (db.passwordRef().isPresent()) {
      try (Secret secret = s.secrets().resolve(db.passwordRef().get())) {
        char[] chars = secret.chars();
        try {
          props.setProperty("password", new String(chars));
        } finally {
          Arrays.fill(chars, '\0');
        }
      } catch (SecretException e) {
        return ReportItem.fail(NAME, e.getMessage(), "fix " + db.passwordRef().get().render());
      }
    }
    try {
      try (Connection connection = driver.connect(url, props)) {
        if (connection == null) {
          return ReportItem.fail(
              NAME, driver.getClass().getName() + " refused " + url, "check database.url");
        }
        String product =
            connection.getMetaData().getDatabaseProductName()
                + " "
                + connection.getMetaData().getDatabaseProductVersion();
        try (Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(probeSql(db.type().orElseThrow()))) {
          if (!rs.next()) {
            return ReportItem.fail(
                NAME, "probe query returned no row from " + product, "check the database");
          }
        }
        return ReportItem.pass(
            NAME, "connected to " + product + db.username().map(u -> " as " + u).orElse(""));
      }
    } catch (SQLException e) {
      return ReportItem.fail(
          NAME,
          "cannot connect to " + url + ": " + e.getMessage(),
          "check database.url, database.username and the password behind database.passwordRef");
    } finally {
      props.clear();
    }
  }

  static String probeSql(Config.DatabaseType type) {
    return switch (type) {
      case ORACLE -> "SELECT 1 FROM DUAL";
      case DB2 -> "SELECT 1 FROM SYSIBM.SYSDUMMY1";
      case POSTGRESQL, MYSQL, MSSQL -> "SELECT 1";
    };
  }

  /** Buildomatic's directory name for a database type; {@code mssql} is {@code sqlserver} there. */
  static String buildomaticDir(Config.DatabaseType type) {
    return switch (type) {
      case MSSQL -> "sqlserver";
      case POSTGRESQL, MYSQL, ORACLE, DB2 -> type.yamlValue();
    };
  }

  private static Optional<Path> bundledDriverDir(Services s, Config.DatabaseType type) {
    return s.config()
        .server()
        .installDir()
        .map(
            d ->
                d.resolve("buildomatic")
                    .resolve("conf_source")
                    .resolve("db")
                    .resolve(buildomaticDir(type))
                    .resolve("jdbc"));
  }

  private static List<URL> jars(Path dir) throws IOException {
    List<URL> urls = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, "*.jar")) {
      for (Path jar : children) {
        try {
          urls.add(jar.toUri().toURL());
        } catch (MalformedURLException e) {
          throw new IOException("cannot address " + jar, e);
        }
      }
    }
    urls.sort((a, b) -> a.toString().compareTo(b.toString()));
    return urls;
  }

  private static Optional<Driver> driverFor(ClassLoader loader, String url) {
    try {
      for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
        try {
          if (driver.acceptsURL(url)) {
            return Optional.of(driver);
          }
        } catch (SQLException e) {
          // this driver cannot judge the URL; try the next one
        }
      }
    } catch (ServiceConfigurationError e) {
      return Optional.empty();
    }
    return Optional.empty();
  }
}
