package com.jaspersoft.jrsctl.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Build-time helper (never shipped): finalises the portable image directory after the runtime and
 * the static files were copied in. Invariants: every file an operator needs is present ({@code
 * runtime/bin/java}, {@code lib/jrsctl.jar}, both launchers, README and third-party licence) or the
 * build fails; the POSIX launcher and the runtime's own executables carry the exec bit where the
 * filesystem supports it (so the image runs in place on Linux, not only after unpacking the
 * tar.gz); the POSIX launcher has LF line endings whatever the checkout's autocrlf setting was.
 *
 * <p>Usage: {@code ImageLayout <image-dir>}.
 */
public final class ImageLayout {

  private static final List<String> REQUIRED =
      List.of(
          "lib/jrsctl.jar",
          "bin/jrsctl",
          "bin/jrsctl.cmd",
          "README.txt",
          "LICENSE-THIRD-PARTY.txt",
          "runtime/release");

  private static final List<String> EXECUTABLE =
      List.of("bin/jrsctl", "runtime/lib/jspawnhelper", "runtime/lib/jexec");

  private ImageLayout() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: ImageLayout <image-dir>");
      System.exit(1);
    }
    Path image = Path.of(args[0]).toAbsolutePath().normalize();

    Path java =
        image.resolve("runtime/bin").resolve(RuntimeModules.isWindows() ? "java.exe" : "java");
    for (Path p : List.of(java, image.resolve("runtime/lib/modules"))) {
      require(p);
    }
    for (String rel : REQUIRED) {
      require(image.resolve(rel));
    }

    Path sh = image.resolve("bin/jrsctl");
    String text = Files.readString(sh, StandardCharsets.UTF_8).replace("\r\n", "\n");
    Files.writeString(sh, text, StandardCharsets.UTF_8);
    Path cmd = image.resolve("bin/jrsctl.cmd");
    String cmdText =
        Files.readString(cmd, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n");
    Files.writeString(cmd, cmdText, StandardCharsets.UTF_8);

    if (Files.getFileStore(image).supportsFileAttributeView("posix")) {
      Set<PosixFilePermission> rwxr = EnumSet.allOf(PosixFilePermission.class);
      rwxr.remove(PosixFilePermission.GROUP_WRITE);
      rwxr.remove(PosixFilePermission.OTHERS_WRITE);
      for (String rel : EXECUTABLE) {
        Path p = image.resolve(rel);
        if (Files.isRegularFile(p)) {
          Files.setPosixFilePermissions(p, rwxr);
        }
      }
      try (var bins = Files.list(image.resolve("runtime/bin"))) {
        for (Path p : bins.toList()) {
          if (Files.isRegularFile(p)) {
            Files.setPosixFilePermissions(p, rwxr);
          }
        }
      }
    }
    System.out.println("image layout ok: " + image);
  }

  private static void require(Path p) {
    if (!Files.isRegularFile(p)) {
      System.err.println("image is missing " + p);
      System.exit(1);
    }
  }
}
