package com.jaspersoft.jrsctl.ops.upgrade;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Re-packs the extracted bundle copy a hotfix apply run left under {@code runs/<runId>/bundle/}
 * into a ZIP that {@code HotfixOperations.planApply} accepts. Invariants: entries are written in
 * sorted order with forward slashes, so the same directory always yields the same archive; the
 * signature stays valid because it covers {@code manifest.json} bytes, which are copied unchanged;
 * everything streams.
 */
final class BundleZips {

  private static final int BUFFER = 64 * 1024;

  private BundleZips() {}

  static Path zip(Path bundleDir, Path target) throws IOException {
    Path root = bundleDir.toAbsolutePath().normalize();
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(null);
    Files.createDirectories(target.toAbsolutePath().getParent());
    Path part = target.resolveSibling(target.getFileName() + ".part");
    byte[] buffer = new byte[BUFFER];
    try (ZipOutputStream zip =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(part), BUFFER))) {
      for (Path file : files) {
        String name = root.relativize(file).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = Files.newInputStream(file)) {
          int read;
          while ((read = in.read(buffer)) != -1) {
            zip.write(buffer, 0, read);
          }
        }
        zip.closeEntry();
      }
    }
    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    return target;
  }
}
