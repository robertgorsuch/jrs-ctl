package com.jaspersoft.jrsctl.jrs.api;

import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Unified import request (spec §9.1). */
public record ImportRequest(
    Path archive,
    boolean update,
    boolean skipUserUpdate,
    boolean includeAccessEvents,
    boolean includeAuditEvents,
    boolean includeMonitoring,
    boolean includeSettings,
    boolean skipThemes,
    Optional<Path> sourceKeystore,
    Optional<SecretRef> sourceKeystorePassword,
    BrokenDependencies brokenDependencies) {

  public ImportRequest {
    Objects.requireNonNull(brokenDependencies, "brokenDependencies");
  }

  /** The request with the server's own default for broken dependencies ({@code fail}). */
  public ImportRequest(
      Path archive,
      boolean update,
      boolean skipUserUpdate,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean skipThemes,
      Optional<Path> sourceKeystore,
      Optional<SecretRef> sourceKeystorePassword) {
    this(
        archive,
        update,
        skipUserUpdate,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        skipThemes,
        sourceKeystore,
        sourceKeystorePassword,
        BrokenDependencies.FAIL);
  }
}
