package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Maps an operation name plus its JSON arguments back to a {@link Plan} builder so {@code runs
 * recover} can rebuild the plan of a pending run from {@code StoredPlan.argsJson()} (spec §6.6).
 * Invariants: the argument documents written by {@link #applyArgs} and {@link #rollbackArgs} are
 * exactly what {@link #rebuild} reads, so a plan stored by {@code hotfix apply} always rebuilds; an
 * unknown operation fails with a message naming the known ones; the hotfix operations are obtained
 * lazily so listing the registry never touches the server.
 */
final class PlanRegistry {

  static final String HOTFIX_APPLY = "hotfix.apply";
  static final String HOTFIX_ROLLBACK = "hotfix.rollback";
  static final String UPGRADE = UpgradeOperations.UPGRADE_OPERATION;
  static final String UPGRADE_ROLLBACK = UpgradeOperations.ROLLBACK_OPERATION;

  private final Map<String, Function<JsonNode, Plan>> builders = new LinkedHashMap<>();

  PlanRegistry(Supplier<HotfixOperations> hotfix, Supplier<UpgradeOperations> upgrade) {
    Objects.requireNonNull(hotfix, "hotfix");
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

  private static String required(JsonNode args, String field) {
    JsonNode v = args.get(field);
    if (v == null || !v.isTextual() || v.asText().isBlank()) {
      throw new IllegalArgumentException("stored plan arguments lack '" + field + "'");
    }
    return v.asText();
  }
}
