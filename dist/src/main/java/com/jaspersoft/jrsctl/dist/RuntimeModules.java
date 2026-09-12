package com.jaspersoft.jrsctl.dist;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Build-time helper (never shipped): computes the jlink module list for the shaded jar and writes
 * it as a jlink argument file. Invariants: the result is the union of what {@code jdeps
 * --print-module-deps} reports and the fixed list given on the command line, minus the explicitly
 * excluded modules (ADR-0008 records why each exclusion is safe), sorted and de-duplicated; a stale
 * {@code runtime/} directory from a previous build is removed so that jlink (which refuses an
 * existing output directory) can run again without {@code mvn clean}. jdeps is taken from the JDK
 * running this program.
 *
 * <p>Usage: {@code RuntimeModules <app-jar> <fixed-modules,comma-separated>
 * <excluded-modules,comma-separated> <out-argfile> <runtime-dir>}.
 */
public final class RuntimeModules {

  private RuntimeModules() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length != 5) {
      System.err.println(
          "usage: RuntimeModules <app-jar> <fixed-modules> <excluded-modules> <out-argfile>"
              + " <runtime-dir>");
      System.exit(1);
    }
    String arch = System.getProperty("os.arch", "");
    if (!supportedArch(arch)) {
      System.err.println(
          "the runtime image is built on x86-64 only (ADR-0002); this host is "
              + System.getProperty("os.name", "")
              + " "
              + arch);
      System.exit(1);
    }
    Path jar = Path.of(args[0]);
    List<String> fixed = List.of(args[1].split(","));
    List<String> excluded = List.of(args[2].split(","));
    Path argfile = Path.of(args[3]);
    Path runtimeDir = Path.of(args[4]);

    if (!Files.isRegularFile(jar)) {
      System.err.println("shaded jar not found: " + jar + " (build the app module first)");
      System.exit(1);
    }

    TreeSet<String> modules = new TreeSet<>(fixed);
    Path stripped = Files.createTempFile("jrsctl-jdeps", ".jar");
    try {
      stripModuleInfo(jar, stripped);
      List<String> detected = jdeps(stripped);
      System.out.println("jdeps: " + String.join(",", detected));
      modules.addAll(detected);
    } finally {
      Files.deleteIfExists(stripped);
    }
    modules.removeIf(String::isBlank);
    for (String ex : excluded) {
      if (modules.remove(ex.strip())) {
        System.out.println(
            "excluded: " + ex.strip() + " (ADR-0008: proven unused by the smoke run)");
      }
    }

    Files.createDirectories(argfile.getParent());
    Files.writeString(
        argfile, "--add-modules " + String.join(",", modules) + "\n", StandardCharsets.UTF_8);
    System.out.println("modules: " + String.join(",", modules));

    if (Files.exists(runtimeDir)) {
      System.out.println("removing stale runtime image " + runtimeDir);
      deleteTree(runtimeDir);
    }
  }

  /**
   * The shaded jar carries a stray {@code META-INF/versions/9/module-info.class} from one of the
   * bundled libraries; jdeps would treat the jar as that module and fail to resolve its requires.
   * The copy drops every module descriptor so jdeps analyses the jar as plain class-path code.
   */
  private static void stripModuleInfo(Path in, Path out) throws IOException {
    try (InputStream is = Files.newInputStream(in);
        ZipInputStream zin = new ZipInputStream(is);
        OutputStream os = Files.newOutputStream(out);
        ZipOutputStream zout = new ZipOutputStream(os)) {
      byte[] buf = new byte[65536];
      for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
        if (e.getName().endsWith("module-info.class")) {
          continue;
        }
        zout.putNextEntry(new ZipEntry(e.getName()));
        for (int n = zin.read(buf); n > 0; n = zin.read(buf)) {
          zout.write(buf, 0, n);
        }
        zout.closeEntry();
      }
    }
  }

  private static List<String> jdeps(Path jar) throws IOException, InterruptedException {
    Path javaHome = Path.of(System.getProperty("java.home"));
    Path jdeps = javaHome.resolve("bin").resolve(isWindows() ? "jdeps.exe" : "jdeps");
    List<String> cmd =
        List.of(
            jdeps.toString(),
            "--multi-release",
            "21",
            "--ignore-missing-deps",
            "--print-module-deps",
            jar.toString());
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    List<String> lines;
    try (InputStream is = p.getInputStream()) {
      lines = new String(is.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
    int rc = p.waitFor();
    if (rc != 0) {
      lines.forEach(System.err::println);
      throw new IOException("jdeps exited with " + rc);
    }
    List<String> result = new ArrayList<>();
    for (String line : lines) {
      String l = line.strip();
      if (l.isEmpty() || l.startsWith("Warning") || l.contains(" ")) {
        continue;
      }
      result.addAll(List.of(l.split(",")));
    }
    if (result.isEmpty()) {
      throw new IOException("jdeps printed no module list: " + lines);
    }
    return result;
  }

  private static void deleteTree(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }

  /**
   * True for the one architecture ADR-0002 packages: {@code amd64} / {@code x86_64} (review 3.6).
   */
  static boolean supportedArch(String osArch) {
    String a = osArch.toLowerCase(java.util.Locale.ROOT);
    return a.equals("amd64") || a.equals("x86_64") || a.equals("x64");
  }

  static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
