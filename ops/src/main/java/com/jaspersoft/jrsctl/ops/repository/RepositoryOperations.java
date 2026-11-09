package com.jaspersoft.jrsctl.ops.repository;

import com.jaspersoft.jrsctl.core.engine.Plan;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Loading a new repository schema from an export (spec §10.5, ADR-0052): the vendor's manual
 * alternative to {@code js-upgrade-newdb}, run as one journaled plan from a tools-only
 * configuration. Invariants: planning reads the buildomatic, the export and the configuration and
 * mutates nothing; a configuration that is not tools-only, a database type without a catalogue
 * rule, or a schema name that collides with the configured server's own database is refused while
 * planning; the plan's compensations drop only the schema the run itself created.
 */
public interface RepositoryOperations {

  /** Operation name of a load run in the journal and the stored plan. */
  String LOAD_OPERATION = "repository.load";

  /**
   * What the operator asked for. {@code schema} is the new database's name, which must equal {@code
   * js.dbName} in the buildomatic's {@code default_master.properties}; {@code export} is a {@code
   * js-export --everything} archive (or jrsctl's full-server export); {@code includeEvents} adds
   * the access, audit and monitoring events to the import; {@code keyAlias} names the key the
   * export was encrypted with when neither the sidecar nor the archive's index says.
   */
  record LoadOptions(String schema, Path export, boolean includeEvents, Optional<String> keyAlias) {

    public LoadOptions {
      Objects.requireNonNull(schema, "schema");
      Objects.requireNonNull(export, "export");
      Objects.requireNonNull(keyAlias, "keyAlias");
      schema = schema.strip();
      export = export.toAbsolutePath().normalize();
      keyAlias = keyAlias.map(String::strip).filter(a -> !a.isEmpty());
    }
  }

  Plan planLoad(LoadOptions options);
}
