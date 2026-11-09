package com.jaspersoft.jrsctl.ops.db;

import com.jaspersoft.jrsctl.core.config.Config;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The catalogue questions {@code repository load} asks a database server (ADR-0052): whether a
 * schema (a database, in PostgreSQL and MySQL terms) exists, and how to drop one by name. Each
 * instance is derived from the configured JDBC URL, whose last path segment names the schema;
 * {@link #adminUrl()} is the same URL pointed at the server's maintenance database, since neither
 * server lets a connection ask about or drop the very database it is connected to. Invariants: a
 * schema name reaches SQL text only when it is a plain identifier ({@link #validName}), so no value
 * from a configuration file or a flag can carry SQL along; the SQL is constant text per database
 * type; the types without a rule here (Oracle, SQL Server, DB2) yield {@link Optional#empty()} and
 * the caller refuses rather than guesses.
 */
public record DatabaseCatalog(Config.DatabaseType type, String adminUrl, String schemaName) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
  private static final String POSTGRESQL_MAINTENANCE = "postgres";
  private static final String MYSQL_MAINTENANCE = "information_schema";

  public DatabaseCatalog {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(adminUrl, "adminUrl");
    Objects.requireNonNull(schemaName, "schemaName");
  }

  /** True when {@code name} is a plain identifier: letters, digits and underscores only. */
  public static boolean validName(String name) {
    return name != null && NAME.matcher(name).matches();
  }

  /**
   * The catalogue rule for the configured database, or empty when the type has none or the URL
   * names no database.
   */
  public static Optional<DatabaseCatalog> of(JdbcSettings settings) {
    Objects.requireNonNull(settings, "settings");
    return switch (settings.type()) {
      case POSTGRESQL -> pathStyle(settings, POSTGRESQL_MAINTENANCE);
      case MYSQL -> pathStyle(settings, MYSQL_MAINTENANCE);
      case ORACLE, MSSQL, DB2 -> Optional.empty();
    };
  }

  /** {@code jdbc:<type>://host:port/<db>[?params]}: the database is the last path segment. */
  private static Optional<DatabaseCatalog> pathStyle(JdbcSettings settings, String maintenance) {
    String url = settings.url();
    int query = url.indexOf('?');
    String base = query < 0 ? url : url.substring(0, query);
    String params = query < 0 ? "" : url.substring(query);
    int slash = base.lastIndexOf('/');
    if (slash < 0 || slash == base.length() - 1 || base.charAt(slash - 1) == '/') {
      return Optional.empty();
    }
    String name = base.substring(slash + 1);
    return Optional.of(
        new DatabaseCatalog(
            settings.type(), base.substring(0, slash + 1) + maintenance + params, name));
  }

  /** Constant SQL that returns a row when {@code name} exists on the server. */
  public String existsSql(String name) {
    String literal = requireValid(name);
    return switch (type) {
      case POSTGRESQL -> "SELECT 1 FROM pg_database WHERE datname = '" + literal + "'";
      case MYSQL ->
          "SELECT 1 FROM information_schema.schemata WHERE schema_name = '" + literal + "'";
      case ORACLE, MSSQL, DB2 -> throw new IllegalStateException("no catalog rule for " + type);
    };
  }

  /** Constant SQL that drops {@code name}; the caller decides whether it may be sent. */
  public String dropSql(String name) {
    String identifier = requireValid(name);
    return switch (type) {
      case POSTGRESQL -> "DROP DATABASE \"" + identifier + "\"";
      case MYSQL -> "DROP DATABASE `" + identifier + "`";
      case ORACLE, MSSQL, DB2 -> throw new IllegalStateException("no catalog rule for " + type);
    };
  }

  private static String requireValid(String name) {
    if (!validName(name)) {
      throw new IllegalArgumentException(
          "schema name '"
              + name
              + "' is not a plain identifier (letters, digits and underscores, up to 63"
              + " characters)");
    }
    return name;
  }
}
