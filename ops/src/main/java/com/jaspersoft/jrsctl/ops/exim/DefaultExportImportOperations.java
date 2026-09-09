package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.engine.PlanFingerprint;
import com.jaspersoft.jrsctl.core.engine.PlanSummary;
import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.jrs.strategy.Strategies;
import com.jaspersoft.jrsctl.ops.ConfigShow;
import com.jaspersoft.jrsctl.ops.Services;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Plans exports and imports (spec §9). An export plan is exactly the chosen strategy's export steps
 * in the {@code export} phase. An import plan has three phases: {@code precheck}, the strategy's
 * leading read-only import steps (keystore fingerprint comparison, vendor-tool location) moved
 * ahead of everything so a failed check exits 2 with nothing mutated; {@code backup}, a {@link
 * PreImportSnapshot} of the affected subtree taken with the same strategy as the import (the uris
 * recorded in the archive's sidecar, else the whole repository; a full-server export when {@code
 * --update} targets the root); and {@code import}, whose first step is the {@link
 * RestoreFromPreImportSnapshot} anchor followed by the strategy's mutating import steps, so a
 * failure anywhere in the phase re-imports the snapshot. Invariants: planning never mutates and
 * needs the server only to read its identity and capabilities; the snapshot lives under {@code
 * snapshots/pre-import/} with a name derived from the archive's hash, so {@code runs recover}
 * rebuilds an identical plan from the stored options; the summary always carries {@link
 * #BEST_EFFORT_WARNING}; the fingerprint covers the server identity, the archive or output path
 * (and the archive's SHA-256), the request flags and the resolved configuration.
 */
public final class DefaultExportImportOperations implements ExportImportOperations {

  public static final String EXPORT_OPERATION = "export";
  public static final String IMPORT_OPERATION = "import";
  public static final String EXPORT_PHASE = "export";
  public static final String PRECHECK_PHASE = "precheck";
  public static final String BACKUP_PHASE = "backup";
  public static final String IMPORT_PHASE = "import";
  public static final String SNAPSHOT_DIR = "pre-import";

  /** Spec §9.4, verbatim in every import plan summary. */
  public static final String BEST_EFFORT_WARNING =
      "Rollback re-imports the pre-import snapshot; it restores overwritten resources but cannot"
          + " delete resources the failed import created.";

  private final Services services;
  private final Strategies strategies;

  public DefaultExportImportOperations(Services services) {
    this(services, Strategies.standard(services.platform(), services.redactor()));
  }

  public DefaultExportImportOperations(Services services, Strategies strategies) {
    this.services = Objects.requireNonNull(services, "services");
    this.strategies = Objects.requireNonNull(strategies, "strategies");
  }

  // ---------------------------------------------------------------- export

  @Override
  public Plan planExport(ExportOptions options) {
    Objects.requireNonNull(options, "options");
    Path out = options.out().toAbsolutePath().normalize();
    ExportRequest request =
        new ExportRequest(
            options.fullServer() ? ExportRequest.Scope.EVERYTHING : ExportRequest.Scope.REPOSITORY,
            options.uris(),
            options.usersRoles(),
            options.accessEvents(),
            options.auditEvents(),
            options.monitoring(),
            options.settings(),
            options.fullServer(),
            out);
    JrsAdapter adapter = services.adapter().get();
    ServerIdentity identity = adapter.identity();
    Strategies.Selection selection =
        strategies.select(services.config(), adapter, request, options.strategy());
    List<Step> steps = selection.strategy().exportSteps(request);

    List<String> warnings = new ArrayList<>();
    if (selection.strategy().requiresServiceStop()) {
      warnings.add(
          "the service will be stopped for the vendor export and started again afterwards");
    }
    if (Files.exists(out)) {
      warnings.add(out + " exists and will be replaced");
    }
    PlanSummary summary =
        new PlanSummary(
            EXPORT_OPERATION,
            target(request),
            List.of(out, Sidecar.pathFor(out)),
            sortedUris(request),
            selection.strategy().requiresServiceStop(),
            List.of(),
            Map.of(EXPORT_PHASE, "delete the partial archive and its sidecar"),
            strategyLine(selection),
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.fingerprintInput());
    inputs.put("out", out.toString());
    inputs.put("request", describe(request));
    inputs.put("strategy", options.strategy().map(Enum::name).orElse("auto"));
    inputs.put("config", configHash());
    return new Plan(planId(EXPORT_OPERATION), steps, summary, PlanFingerprint.of(inputs));
  }

  // ---------------------------------------------------------------- import

  @Override
  public Plan planImport(ImportOptions options) {
    Objects.requireNonNull(options, "options");
    Path archive = options.archive().toAbsolutePath().normalize();
    if (!Files.isRegularFile(archive)) {
      throw new IllegalArgumentException("archive " + archive + " does not exist");
    }
    String archiveHash;
    try {
      archiveHash = services.platform().files().sha256(archive);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot hash " + archive + ": " + e.getMessage(), e);
    }
    ImportRequest request =
        new ImportRequest(
            archive,
            options.update(),
            options.skipUserUpdate(),
            options.accessEvents(),
            options.auditEvents(),
            options.monitoring(),
            options.settings(),
            options.skipThemes(),
            options.sourceKeystore().map(p -> p.toAbsolutePath().normalize()),
            options.sourceKeystorePassword());
    JrsAdapter adapter = services.adapter().get();
    ServerIdentity identity = adapter.identity();
    Strategies.Selection selection =
        strategies.select(services.config(), adapter, request, options.strategy());
    ExportImportStrategy strategy = selection.strategy();

    List<String> warnings = new ArrayList<>();
    warnings.add(BEST_EFFORT_WARNING);
    Optional<Sidecar> sidecar = readSidecar(archive, warnings);
    ExportRequest snapshotRequest =
        snapshotRequest(sidecar, options.update(), snapshotPath(archive, archiveHash));
    ImportRequest restore =
        new ImportRequest(
            snapshotRequest.output(),
            true,
            false,
            snapshotRequest.includeAccessEvents(),
            snapshotRequest.includeAuditEvents(),
            snapshotRequest.includeMonitoring(),
            snapshotRequest.includeSettings(),
            false,
            Optional.empty(),
            Optional.empty());
    if (strategy.requiresServiceStop()) {
      warnings.add(
          "the service will be stopped for the vendor snapshot and import and started again"
              + " afterwards");
    }
    if (options.update()) {
      warnings.add(
          "--update overwrites existing resources under "
              + PreImportSnapshot.describe(snapshotRequest));
    }

    List<Step> importSteps = strategy.importSteps(request);
    int firstMutating = 0;
    while (firstMutating < importSteps.size() && !importSteps.get(firstMutating).mutating()) {
      firstMutating++;
    }
    String importPhase =
        firstMutating < importSteps.size() ? importSteps.get(firstMutating).phase() : IMPORT_PHASE;
    List<Step> steps = new ArrayList<>();
    for (Step s : importSteps.subList(0, firstMutating)) {
      steps.add(Rephased.into(PRECHECK_PHASE, s));
    }
    steps.addAll(PreImportSnapshot.steps(strategy, snapshotRequest));
    steps.add(
        new RestoreFromPreImportSnapshot(importPhase, strategy, snapshotRequest.output(), restore));
    steps.addAll(importSteps.subList(firstMutating, importSteps.size()));

    Map<String, String> rollback = new LinkedHashMap<>();
    rollback.put(BACKUP_PHASE, "delete the pre-import snapshot");
    rollback.put(importPhase, "re-import the pre-import snapshot with update (best effort)");
    PlanSummary summary =
        new PlanSummary(
            IMPORT_OPERATION,
            archive.getFileName().toString(),
            List.of(),
            sortedUris(snapshotRequest),
            strategy.requiresServiceStop(),
            List.of(snapshotRequest.output()),
            rollback,
            strategyLine(selection),
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.fingerprintInput());
    inputs.put("archive", archive.toString());
    inputs.put("archiveSha256", archiveHash);
    inputs.put("request", describe(request));
    inputs.put("strategy", options.strategy().map(Enum::name).orElse("auto"));
    inputs.put("config", configHash());
    return new Plan(planId(IMPORT_OPERATION), steps, summary, PlanFingerprint.of(inputs));
  }

  /** Where the pre-import snapshot of {@code archive} goes; stable for the same archive bytes. */
  Path snapshotPath(Path archive, String archiveHash) {
    String name = archive.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    String tag = archiveHash.length() >= 12 ? archiveHash.substring(0, 12) : archiveHash;
    return services
        .home()
        .snapshots()
        .resolve(SNAPSHOT_DIR)
        .resolve("pre-import-" + base + "-" + tag + ".zip");
  }

  /**
   * The affected subtree: the sidecar's uris (the whole repository when it lists none or was a
   * full-server export), else "/"; a full-server snapshot when {@code update} targets the root.
   * Users and roles are included when the sidecar says the archive carries them, or always when
   * there is no sidecar to tell.
   */
  static ExportRequest snapshotRequest(Optional<Sidecar> sidecar, boolean update, Path output) {
    Set<String> uris = new TreeSet<>();
    boolean usersRoles = true;
    boolean access = false;
    boolean audit = false;
    boolean monitoring = false;
    boolean settings = false;
    if (sidecar.isPresent()) {
      Sidecar.Flags flags = sidecar.get().flags();
      if (!flags.fullServer() && flags.scope() == ExportRequest.Scope.REPOSITORY) {
        uris.addAll(flags.uris());
      }
      usersRoles = flags.includeUsersRoles();
      access = flags.includeAccessEvents();
      audit = flags.includeAuditEvents();
      monitoring = flags.includeMonitoring();
      settings = flags.includeSettings();
    }
    if (uris.isEmpty() || uris.contains("/")) {
      uris.clear();
      uris.add("/");
    }
    boolean root = uris.contains("/");
    boolean fullServer = update && root;
    return new ExportRequest(
        fullServer ? ExportRequest.Scope.EVERYTHING : ExportRequest.Scope.REPOSITORY,
        uris,
        usersRoles || fullServer,
        access,
        audit,
        monitoring,
        settings,
        fullServer,
        output);
  }

  private static Optional<Sidecar> readSidecar(Path archive, List<String> warnings) {
    Path file = Sidecar.pathFor(archive);
    try {
      Optional<Sidecar> sidecar = Sidecar.read(file);
      if (sidecar.isEmpty()) {
        warnings.add(
            "no sidecar "
                + file.getFileName()
                + " next to the archive: the keystore fingerprint cannot be verified and the"
                + " whole repository is snapshotted");
      }
      return sidecar;
    } catch (IOException | IllegalArgumentException e) {
      warnings.add(
          "sidecar "
              + file
              + " is unreadable ("
              + e.getMessage()
              + "); the import precheck will"
              + " report it and the whole repository is snapshotted");
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static String target(ExportRequest request) {
    return request.fullServer() ? "full server" : String.join(", ", sortedUris(request));
  }

  /** The repository subtrees a request covers; the root for a full-server or unscoped request. */
  private static List<String> sortedUris(ExportRequest request) {
    if (request.fullServer() || request.uris().isEmpty()) {
      return List.of("/");
    }
    return List.copyOf(new TreeSet<>(request.uris()));
  }

  static String strategyLine(Strategies.Selection selection) {
    String kind =
        switch (selection.kind()) {
          case REST -> "rest";
          case VENDOR_CLI -> "vendor";
        };
    return kind + " (" + selection.reason() + ")";
  }

  private static String describe(ExportRequest r) {
    return "scope="
        + r.scope()
        + ";uris="
        + new TreeSet<>(r.uris())
        + ";usersRoles="
        + r.includeUsersRoles()
        + ";access="
        + r.includeAccessEvents()
        + ";audit="
        + r.includeAuditEvents()
        + ";monitoring="
        + r.includeMonitoring()
        + ";settings="
        + r.includeSettings()
        + ";fullServer="
        + r.fullServer();
  }

  private static String describe(ImportRequest r) {
    return "update="
        + r.update()
        + ";skipUserUpdate="
        + r.skipUserUpdate()
        + ";access="
        + r.includeAccessEvents()
        + ";audit="
        + r.includeAuditEvents()
        + ";monitoring="
        + r.includeMonitoring()
        + ";settings="
        + r.includeSettings()
        + ";skipThemes="
        + r.skipThemes()
        + ";sourceKeystore="
        + r.sourceKeystore().map(Path::toString).orElse("")
        + ";sourceKeystorePassword="
        + r.sourceKeystorePassword().map(ref -> ref.render()).orElse("");
  }

  private String configHash() {
    return sha256(ConfigShow.render(services.config()));
  }

  private static String sha256(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String planId(String operation) {
    return operation + "-" + UUID.randomUUID();
  }
}
