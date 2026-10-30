package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review finding 4: the cross-volume fallback of {@link PointB#moveTree} copies the tree with
 * {@link PointB#copyTree}, and a restored webapp or buildomatic must come back as it was archived:
 * a symbolic link stays a link with the same target, and a file keeps its POSIX permissions (a
 * buildomatic {@code js-ant} that loses its execute bit cannot run).
 */
class PointBCopyTreeTest {

  @TempDir Path tmp;

  /** Windows needs developer mode or elevation to create a link; skip rather than fail there. */
  private static void link(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      assumeTrue(false, "cannot create a symbolic link here: " + e.getMessage());
    }
  }

  @Test
  void should_copy_a_link_to_a_file_as_a_link_when_copying_a_tree() throws Exception {
    Path source = tmp.resolve("src");
    Path lib = source.resolve("WEB-INF").resolve("lib");
    Files.createDirectories(lib);
    Files.writeString(lib.resolve("real.jar"), "jar", StandardCharsets.UTF_8);
    link(lib.resolve("linked.jar"), Path.of("real.jar"));
    Path target = tmp.resolve("dst");

    PointB.copyTree(source, target);

    Path linked = target.resolve("WEB-INF").resolve("lib").resolve("linked.jar");
    assertThat(Files.isSymbolicLink(linked)).as("still a link").isTrue();
    assertThat(Files.readSymbolicLink(linked)).isEqualTo(Path.of("real.jar"));
  }

  @Test
  void should_copy_a_link_to_a_directory_as_a_link_when_copying_a_tree() throws Exception {
    Path source = tmp.resolve("src");
    Files.createDirectories(source.resolve("WEB-INF").resolve("lib"));
    Files.writeString(
        source.resolve("WEB-INF").resolve("lib").resolve("real.jar"),
        "jar",
        StandardCharsets.UTF_8);
    link(source.resolve("shortcut"), Path.of("WEB-INF", "lib"));
    Path target = tmp.resolve("dst");

    PointB.copyTree(source, target);

    Path shortcut = target.resolve("shortcut");
    assertThat(Files.isSymbolicLink(shortcut)).as("still a link").isTrue();
    assertThat(Files.readSymbolicLink(shortcut)).isEqualTo(Path.of("WEB-INF", "lib"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_keep_posix_permissions_when_copying_a_tree() throws Exception {
    Path source = tmp.resolve("src");
    Path bin = source.resolve("bin");
    Files.createDirectories(bin);
    Path script = bin.resolve("js-ant");
    Files.writeString(script, "#!/bin/sh\n", StandardCharsets.UTF_8);
    Set<PosixFilePermission> executable = PosixFilePermissions.fromString("rwxr-x---");
    Files.setPosixFilePermissions(script, executable);
    Set<PosixFilePermission> privateDir = PosixFilePermissions.fromString("rwx------");
    Files.setPosixFilePermissions(bin, privateDir);
    Path target = tmp.resolve("dst");

    PointB.copyTree(source, target);

    assertThat(Files.getPosixFilePermissions(target.resolve("bin").resolve("js-ant")))
        .isEqualTo(executable);
    assertThat(Files.getPosixFilePermissions(target.resolve("bin"))).isEqualTo(privateDir);
  }
}
