package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryPermission;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes small secret files (private keys) that only their owner may read (spec §5.2). Invariants:
 * the file is created with {@code CREATE_NEW}, so an existing key is never overwritten; the
 * permission set is applied through the platform's {@link FileOps} in the same serialised form it
 * captures and restores, which is what {@link FileOps#isOwnerOnly} later checks when the file is
 * used as a {@code file:} secret reference; the content is never logged.
 */
final class OwnerOnlyFiles {

  private static final String WINDOWS_FULL_CONTROL =
      Arrays.stream(AclEntryPermission.values()).map(Enum::name).collect(Collectors.joining(","));

  private OwnerOnlyFiles() {}

  static void write(Platform platform, Path file, String content) throws IOException {
    Path abs = file.toAbsolutePath().normalize();
    Path parent = abs.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(
        abs,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    restrictToOwner(platform, abs);
  }

  static void restrictToOwner(Platform platform, Path file) throws IOException {
    FileOps files = platform.files();
    String owner = files.capturePermissions(file).owner();
    List<String> entries =
        switch (platform.os()) {
          case WINDOWS ->
              owner.isEmpty()
                  ? List.of()
                  : List.of("ALLOW|" + owner + "|" + WINDOWS_FULL_CONTROL + "|");
          case LINUX -> List.of("posix:rw-------");
        };
    if (entries.isEmpty()) {
      throw new IOException("cannot determine the owner of " + file + " to restrict it");
    }
    files.applyPermissions(file, new FileOps.Permissions(owner, entries));
  }
}
