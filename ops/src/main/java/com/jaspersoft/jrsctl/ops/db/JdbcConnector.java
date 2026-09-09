package com.jaspersoft.jrsctl.ops.db;

import com.jaspersoft.jrsctl.core.secrets.Secret;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Opens JDBC sessions against the JasperReports repository database using driver jars that are not
 * on jrsctl's own class path (spec §8.2 step 9, §12.1 doctor). Invariants: the password never
 * leaves the {@link Secret} except into the driver's connection properties, which are cleared right
 * after the connection is established; every session owns its driver class loader and releases it
 * on {@link Session#close()}; statements run in the order the script lists them.
 */
public interface JdbcConnector {

  /**
   * Loads every {@code *.jar} under {@code driverDir}, picks the first {@code java.sql.Driver} that
   * accepts {@code url} and connects. Callers close the session in try-with-resources.
   */
  Session connect(Path driverDir, String url, Optional<String> username, Optional<Secret> password)
      throws JdbcException;

  /** One open connection plus the class loader that hosts its driver. */
  interface Session extends AutoCloseable {

    /** Database product name and version as the driver reports them. */
    String product();

    /** True when {@code sql} returns at least one row. */
    boolean queryHasRow(String sql) throws JdbcException;

    /**
     * Runs every statement of {@code sqlText} (see {@link SqlScript#statements}) and returns how
     * many were executed.
     */
    int execute(String sqlText) throws JdbcException;

    @Override
    void close() throws JdbcException;
  }
}
