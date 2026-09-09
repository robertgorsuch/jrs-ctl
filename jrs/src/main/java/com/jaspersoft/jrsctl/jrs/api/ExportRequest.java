package com.jaspersoft.jrsctl.jrs.api;

import java.nio.file.Path;
import java.util.Set;

/** Unified export request (spec §9.1). */
public record ExportRequest(
    Scope scope,
    Set<String> uris,
    boolean includeUsersRoles,
    boolean includeAccessEvents,
    boolean includeAuditEvents,
    boolean includeMonitoring,
    boolean includeSettings,
    boolean fullServer,
    Path output) {

  public enum Scope {
    REPOSITORY,
    EVERYTHING
  }

  public ExportRequest {
    uris = Set.copyOf(uris);
  }
}
