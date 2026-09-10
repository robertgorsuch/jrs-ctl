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
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileOps} for Windows. Invariants: permissions are the file's owner plus every DACL entry
 * serialised as {@code TYPE|principal|perm,perm|flag,flag}; a file is locked when it cannot be
 * opened for writing or cannot be renamed within its own directory, which is exactly what a jar
 * held open by Tomcat (opened through {@code java.io} without {@code FILE_SHARE_DELETE}) looks
 * like; {@link #isOwnerOnly} accepts ALLOW entries only for the owner, {@code NT AUTHORITY\SYSTEM}
 * and {@code BUILTIN\Administrators}. Lock holders are only reported when a running Tomcat's
 * command line references an ancestor of the file; Windows offers no cheap general lookup.
 *
 * <p>Windows has no way to ask whether a file may be renamed without renaming it: the only open
 * flag that requests DELETE access, {@code DELETE_ON_CLOSE}, also takes it, and a hard link is
 * checked against its own directory entry rather than the one the holder opened. So the probe
 * renames, and everything around that rename exists to make the window survivable. A hard link
 * beside the file gives its bytes a second name before anything moves; a rename that is refused
 * answers the question with nothing moved at all, which is the case on a live server; a rename that
 * succeeds is undone at once, from the guard link if the probe name cannot be moved back; and a
 * restore that fails every way throws rather than reporting "not locked" over a missing jar.
 * Whatever a crash leaves behind is named after the file it came from, and {@link #isLocked} puts
 * it back before it does anything else.
 */
public final class WindowsFileOps extends DefaultFileOps {

  private static final Logger LOG = LoggerFactory.getLogger(WindowsFileOps.class);
  private static final String LOCK_PROBE_SUFFIX = ".jrsctl-lockprobe";
  private static final String LOCK_GUARD_SUFFIX = ".jrsctl-lockguard";
  private static final int RENAME_BACK_ATTEMPTS = 20;
  private static final long RENAME_BACK_DELAY_MS = 50;
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
    healLeftoverProbe(file);
    if (!Files.isRegularFile(file)) {
      return false;
    }
    try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.WRITE)) {
      // opened for write: nobody denies write sharing
    } catch (IOException e) {
      LOG.debug("{} refuses write access: {}", file, e.toString());
      return true;
    }
    return deniesRename(file);
  }

  /**
   * True when the file cannot be renamed inside its own directory. The rename is only attempted
   * after a hard link has given the bytes a second name, so the only path that leaves the file's
   * own name unused is a crash between the two renames, and {@link #healLeftoverProbe} closes that
   * on the next call.
   *
   * @throws IllegalStateException when the file was moved aside and could not be put back, which
   *     must never be mistaken for "not locked"
   */
  private boolean deniesRename(Path file) {
    Path absolute = file.toAbsolutePath();
    Path probe = absolute.resolveSibling(absolute.getFileName() + LOCK_PROBE_SUFFIX);
    Path guard = absolute.resolveSibling(absolute.getFileName() + LOCK_GUARD_SUFFIX);
    boolean guarded = link(absolute, guard);
    try {
      Files.move(absolute, probe);
    } catch (IOException e) {
      LOG.debug("{} refuses rename: {}", file, e.toString());
      discard(guard);
      return true;
    }
    restore(absolute, probe, guarded ? Optional.of(guard) : Optional.empty());
    return false;
  }

  /** Creates the guard link; false when the volume or the holder will not have one. */
  private static boolean link(Path file, Path guard) {
    try {
      Files.deleteIfExists(guard);
      Files.createLink(guard, file);
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      LOG.debug("no guard link for {}: {}", file, e.toString());
      return false;
    }
  }

  /**
   * Puts the file back under its own name, from the probe name if that can be renamed and from the
   * guard link if it cannot, and removes whichever copy is left over.
   */
  private static void restore(Path file, Path probe, Optional<Path> guard) {
    IOException last = null;
    for (int attempt = 0; attempt < RENAME_BACK_ATTEMPTS; attempt++) {
      try {
        Files.move(probe, file);
        guard.ifPresent(WindowsFileOps::discard);
        return;
      } catch (IOException e) {
        last = e;
        if (!pause()) {
          break;
        }
      }
    }
    if (guard.isPresent()) {
      try {
        Files.move(guard.get(), file);
        LOG.warn("{} was restored from its guard link; removing {}", file, probe);
        discard(probe);
        return;
      } catch (IOException e) {
        last.addSuppressed(e);
      }
    }
    throw new IllegalStateException(
        "lock probe moved " + file + " to " + probe + " and could not put it back", last);
  }

  private static void discard(Path leftover) {
    try {
      Files.deleteIfExists(leftover);
    } catch (IOException e) {
      LOG.debug("cannot remove lock probe leftover {}: {}", leftover, e.toString());
    }
  }

  private static boolean pause() {
    try {
      Thread.sleep(RENAME_BACK_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Undoes a probe that a crash interrupted. The file's bytes are always still there under the
   * probe or the guard name, so the repair is a rename and nothing is lost; the other name, if it
   * survived too, is the same data and goes. This runs before every lock check, so no caller ever
   * sees the gap.
   */
  private static void healLeftoverProbe(Path file) {
    if (Files.exists(file)) {
      return;
    }
    Path absolute = file.toAbsolutePath();
    List<Path> leftovers =
        Stream.of(LOCK_PROBE_SUFFIX, LOCK_GUARD_SUFFIX)
            .map(suffix -> absolute.resolveSibling(absolute.getFileName() + suffix))
            .toList();
    for (Path leftover : leftovers) {
      if (!Files.isRegularFile(leftover)) {
        continue;
      }
      try {
        Files.move(leftover, absolute);
        LOG.warn("restored {} from {} left by an interrupted lock probe", file, leftover);
        leftovers.forEach(WindowsFileOps::discard);
        return;
      } catch (IOException e) {
        LOG.error("cannot restore {} from {}", file, leftover, e);
      }
    }
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
