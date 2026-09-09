package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Maps an operation name plus its JSON arguments back to a {@link Plan} builder so {@code runs
 * recover} can rebuild the plan of a pending run from {@code StoredPlan.argsJson()} (spec §6.6).
 * Invariants: the argument documents written by {@link #applyArgs}, {@link #rollbackArgs}, {@link
 * #exportArgs} and {@link #importArgs} are exactly what {@link #rebuild} reads, so a plan stored by
 * any mutating command always rebuilds; the export and import documents use the field names the
 * console front-end sends ({@code uris, usersRoles, accessEvents, fullServer, strategy, out};
 * {@code archive, update, skipUserUpdate, sourceKeystore, sourceKeystorePassword, strategy}); an
 * unknown operation fails with a message naming the known ones; the operations are obtained lazily
 * so listing the registry never touches the server.
 */
final class PlanRegistry {

  static final String HOTFIX_APPLY = "hotfix.apply";
  static final String HOTFIX_ROLLBACK = "hotfix.rollback";
  static final String EXPORT = "export";
  static final String IMPORT = "import";

  static final String UPGRADE = UpgradeOperations.UPGRADE_OPERATION;
  static final String UPGRADE_ROLLBACK = UpgradeOperations.ROLLBACK_OPERATION;

  private final Map<String, Function<JsonNode, Plan>> builders = new LinkedHashMap<>();

  PlanRegistry(
      Supplier<HotfixOperations> hotfix,
      Supplier<ExportImportOperations> exim,
      Supplier<UpgradeOperations> upgrade) {
    Objects.requireNonNull(hotfix, "hotfix");
    Objects.requireNonNull(exim, "exim");
    Objects.requireNonNull(upgrade, "upgrade");
    builders.put(
        UPGRADE,
        args ->
            upgrade
                .get()
                .planUpgrade(
                    new UpgradeOperations.UpgradeOptions(
                        required(args, "to"),
                        Path.of(required(args, "package")),
                        UpgradeOperations.Mode.valueOf(required(args, "mode")),
                        args.path("dbBackupConfirmed").asBoolean(false),
                        args.path("reapplyHotfixes").asBoolean(false))));
    builders.put(
        UPGRADE_ROLLBACK,
        args ->
            upgrade
                .get()
                .planRollback(
                    required(args, "runId"),
                    UpgradeOperations.RollbackPoint.valueOf(required(args, "point"))));
    builders.put(
        HOTFIX_APPLY,
        args ->
            hotfix
                .get()
                .planApply(
                    Path.of(required(args, "bundle")),
                    new HotfixOperations.ApplyOptions(
                        args.path("allowUnsigned").asBoolean(false))));
    builders.put(
        HOTFIX_ROLLBACK,
        args ->
            hotfix
                .get()
                .planRollback(
                    required(args, "id"),
                    new HotfixOperations.RollbackOptions(args.path("cascade").asBoolean(false))));
    builders.put(EXPORT, args -> exim.get().planExport(exportOptions(args)));
    builders.put(IMPORT, args -> exim.get().planImport(importOptions(args)));
  }

  Plan rebuild(String operation, String argsJson) {
    Function<JsonNode, Plan> builder = builders.get(operation);
    if (builder == null) {
      throw new IllegalArgumentException(
          "unknown operation '"
              + operation
              + "'; this build can rebuild plans for: "
              + String.join(", ", builders.keySet()));
    }
    JsonNode args;
    try {
      args = Json.mapper().readTree(argsJson);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("stored arguments of " + operation + " are not JSON", e);
    }
    return builder.apply(args);
  }

  static String applyArgs(Path bundle, boolean allowUnsigned) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("bundle", bundle.toAbsolutePath().normalize().toString());
    node.put("allowUnsigned", allowUnsigned);
    return Json.write(node);
  }

  static String rollbackArgs(String hotfixId, boolean cascade) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("id", hotfixId);
    node.put("cascade", cascade);
    return Json.write(node);
  }

  static String exportArgs(ExportImportOperations.ExportOptions o) {
    ObjectNode node = Json.mapper().createObjectNode();
    ArrayNode uris = node.putArray("uris");
    o.uris().stream().sorted().forEach(uris::add);
    node.put("usersRoles", o.usersRoles());
    node.put("accessEvents", o.accessEvents());
    node.put("auditEvents", o.auditEvents());
    node.put("monitoring", o.monitoring());
    node.put("settings", o.settings());
    node.put("fullServer", o.fullServer());
    putStrategy(node, o.strategy());
    node.put("out", o.out().toAbsolutePath().normalize().toString());
    return Json.write(node);
  }

  static String importArgs(ExportImportOperations.ImportOptions o) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("archive", o.archive().toAbsolutePath().normalize().toString());
    node.put("update", o.update());
    node.put("skipUserUpdate", o.skipUserUpdate());
    node.put("accessEvents", o.accessEvents());
    node.put("auditEvents", o.auditEvents());
    node.put("monitoring", o.monitoring());
    node.put("settings", o.settings());
    node.put("skipThemes", o.skipThemes());
    if (o.sourceKeystore().isPresent()) {
      node.put("sourceKeystore", o.sourceKeystore().get().toAbsolutePath().normalize().toString());
    } else {
      node.putNull("sourceKeystore");
    }
    if (o.sourceKeystorePassword().isPresent()) {
      node.put("sourceKeystorePassword", o.sourceKeystorePassword().get().render());
    } else {
      node.putNull("sourceKeystorePassword");
    }
    putStrategy(node, o.strategy());
    return Json.write(node);
  }

  static ExportImportOperations.ExportOptions exportOptions(JsonNode args) {
    Set<String> uris = new LinkedHashSet<>();
    for (JsonNode u : args.path("uris")) {
      if (u.isTextual() && !u.asText().isBlank()) {
        uris.add(u.asText());
      }
    }
    return new ExportImportOperations.ExportOptions(
        uris,
        args.path("usersRoles").asBoolean(false),
        args.path("accessEvents").asBoolean(false),
        args.path("auditEvents").asBoolean(false),
        args.path("monitoring").asBoolean(false),
        args.path("settings").asBoolean(false),
        args.path("fullServer").asBoolean(false),
        Path.of(required(args, "out")),
        strategy(args));
  }

  static ExportImportOperations.ImportOptions importOptions(JsonNode args) {
    return new ExportImportOperations.ImportOptions(
        Path.of(required(args, "archive")),
        args.path("update").asBoolean(false),
        args.path("skipUserUpdate").asBoolean(false),
        args.path("accessEvents").asBoolean(false),
        args.path("auditEvents").asBoolean(false),
        args.path("monitoring").asBoolean(false),
        args.path("settings").asBoolean(false),
        args.path("skipThemes").asBoolean(false),
        text(args, "sourceKeystore").map(Path::of),
        text(args, "sourceKeystorePassword").map(SecretRef::parse),
        strategy(args));
  }

  private static void putStrategy(ObjectNode node, Optional<ExportImportStrategy.Kind> kind) {
    if (kind.isPresent()) {
      node.put("strategy", StrategyFlag.render(kind.get()));
    } else {
      node.putNull("strategy");
    }
  }

  private static Optional<ExportImportStrategy.Kind> strategy(JsonNode args) {
    return text(args, "strategy").flatMap(StrategyFlag::parse);
  }

  static String upgradeArgs(UpgradeOperations.UpgradeOptions options) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("to", options.toVersion());
    node.put("package", options.packageDir().toString());
    node.put("mode", options.mode().name());
    node.put("dbBackupConfirmed", options.dbBackupConfirmed());
    node.put("reapplyHotfixes", options.reapplyHotfixes());
    return Json.write(node);
  }

  static String upgradeRollbackArgs(String runId, UpgradeOperations.RollbackPoint point) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("runId", runId);
    node.put("point", point.name());
    return Json.write(node);
  }

  private static Optional<String> text(JsonNode args, String field) {
    JsonNode v = args.get(field);
    if (v == null || !v.isTextual() || v.asText().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(v.asText());
  }

  private static String required(JsonNode args, String field) {
    return text(args, field)
        .orElseThrow(
            () -> new IllegalArgumentException("stored plan arguments lack '" + field + "'"));
  }
}
