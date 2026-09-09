package com.jaspersoft.jrsctl.core.platform;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileOps} for Windows. Invariants: permissions are the file's owner plus every DACL entry
 * serialised as {@code TYPE|principal|perm,perm|flag,flag}; a file is locked when it cannot be
 * opened for writing or cannot be renamed within its own directory, which is exactly what a jar
 * held open by Tomcat (opened without {@code FILE_SHARE_DELETE}) looks like; the rename probe
 * always renames the file back, retrying if necessary; {@link #isOwnerOnly} accepts ALLOW entries
 * only for the owner, {@code NT AUTHORITY\SYSTEM} and {@code BUILTIN\Administrators}. Lock holders
 * are only reported when a running Tomcat's command line references an ancestor of the file;
 * Windows offers no cheap general lookup.
 */
public final class WindowsFileOps extends DefaultFileOps {

  private static final Logger LOG = LoggerFactory.getLogger(WindowsFileOps.class);
  private static final String LOCK_PROBE_SUFFIX = ".jrsctl-lockprobe";
  private static final int RENAME_BACK_ATTEMPTS = 20;
  private static final Pattern PIPE = Pattern.compile("\\|");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final Set<String> SYSTEM_PRINCIPALS =
      Set.of("nt authority\\system", "builtin\\administrators", "system", "administrators");

  private final TomcatProcessFinder processes;

  public WindowsFileOps() {
    this(TomcatProcesses.INSTANCE);
  }

  WindowsFileOps(TomcatProcessFinder processes) {
    this.processes = processes;
  }

  @Override
  public boolean isLocked(Path file) {
    if (!Files.isRegularFile(file)) {
      return false;
    }
    try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.WRITE)) {
      // opened for write: nobody denies write sharing
    } catch (IOException e) {
      LOG.debug("{} refuses write access: {}", file, e.toString());
      return true;
    }
    Path probe = file.toAbsolutePath().resolveSibling(file.getFileName() + LOCK_PROBE_SUFFIX);
    try {
      Files.move(file, probe);
    } catch (IOException e) {
      LOG.debug("{} refuses rename: {}", file, e.toString());
      return true;
    }
    renameBack(probe, file);
    return false;
  }

  private static void renameBack(Path probe, Path original) {
    IOException last = null;
    for (int attempt = 0; attempt < RENAME_BACK_ATTEMPTS; attempt++) {
      try {
        Files.move(probe, original);
        return;
      } catch (IOException e) {
        last = e;
        try {
          Thread.sleep(50);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    LOG.error("lock probe could not rename {} back to {}", probe, original, last);
  }

  @Override
  public Optional<String> lockHolder(Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    for (TomcatProcessFinder.TomcatProcess tomcat : processes.find()) {
      boolean holds =
          tomcat.catalinaHome().map(absolute::startsWith).orElse(false)
              || tomcat.catalinaBase().map(absolute::startsWith).orElse(false);
      if (holds) {
        return Optional.of("pid " + tomcat.pid() + " (Tomcat)");
      }
    }
    return Optional.empty();
  }

  @Override
  public FileOps.Permissions capturePermissions(Path path) throws IOException {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (view == null) {
      return super.capturePermissions(path);
    }
    List<String> entries = new ArrayList<>();
    for (AclEntry entry : view.getAcl()) {
      entries.add(serialise(entry));
    }
    return new FileOps.Permissions(view.getOwner().getName(), List.copyOf(entries));
  }

  @Override
  public void applyPermissions(Path path, FileOps.Permissions permissions) throws IOException {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (view == null) {
      super.applyPermissions(path, permissions);
      return;
    }
    UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
    List<AclEntry> acl = new ArrayList<>();
    for (String serialised : permissions.entries()) {
      Optional<AclEntry> entry = deserialise(serialised, lookup);
      if (entry.isPresent()) {
        acl.add(entry.get());
      } else {
        LOG.warn("dropping unresolvable ACL entry {} while restoring {}", serialised, path);
      }
    }
    if (!acl.isEmpty()) {
      view.setAcl(acl);
    }
    applyOwner(path, permissions.owner());
  }

  @Override
  public boolean isOwnerOnly(Path file) throws IOException {
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    if (view == null) {
      return false;
    }
    String owner = view.getOwner().getName().toLowerCase(Locale.ROOT);
    for (AclEntry entry : view.getAcl()) {
      if (entry.type() != AclEntryType.ALLOW) {
        continue;
      }
      String principal = entry.principal().getName().toLowerCase(Locale.ROOT);
      if (!principal.equals(owner) && !SYSTEM_PRINCIPALS.contains(principal)) {
        return false;
      }
    }
    return true;
  }

  static String serialise(AclEntry entry) {
    return entry.type().name()
        + "|"
        + entry.principal().getName()
        + "|"
        + entry.permissions().stream().map(Enum::name).collect(Collectors.joining(","))
        + "|"
        + entry.flags().stream().map(Enum::name).collect(Collectors.joining(","));
  }

  static Optional<AclEntry> deserialise(String serialised, UserPrincipalLookupService lookup) {
    String[] parts = PIPE.split(serialised, -1);
    if (parts.length != 4) {
      return Optional.empty();
    }
    Optional<UserPrincipal> principal = lookupPrincipal(lookup, parts[1]);
    if (principal.isEmpty()) {
      return Optional.empty();
    }
    AclEntry.Builder builder =
        AclEntry.newBuilder()
            .setType(AclEntryType.valueOf(parts[0]))
            .setPrincipal(principal.get())
            .setPermissions(names(parts[2], AclEntryPermission.class))
            .setFlags(names(parts[3], AclEntryFlag.class));
    return Optional.of(builder.build());
  }

  private static <E extends Enum<E>> Set<E> names(String joined, Class<E> type) {
    Set<E> set = EnumSet.noneOf(type);
    if (joined.isEmpty()) {
      return set;
    }
    for (String name : COMMA.split(joined, -1)) {
      set.add(Enum.valueOf(type, name));
    }
    return set;
  }

  private static Optional<UserPrincipal> lookupPrincipal(
      UserPrincipalLookupService lookup, String name) {
    try {
      return Optional.of(lookup.lookupPrincipalByName(name));
    } catch (IOException e) {
      try {
        return Optional.of(lookup.lookupPrincipalByGroupName(name));
      } catch (IOException e2) {
        LOG.debug("principal {} not found", name, e2);
        return Optional.empty();
      }
    }
  }
}
