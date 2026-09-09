package com.jaspersoft.jrsctl.core.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * File-system operations with the guarantees ops code relies on (spec §5.3): atomic replace, lock
 * detection, permission/ACL preservation, streaming hashes. Invariant: nothing here reads a whole
 * file into memory; {@link #sha256} streams.
 */
public interface FileOps {

  /** Hex SHA-256 of the file content, computed while streaming. */
  String sha256(Path file) throws IOException;

  /**
   * Replaces {@code target} with {@code source} atomically (rename within the same volume), keeping
   * the target's owner, permissions and ACLs. {@code source} is consumed.
   */
  void atomicReplace(Path source, Path target) throws IOException;

  /** Copies preserving permissions/ACLs and timestamps, streaming. */
  void copyPreserving(Path source, Path target) throws IOException;

  /** True when another process holds the file in a way that prevents rename/delete. */
  boolean isLocked(Path file);

  /** Best-effort id of the process holding the lock, for the failure message. */
  Optional<String> lockHolder(Path file);

  /** Captures owner/permissions/ACLs so they can be re-applied after a restore. */
  Permissions capturePermissions(Path path) throws IOException;

  void applyPermissions(Path path, Permissions permissions) throws IOException;

  long freeSpaceBytes(Path anyPathOnVolume) throws IOException;

  boolean isWritable(Path dir);

  /** Owner-only check for secret files (0600 on Linux; owner + Administrators only on Windows). */
  boolean isOwnerOnly(Path file) throws IOException;

  /** Opaque, platform-specific serialisable permission set. */
  record Permissions(String owner, List<String> entries) {}
}
