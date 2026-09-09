package com.jaspersoft.jrsctl.jrs.api;

import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import java.nio.file.Path;
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
    Optional<SecretRef> sourceKeystorePassword) {}
