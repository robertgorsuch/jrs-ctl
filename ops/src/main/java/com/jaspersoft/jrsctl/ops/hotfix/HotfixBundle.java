package com.jaspersoft.jrsctl.ops.hotfix;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A hotfix bundle unpacked into a directory (spec §8.1): {@code manifest.json}, optional {@code
 * SIGNATURE}, and the {@code payload/}, {@code sql/} and {@code checks/} trees. Invariants: the
 * manifest bytes are kept exactly as they were in the ZIP because the signature covers them; entry
 * names are validated against ZIP-slip (relative, no {@code ..}, no drive letters, no backslashes)
 * before anything is written; entries are streamed, never buffered whole; {@link #files()} lists
 * every regular file except the manifest and the signature, so anything not listed in the manifest
 * is an "unlisted file" for the verifier.
 */
public final class HotfixBundle {

  public static final String MANIFEST = "manifest.json";
  public static final String SIGNATURE = "SIGNATURE";
  public static final String PAYLOAD_DIR = "payload";
  public static final String SQL_DIR = "sql";
  public static final String CHECKS_DIR = "checks";

  /** Manifests larger than this are refused; a manifest is a few kilobytes of JSON. */
  static final long MAX_MANIFEST_BYTES = 4L << 20;

  private static final Pattern ENTRY_NAME = Pattern.compile("[A-Za-z0-9._\\-/]+");
  private static final int BUFFER = 64 * 1024;

  private final Path dir;
  private final byte[] manifestBytes;
  private final Optional<String> signature;
  private final List<String> files;

  HotfixBundle(Path dir, byte[] manifestBytes, Optional<String> signature, List<String> files) {
    this.dir = Objects.requireNonNull(dir, "dir");
    this.manifestBytes = manifestBytes.clone();
    this.signature = Objects.requireNonNull(signature, "signature");
    this.files = List.copyOf(files);
  }

  /** The unpacked directory. */
  public Path dir() {
    return dir;
  }

  /** Exact bytes of {@code manifest.json}; the signature covers them. */
  public byte[] manifestBytes() {
    return manifestBytes.clone();
  }

  /** Base64 text of {@code SIGNATURE}, when the bundle carries one. */
  public Optional<String> signature() {
    return signature;
  }

  /** Every regular file except the manifest and the signature, bundle-relative, sorted. */
  public List<String> files() {
    return files;
  }

  /** Unpacks {@code zip} into {@code dir} (created, must be empty or absent) and opens it. */
  public static HotfixBundle extract(Path zip, Path dir) throws IOException {
    Files.createDirectories(dir);
    boolean manifestSeen = false;
    try (InputStream raw = Files.newInputStream(zip);
        ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory()) {
          continue;
        }
        checkEntryName(name);
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir.toAbsolutePath().normalize())
            && !target.startsWith(dir.normalize())) {
          throw new IOException("ZIP entry escapes the bundle directory: " + name);
        }
        Files.createDirectories(target.getParent());
        long limit = name.equals(MANIFEST) ? MAX_MANIFEST_BYTES : Long.MAX_VALUE;
        copy(in, target, limit, name);
        manifestSeen |= name.equals(MANIFEST);
      }
    } catch (IllegalArgumentException e) {
      throw new IOException("not a readable ZIP: " + e.getMessage(), e);
    }
    if (!manifestSeen) {
      throw new IOException("bundle has no " + MANIFEST);
    }
    return open(dir);
  }

  /** Opens an already unpacked bundle directory. */
  public static HotfixBundle open(Path dir) throws IOException {
    Path root = dir.toAbsolutePath().normalize();
    Path manifest = root.resolve(MANIFEST);
    if (!Files.isRegularFile(manifest)) {
      throw new IOException("bundle has no " + MANIFEST);
    }
    if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
      throw new IOException(MANIFEST + " exceeds " + MAX_MANIFEST_BYTES + " bytes");
    }
    byte[] manifestBytes;
    try (InputStream in = Files.newInputStream(manifest)) {
      manifestBytes = in.readNBytes((int) MAX_MANIFEST_BYTES);
    }
    Path signatureFile = root.resolve(SIGNATURE);
    Optional<String> signature =
        Files.isRegularFile(signatureFile)
            ? Optional.of(Files.readString(signatureFile, StandardCharsets.US_ASCII).strip())
            : Optional.empty();
    List<String> files = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String rel = relative(root, file);
            if (!rel.equals(MANIFEST) && !rel.equals(SIGNATURE)) {
              files.add(rel);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(String::compareTo);
    return new HotfixBundle(root, manifestBytes, signature, files);
  }

  /** The manifest as UTF-8 text. */
  public String manifestJson() {
    return new String(manifestBytes, StandardCharsets.UTF_8);
  }

  /** Absolute path of a bundle-relative entry. */
  public Path file(String entry) {
    return dir.resolve(entry.replace('\\', '/'));
  }

  /** Deletes the unpacked directory; safe to call twice. */
  public void delete() throws IOException {
    deleteRecursively(dir);
  }

  /** Bundle-relative name with forward slashes. */
  static String relative(Path root, Path file) {
    StringBuilder sb = new StringBuilder();
    for (Path element : root.relativize(file)) {
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(element);
    }
    return sb.toString();
  }

  static void checkEntryName(String name) throws IOException {
    if (name.isEmpty()
        || name.startsWith("/")
        || name.contains("\\")
        || name.contains(":")
        || !ENTRY_NAME.matcher(name).matches()) {
      throw new IOException("ZIP entry name is not acceptable: " + name);
    }
    for (String segment : name.split("/", -1)) {
      if (segment.equals("..") || segment.isEmpty()) {
        throw new IOException("ZIP entry name is not acceptable: " + name);
      }
    }
  }

  static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void copy(InputStream in, Path target, long limit, String name)
      throws IOException {
    byte[] buffer = new byte[BUFFER];
    long total = 0;
    try (OutputStream out =
        Files.newOutputStream(
            target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > limit) {
          throw new IOException(name + " exceeds " + limit + " bytes");
        }
        out.write(buffer, 0, read);
      }
    }
  }
}
