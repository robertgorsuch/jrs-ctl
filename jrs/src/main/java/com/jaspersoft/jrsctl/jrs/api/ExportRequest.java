package com.jaspersoft.jrsctl.jrs.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Unified export request (spec §9.1). {@code stopService} says whether the vendor strategy stops
 * the service around {@code js-export} (#67, ADR-0021); the REST strategy never stops it. The
 * constructor without it keeps the behaviour every caller had before the flag existed: stop.
 */
public record ExportRequest(
    Scope scope,
    Set<String> uris,
    boolean includeUsersRoles,
    boolean includeAccessEvents,
    boolean includeAuditEvents,
    boolean includeMonitoring,
    boolean includeSettings,
    boolean fullServer,
    Path output,
    boolean stopService,
    Optional<String> keyAlias) {

  /** The alias {@code export --portable} names: every keystore since 7.5 holds it. */
  public static final String PORTABLE_KEY_ALIAS = "deprecatedImportExportEncSecret";

  public enum Scope {
    REPOSITORY,
    EVERYTHING
  }

  public ExportRequest {
    uris = Set.copyOf(uris);
    Objects.requireNonNull(keyAlias, "keyAlias");
  }

  public ExportRequest(
      Scope scope,
      Set<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer,
      Path output,
      boolean stopService) {
    this(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        stopService,
        Optional.empty());
  }

  /** The same request encrypted with {@code alias} instead of the server's own key. */
  public ExportRequest withKeyAlias(String alias) {
    return new ExportRequest(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        stopService,
        Optional.of(alias));
  }

  /** As the canonical constructor with {@code stopService} true, the behaviour before #67. */
  public ExportRequest(
      Scope scope,
      Set<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer,
      Path output) {
    this(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        true);
  }
}
