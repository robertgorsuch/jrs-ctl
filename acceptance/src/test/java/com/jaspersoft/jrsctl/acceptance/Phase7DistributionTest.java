package com.jaspersoft.jrsctl.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec §14 Phase 7: jlink image, portable archive, checksums, launcher works with no JDK on the
 * host. The unit under test is {@code dist/target/image/<platform>/} and the archive next to it,
 * produced by {@code -Pdist}; every launcher run uses a scrubbed environment (no {@code JAVA_HOME},
 * {@code PATH} reduced to the system directory) so nothing but the bundled runtime can be found.
 * The suite is skipped, with build instructions, when the image was not built.
 */
@Tag("phase7")
class Phase7DistributionTest {

  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  private static final String PLATFORM = WINDOWS ? "windows-x64" : "linux-x64";
  private static final String ARCHIVE_EXT = WINDOWS ? "zip" : "tar.gz";
  private static final String VERSION = System.getProperty("jrsctl.version");

  private final Path distTarget = repoRoot().resolve("dist/target");
  private final Path image = distTarget.resolve("image").resolve(PLATFORM);
  private final Path archive =
      distTarget.resolve("jrsctl-" + VERSION + "-" + PLATFORM + "." + ARCHIVE_EXT);

  private void assumeImageBuilt() {
    Assumptions.assumeTrue(
        Files.isDirectory(image.resolve("runtime")) && Files.isRegularFile(archive),
        () ->
            "Phase 7 image not built at "
                + image
                + "; run scripts/build-dist.cmd (or scripts/mvn.sh -Pdist -DskipTests package)"
                + " first, or run the acceptance with -Pdist");
  }

  /**
   * Review finding 3.6: the {@code dist-linux} profile used to activate on anything that is not
   * Windows, so a macOS or ARM64 builder produced an archive labelled {@code linux-x64} carrying
   * that host's runtime. Each profile now names the operating system and the architecture, and the
   * packaging build refuses a host that matches neither. No image needed: this reads the pom.
   */
  @Test
  void dist_profiles_activate_only_on_the_two_hosts_adr_0002_supports() throws Exception {
    String pom = Files.readString(repoRoot().resolve("dist/pom.xml"), StandardCharsets.UTF_8);

    assertThat(pom)
        .as("a !windows activation labels a macOS or ARM build linux-x64")
        .doesNotContain("<family>!windows</family>");
    assertThat(pom).contains("<os><family>windows</family><arch>amd64</arch></os>");
    assertThat(pom).contains("<os><name>Linux</name><arch>amd64</arch></os>");
    assertThat(pom)
        .as("the packaging build must refuse a host that activated neither profile")
        .contains("<property>dist.platform</property>")
        .contains("(windows|linux)-x64");
  }

  @Test
  void manifest_lists_every_file_with_a_matching_sha256() throws Exception {
    assumeImageBuilt();
    Path manifest = image.resolve("MANIFEST.sha256");
    assertThat(manifest).exists();

    Map<String, String> listed = new LinkedHashMap<>();
    for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      assertThat(line).as("manifest line format").matches("[0-9a-f]{64}  \\S.*");
      listed.put(line.substring(66), line.substring(0, 64));
    }
    assertThat(listed).isNotEmpty().doesNotContainKey("MANIFEST.sha256");

