package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.strategy.ExportIndex;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Entry points of the export/import subsystem (spec §9). Planning never mutates anything: it
 * connects to the server, chooses the strategy, and returns a {@link Plan} whose execution is the
 * {@code Runner}'s job. Invariants: an option record is exactly what the CLI flags carry, so a plan
 * can be rebuilt from them for {@code runs recover}; an empty {@code strategy} means "select by the
 * rules of spec §9.2", a present one forces that kind.
 */
public interface ExportImportOperations {

  /**
   * The source version a recorded sidecar carries when neither {@code index.xml} nor the operator
   * names one (issue #263): it parses as no version, so the import judges nothing by it.
   */
  String UNKNOWN_VERSION = "unknown";

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
      Optional<ExportImportStrategy.Kind> strategy,
      boolean stopService,
      Optional<String> keyAlias,
      Optional<String> organization,
      boolean skipDependentResources,
      boolean skipFavoriteResources) {

    public ExportOptions {
      uris = Set.copyOf(uris);
      Objects.requireNonNull(out, "out");
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(organization, "organization");
    }

    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy,
        boolean stopService,
        Optional<String> keyAlias,
        Optional<String> organization) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          stopService,
          keyAlias,
          organization,
          false,
          false);
    }

    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy,
        boolean stopService,
        Optional<String> keyAlias) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          stopService,
          keyAlias,
          Optional.empty());
    }

    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy,
        boolean stopService) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          stopService,
          Optional.empty());
    }

    /** With {@code stopService} true, what an export did before #67. */
    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          true);
    }
  }

  /**
   * Options for {@code jrsctl import}. {@code forceVersion} imports an archive whose sidecar
   * records a source version the vendor says cannot be imported here (10.1 and later into a server
   * below 10.1; issue #107) instead of refusing it; the override is audited. {@code keepThemes}
   * (issue #115) turns off the default of skipping themes when the archive comes from another major
   * version than this server. {@code snapshotStopsService} (ADR-0040) is true only for a plan
   * rebuilt from arguments an earlier jrsctl stored, whose vendor snapshot stopped and started the
   * service; every new plan takes the snapshot with the server running. {@code noSnapshot}
   * (ADR-0040) leaves out the pre-import snapshot and its re-import on rollback; the override is
   * audited.
   */
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
      Optional<ExportImportStrategy.Kind> strategy,
      BrokenDependencies brokenDependencies,
      Optional<String> keyAlias,
      Optional<String> organization,
      boolean mergeOrganization,
      boolean forceVersion,
      boolean keepThemes,
      boolean snapshotStopsService,
      boolean noSnapshot,
      boolean pauseJobs) {

    public ImportOptions {
      Objects.requireNonNull(archive, "archive");
      Objects.requireNonNull(sourceKeystore, "sourceKeystore");
      Objects.requireNonNull(sourceKeystorePassword, "sourceKeystorePassword");
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(brokenDependencies, "brokenDependencies");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(organization, "organization");
    }

    /** The options of an import that pauses no jobs (every option before issue #264). */
    public ImportOptions(
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
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization,
        boolean forceVersion,
        boolean keepThemes,
        boolean snapshotStopsService,
        boolean noSnapshot) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          snapshotStopsService,
          noSnapshot,
          false);
    }

    /** The same options with or without the pause of the jobs the import adds (issue #264). */
    public ImportOptions withPauseJobs(boolean pause) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          snapshotStopsService,
          noSnapshot,
          pause);
    }

    /** The options of a new import: the snapshot is taken, with the server running. */
    public ImportOptions(
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
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization,
        boolean forceVersion,
        boolean keepThemes) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          false,
          false);
    }

    /** The same options, the vendor snapshot stopping the service as it did before ADR-0040. */
    public ImportOptions withSnapshotStopsService(boolean stops) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          stops,
          noSnapshot,
          pauseJobs);
    }

    /** The same options with or without the pre-import snapshot ({@code --no-snapshot}). */
    public ImportOptions withNoSnapshot(boolean skip) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          snapshotStopsService,
          skip,
          pauseJobs);
    }

    /** The options that keep the themes default: skipped only across a major version. */
    public ImportOptions(
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
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization,
        boolean forceVersion) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          false);
    }

    /** The options that refuse an archive from a version the vendor says cannot come here. */
    public ImportOptions(
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
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          false);
    }

    public ImportOptions withForceVersion(boolean force) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          force,
          keepThemes,
          snapshotStopsService,
          noSnapshot,
          pauseJobs);
    }

    public ImportOptions withKeepThemes(boolean keep) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keep,
          snapshotStopsService,
          noSnapshot,
          pauseJobs);
    }

    public ImportOptions(
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
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          Optional.empty(),
          false);
    }

    public ImportOptions(
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
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          Optional.empty());
    }

    /** The options with the server's own default for broken dependencies ({@code fail}). */
    public ImportOptions(
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
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          BrokenDependencies.FAIL);
    }
  }

  Plan planExport(ExportOptions options);

  Plan planImport(ImportOptions options);

  /**
   * Options for {@code jrsctl export record} (issue #263): the archive {@code js-export} made by
   * hand, the exporting server's version when {@code index.xml} does not carry it (an explicit
   * value wins over the index), the exporting server's {@code .jrsks} for the fingerprint, the key
   * alias the archive was encrypted with (an explicit value wins; the Legacy alias is taken from
   * the index), whether an existing sidecar may be replaced, and the name of the exporting server's
   * repository database, which {@code repository load} then refuses as its schema.
   */
  record RecordSidecarOptions(
      Path archive,
      Optional<String> sourceVersion,
      Optional<Path> sourceKeystore,
      Optional<String> keyAlias,
      boolean force,
      Optional<String> sourceDatabase) {
    public RecordSidecarOptions {
      Objects.requireNonNull(archive, "archive");
      Objects.requireNonNull(sourceVersion, "sourceVersion");
      Objects.requireNonNull(sourceKeystore, "sourceKeystore");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(sourceDatabase, "sourceDatabase");
      sourceDatabase = sourceDatabase.map(String::strip).filter(d -> !d.isEmpty());
    }

    /** Without a source database name. */
    public RecordSidecarOptions(
        Path archive,
        Optional<String> sourceVersion,
        Optional<Path> sourceKeystore,
        Optional<String> keyAlias,
        boolean force) {
      this(archive, sourceVersion, sourceKeystore, keyAlias, force, Optional.empty());
    }
  }

  /** What {@code export record} wrote and what it read. */
  record RecordedSidecar(Path file, Sidecar sidecar, ExportIndex index) {
    public RecordedSidecar {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(sidecar, "sidecar");
      Objects.requireNonNull(index, "index");
    }
  }

  /**
   * Writes the {@code .jrsctl.json} sidecar for an archive jrsctl did not export (issue #263): the
   * archive's SHA-256, the version and content read from its {@code index.xml}, and what the
   * options add, marked {@link Sidecar#ORIGIN_RECORD}. Mutates only the sidecar file; refuses an
   * archive without {@code index.xml} ({@link IllegalArgumentException}) and an existing sidecar
   * without {@code force} ({@link IllegalStateException}), both before anything is written.
   */
  RecordedSidecar recordSidecar(RecordSidecarOptions options);
}
