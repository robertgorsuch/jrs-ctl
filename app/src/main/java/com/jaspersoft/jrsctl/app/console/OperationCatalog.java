package com.jaspersoft.jrsctl.app.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.engine.Plan;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The one table that maps a console operation name ({@code POST /api/plan} {@code op}) to the code
 * that plans it (spec §13.1, {@code web/README.md}). Invariants: the names are exactly the six the
 * front-end offers; {@code hotfix.verify} is planned here as a read-only verification plan because
 * the hotfix subsystem exposes it as a report, every other name goes through the {@link
 * PlanBuilder} the CLI uses, so a plan built for the console is byte-for-byte the plan {@code runs
 * recover} would rebuild; a name outside the table is a 400, a name the build does not implement
 * yet is a 501; the JSON argument document stored with the plan is what {@link #rebuild} reads.
 */
public final class OperationCatalog {

  public static final String HOTFIX_APPLY = "hotfix.apply";
  public static final String HOTFIX_ROLLBACK = "hotfix.rollback";
  public static final String HOTFIX_VERIFY = "hotfix.verify";
  public static final String EXPORT = "export";
  public static final String IMPORT = "import";
  public static final String UPGRADE = "upgrade";

  /** Every operation the console form can ask for, in menu order. */
  public static final List<String> OPERATIONS =
      List.of(HOTFIX_APPLY, HOTFIX_ROLLBACK, HOTFIX_VERIFY, EXPORT, IMPORT, UPGRADE);

  private final PlanBuilder registry;
  private final Supplier<HotfixOperations> hotfix;

  public OperationCatalog(PlanBuilder registry, Supplier<HotfixOperations> hotfix) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.hotfix = Objects.requireNonNull(hotfix, "hotfix");
  }

  /** True when {@code operation} names something the front-end may ask for. */
  public static boolean known(String operation) {
    return OPERATIONS.contains(operation);
  }

  /**
   * Plans {@code operation} from the request's {@code args}; returns the plan and its stored args.
   */
  public Planned plan(String operation, JsonNode args) {
    if (!known(operation)) {
      throw new IllegalArgumentException(
          "unknown operation '" + operation + "'; one of " + String.join(", ", OPERATIONS));
    }
    ObjectNode normalised = args.isObject() ? (ObjectNode) args : Json.mapper().createObjectNode();
    String argsJson = Json.write(normalised);
    return new Planned(rebuild(operation, argsJson), argsJson);
  }

  /** Rebuilds the plan of a stored operation from its argument document. */
  public Plan rebuild(String operation, String argsJson) {
    if (HOTFIX_VERIFY.equals(operation)) {
      JsonNode args = parse(argsJson);
      JsonNode bundle = args.get("bundle");
      if (bundle == null || !bundle.isTextual() || bundle.asText().isBlank()) {
        throw new IllegalArgumentException("hotfix.verify needs args.bundle");
      }
      return VerifyPlan.build(hotfix.get(), Path.of(bundle.asText()));
    }
    return registry.build(operation, argsJson);
  }

  /** The hotfix id a {@code hotfix.rollback} plan is built for, as stored args. */
  public static String rollbackArgs(String hotfixId) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("id", hotfixId);
    node.put("cascade", false);
    return Json.write(node);
  }

  private static JsonNode parse(String argsJson) {
    try {
      return Json.mapper().readTree(argsJson);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("plan arguments are not JSON", e);
    }
  }

  /** A freshly built plan together with the argument document to store beside it. */
  public record Planned(Plan plan, String argsJson) {}
}
