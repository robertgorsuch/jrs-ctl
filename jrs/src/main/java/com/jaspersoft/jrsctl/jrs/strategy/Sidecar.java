package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The {@code <archive>.jrsctl.json} sidecar written next to every export (spec §9.3): when it was
 * taken, from which server, with which request flags, the SHA-256 of the archive and the server
 * keystore fingerprint an import must match. Invariants: {@link #pathFor} is the one place the
 * sidecar name is derived, so export and import always agree; the file is written through a temp
 * file and an atomic rename; an unreadable or malformed sidecar surfaces as {@link
 * IllegalArgumentException} for the import precheck to report, never as a silent skip.
 */
public record Sidecar(
    Instant exportedAt,
    String serverIdentity,
    String serverVersion,
    Optional<String> keystoreFingerprint,
    Flags flags,
    String sha256,
    ExportImportStrategy.Kind strategy) {

  public static final String SUFFIX = ".jrsctl.json";

  public Sidecar {
    Objects.requireNonNull(exportedAt, "exportedAt");
    Objects.requireNonNull(serverIdentity, "serverIdentity");
    Objects.requireNonNull(serverVersion, "serverVersion");
    Objects.requireNonNull(keystoreFingerprint, "keystoreFingerprint");
    Objects.requireNonNull(flags, "flags");
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(strategy, "strategy");
  }

  /** The request that produced the archive, minus the output path. */
  public record Flags(
      ExportRequest.Scope scope,
      List<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer) {

    public Flags {
      Objects.requireNonNull(scope, "scope");
      uris = List.copyOf(uris);
    }

    public static Flags of(ExportRequest r) {
      return new Flags(
          r.scope(),
          List.copyOf(new TreeSet<>(r.uris())),
          r.includeUsersRoles(),
          r.includeAccessEvents(),
          r.includeAuditEvents(),
          r.includeMonitoring(),
          r.includeSettings(),
          r.fullServer());
    }
  }

  public static Path pathFor(Path archive) {
    return archive.resolveSibling(archive.getFileName() + SUFFIX);
  }

  public static void write(Path file, Sidecar sidecar) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, Json.writePretty(sidecar), StandardCharsets.UTF_8);
    RunFiles.replace(tmp, file);
  }

  /** Empty when no sidecar exists; throws {@link IllegalArgumentException} when it is malformed. */
  public static Optional<Sidecar> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(Json.read(Files.readString(file, StandardCharsets.UTF_8), Sidecar.class));
  }
}
