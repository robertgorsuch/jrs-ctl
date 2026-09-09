package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Entry points of the export/import subsystem (spec §9). Planning never mutates anything: it
 * connects to the server, chooses the strategy, and returns a {@link Plan} whose execution is the
 * {@code Runner}'s job. Invariants: an option record is exactly what the CLI flags and the console
 * arguments carry, so a plan can be rebuilt from them for {@code runs recover}; an empty {@code
 * strategy} means "select by the rules of spec §9.2", a present one forces that kind.
 */
public interface ExportImportOperations {

  /** Options for {@code jrsctl export}; an empty {@code uris} set means the whole repository. */
  record ExportOptions(
      Set<String> uris,
      boolean usersRoles,
      boolean accessEvents,
      boolean auditEvents,
      boolean monitoring,
      boolean settings,
      boolean fullServer,
      Path out,
      Optional<ExportImportStrategy.Kind> strategy) {

    public ExportOptions {
      uris = Set.copyOf(uris);
      Objects.requireNonNull(out, "out");
      Objects.requireNonNull(strategy, "strategy");
    }
  }

  /** Options for {@code jrsctl import}. */
  record ImportOptions(
      Path archive,
      boolean update,
      boolean skipUserUpdate,
      boolean accessEvents,
      boolean auditEvents,
      boolean monitoring,
      boolean settings,
      boolean skipThemes,
      Optional<Path> sourceKeystore,
      Optional<SecretRef> sourceKeystorePassword,
      Optional<ExportImportStrategy.Kind> strategy) {

    public ImportOptions {
      Objects.requireNonNull(archive, "archive");
      Objects.requireNonNull(sourceKeystore, "sourceKeystore");
      Objects.requireNonNull(sourceKeystorePassword, "sourceKeystorePassword");
      Objects.requireNonNull(strategy, "strategy");
    }
  }

  Plan planExport(ExportOptions options);

  Plan planImport(ImportOptions options);
}