    TreeSet<String> onDisk = new TreeSet<>();
    try (Stream<Path> walk = Files.walk(image)) {
      walk.filter(Files::isRegularFile)
          .map(p -> image.relativize(p).toString().replace('\\', '/'))
          .filter(p -> !p.equals("MANIFEST.sha256"))
          .forEach(onDisk::add);
    }
    assertThat(listed.keySet()).as("every image file is listed and nothing else").isEqualTo(onDisk);
    assertThat(new ArrayList<>(listed.keySet())).as("sorted by path").isSorted();
    for (Map.Entry<String, String> e : listed.entrySet()) {
      assertThat(sha256(image.resolve(e.getKey()))).as(e.getKey()).isEqualTo(e.getValue());
    }
    assertThat(listed)
        .containsKeys("lib/jrsctl.jar", "bin/jrsctl", "bin/jrsctl.cmd", "README.txt")
        .containsKeys("LICENSE", "LICENSE-THIRD-PARTY.txt");
    assertThat(Files.readString(image.resolve("README.txt"))).contains("Actian Jaspersoft");
    assertThat(Files.readString(image.resolve("LICENSE")))
        .as("the image carries the product's own licence text, byte for byte (ADR-0010)")
        .isEqualTo(Files.readString(repoRoot().resolve("LICENSE")));
  }

  @Test
  void launcher_prints_version_and_passes_selfcheck_without_a_jdk(@TempDir Path tmp)
      throws Exception {
    assumeImageBuilt();
    Launch r = launch(image, tmp, "--version");
    assertThat(r.exit()).as("--version\n%s\n%s", r.stdout(), r.stderr()).isZero();
    assertThat(r.stdout()).contains("jrsctl " + VERSION).contains("Actian Jaspersoft");

    r = launch(image, tmp, "selfcheck");
    assertThat(r.exit()).as("selfcheck\n%s\n%s", r.stdout(), r.stderr()).isZero();
    assertThat(r.stdout()).contains("PASS").contains("selfcheck ok").doesNotContain("FAIL");

    r = launch(image, tmp, "runs", "list");
    assertThat(r.exit())
        .as("runs list (SQLite native library)\n%s\n%s", r.stdout(), r.stderr())
        .isZero();
  }

  @Test
  void archive_matches_its_checksum_and_runs_after_unpacking(@TempDir Path tmp) throws Exception {
    assumeImageBuilt();
    Path sidecar = archive.resolveSibling(archive.getFileName() + ".sha256");
    assertThat(sidecar).exists();
    String line = Files.readString(sidecar, StandardCharsets.UTF_8).strip();
    assertThat(line).isEqualTo(sha256(archive) + "  " + archive.getFileName());
    assertThat(distTarget.resolve("jrsctl-" + VERSION + "-sbom.json")).exists();

    Path unpacked = tmp.resolve("unpacked");
    Files.createDirectories(unpacked);
    if (WINDOWS) {
      unzip(archive, unpacked);
    } else {
      Launch tar = run(List.of("tar", "-xzf", archive.toString(), "-C", unpacked.toString()), null);
      assertThat(tar.exit()).as("tar\n%s", tar.stderr()).isZero();
    }
    List<Path> roots;
    try (Stream<Path> list = Files.list(unpacked)) {
      roots = list.toList();
    }
    assertThat(roots).hasSize(1);
    Path root = roots.get(0);
    assertThat(root.getFileName().toString()).isEqualTo("jrsctl-" + VERSION);
    assertThat(root.resolve("MANIFEST.sha256")).exists();

    Launch r = launch(root, tmp.resolve("home2"), "--version");
    assertThat(r.exit()).as("--version from archive\n%s\n%s", r.stdout(), r.stderr()).isZero();
    assertThat(r.stdout()).contains("jrsctl " + VERSION).contains("Actian Jaspersoft");
  }

  record Launch(int exit, String stdout, String stderr) {}

  /** Runs the launcher of {@code root} with a scrubbed environment and {@code home} as home. */
  private static Launch launch(Path root, Path home, String... args) throws Exception {
    Files.createDirectories(home);
    List<String> cmd = new ArrayList<>();
    if (WINDOWS) {
      cmd.add(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString());
      cmd.add("/c");
      cmd.add(root.resolve("bin/jrsctl.cmd").toString());
    } else {
      cmd.add(root.resolve("bin/jrsctl").toString());
    }
    cmd.addAll(List.of(args));
    return run(cmd, home);
  }

  private static Launch run(List<String> cmd, Path home) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd);
    if (home != null) {
      Map<String, String> env = pb.environment();
      env.clear();
      env.put("JRSCTL_HOME", home.toString());
      if (WINDOWS) {
        String systemRoot = System.getenv("SystemRoot");
        env.put("SystemRoot", systemRoot);
        env.put("PATH", systemRoot + "\\System32;" + systemRoot);
        env.put("TEMP", home.toString());
        env.put("TMP", home.toString());
      } else {
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", home.toString());
      }
    }
    Path out = Files.createTempFile("jrsctl-dist-out", ".txt");
    Path err = Files.createTempFile("jrsctl-dist-err", ".txt");
    pb.redirectOutput(out.toFile());
    pb.redirectError(err.toFile());
    Process p = pb.start();
    if (!p.waitFor(120, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("did not exit within 120s: " + cmd);
    }
    Launch l =
        new Launch(
            p.exitValue(),
            Files.readString(out, StandardCharsets.UTF_8),
            Files.readString(err, StandardCharsets.UTF_8));
    Files.deleteIfExists(out);
    Files.deleteIfExists(err);
    return l;
  }

  private static Path repoRoot() {
    return Path.of(System.getProperty("jrsctl.acceptanceDir")).getParent();
  }

  private static void unzip(Path zip, Path dest) throws IOException {
    Path base = dest.toAbsolutePath().normalize();
    try (InputStream is = Files.newInputStream(zip);
        ZipInputStream zin = new ZipInputStream(is)) {
      for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
        Path target = base.resolve(e.getName()).normalize();
        assertThat(target.startsWith(base)).as("zip entry stays inside dest").isTrue();
        if (e.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        Files.copy(zin, target);
      }
    }
  }

  private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[65536];
    try (InputStream in = Files.newInputStream(file)) {
      for (int n = in.read(buf); n > 0; n = in.read(buf)) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }
}
