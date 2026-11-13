package com.jaspersoft.jrsctl.jrs.vendor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * The import/export class-path jar buildomatic's Ant targets run with (#315, ADR-0054): {@code
 * conf_source/iePro/lib/iecp.jar} (or {@code ieCe}), a manifest-only jar whose {@code Class-Path}
 * names every jar in its {@code lib} directory. {@code bin/import-export.xml}'s {@code
 * make-ie-classpath-jar} builds it only when it does not exist, so after a hotfix replaces jars in
 * {@code lib} the old one names files that are gone and every Ant import ({@code import-minimal},
 * the upgrade scripts' imports) fails with {@code NoClassDefFoundError}. Invariants: read-only;
 * only the manifest is read, streamed; a jar without a readable manifest is never reported stale
 * (it cannot be judged); the {@code js-export} and {@code js-import} wrappers do not use this jar.
 */
public final class IeClasspathJar {

  public static final String NAME = "iecp.jar";
  private static final List<String> EDITIONS = List.of("iePro", "ieCe");
  private static final int EXAMPLES = 3;

  /** A jar whose class path names {@code missing} of its {@code entries} files. */
  public record Stale(Path jar, int entries, List<String> missing) {
    public Stale {
      Objects.requireNonNull(jar, "jar");
      missing = List.copyOf(missing);
    }

    /** One sentence naming the jar, the count and the first few missing files. */
    public String detail() {
      return jar
          + " is stale: "
          + missing.size()
          + " of its "
          + entries
          + " class-path entries are no longer in "
          + jar.getParent()
          + " (for example "
          + String.join(", ", missing.subList(0, Math.min(EXAMPLES, missing.size())))
          + "), so buildomatic's import targets would fail with NoClassDefFoundError";
    }

    /** What to do: delete it, and buildomatic rebuilds it on its next import. */
    public String remediation() {
      return "delete "
          + jar
          + "; buildomatic rebuilds it from the jars now in "
          + jar.getParent()
          + " on its next import (make-ie-classpath-jar in bin/import-export.xml). A hotfix that"
          + " replaced jars there leaves the old one behind";
    }
  }

  private IeClasspathJar() {}

  /** The first stale class-path jar under {@code buildomaticDir}, if any. */
  public static Optional<Stale> stale(Path buildomaticDir) {
    Objects.requireNonNull(buildomaticDir, "buildomaticDir");
    for (String edition : EDITIONS) {
      Path jar =
          buildomaticDir.resolve("conf_source").resolve(edition).resolve("lib").resolve(NAME);
      if (Files.isRegularFile(jar)) {
        Optional<Stale> stale = check(jar);
        if (stale.isPresent()) {
          return stale;
        }
      }
    }
    return Optional.empty();
  }

  static Optional<Stale> check(Path jar) {
    Manifest manifest;
    try (InputStream in = Files.newInputStream(jar);
        JarInputStream j = new JarInputStream(in)) {
      manifest = j.getManifest();
    } catch (IOException | SecurityException e) {
      return Optional.empty();
    }
    if (manifest == null) {
      return Optional.empty();
    }
    String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
    if (classPath == null || classPath.isBlank()) {
      return Optional.empty();
    }
    Path dir = jar.getParent();
    List<String> missing = new ArrayList<>();
    int entries = 0;
    for (String entry : classPath.trim().split("\\s+", -1)) {
      entries++;
      Optional<Path> file = resolve(dir, entry);
      if (file.isEmpty() || !Files.exists(file.get())) {
        missing.add(entry);
      }
    }
    return missing.isEmpty() ? Optional.empty() : Optional.of(new Stale(jar, entries, missing));
  }

  /** A {@code Class-Path} entry is a relative URL: percent-decoded, resolved against the jar. */
  private static Optional<Path> resolve(Path dir, String entry) {
    try {
      String path = URI.create(entry).getPath();
      return Optional.ofNullable(path).map(dir::resolve);
    } catch (IllegalArgumentException e) { // also InvalidPathException
      return Optional.empty();
    }
  }
}
