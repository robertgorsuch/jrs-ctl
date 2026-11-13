package com.jaspersoft.jrsctl.core.engine;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the operator sees before confirming (spec §6.2): what changes, whether the server goes down,
 * where the backups are, how to undo, and any plain-language warnings such as "database rollback is
 * the operator's responsibility". Invariant: lists are immutable copies.
 */
public record PlanSummary(
    String operation,
    String target,
    List<Path> filesTouched,
    List<String> resourcesTouched,
    boolean serviceRestart,
    List<Path> backupLocations,
    Map<String, String> rollbackPointsByPhase,
    String strategy,
    List<String> warnings) {

  public PlanSummary {
    filesTouched = List.copyOf(filesTouched);
    resourcesTouched = List.copyOf(resourcesTouched);
    backupLocations = List.copyOf(backupLocations);
    // insertion order is the plan's phase order, which the printed plan keeps (#319 item 4)
    rollbackPointsByPhase = Collections.unmodifiableMap(new LinkedHashMap<>(rollbackPointsByPhase));
    warnings = List.copyOf(warnings);
  }
}
