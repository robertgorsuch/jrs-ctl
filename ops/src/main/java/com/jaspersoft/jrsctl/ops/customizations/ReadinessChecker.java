package com.jaspersoft.jrsctl.ops.customizations;

import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessFinding;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessKind;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the readiness rules over a site's files (issue #306, ADR-0061). Invariants: read-only; every
 * file and every jar is read as a stream, a jar inside a war as a stream inside a stream, and
 * nothing is unpacked; a class the site defines itself is never reported as missing from the
 * target; a file that cannot be read is reported as {@link ReadinessKind#UNREADABLE}, never skipped
 * silently; findings come out in file order, one per file and kind, with sorted references.
 */
final class ReadinessChecker {

  /** One site file to check, named as the report names it. */
  record SiteFile(String name, Path path) {}

  /** What one site file holds, gathered before the target is consulted. */
  private static final class Gathered {
    final Set<String> classRefs = new TreeSet<>();
    final Set<String> textJakarta = new TreeSet<>();
    final Set<String> namespaces = new TreeSet<>();
    int maxMajor;
    final List<String> unreadable = new ArrayList<>();
  }

  /** A jar's Maven build metadata ({@code pom.xml}, {@code pom.properties}); never read. */
  static final String MAVEN_METADATA = "META-INF/maven/";

  private final Map<String, Gathered> files = new LinkedHashMap<>();
  private final Set<String> defined = new HashSet<>();
  private int classes;

  int classesChecked() {
    return classes;
  }

  int filesChecked() {
    return files.size();
  }

  /** Reads one site file: a class, a jar, a directory of classes, or a text file. */
  void check(SiteFile file) {
    Gathered g = files.computeIfAbsent(file.name(), k -> new Gathered());
    String lower = file.path().getFileName().toString().toLowerCase(Locale.ROOT);
    try {
      if (Files.isDirectory(file.path())) {
        try (Stream<Path> walk = Files.walk(file.path())) {
          for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
            String n = p.getFileName().toString();
            if (n.endsWith(".class")) {
              classFile(g, p.toString(), Files.newInputStream(p));
            } else if (ReadinessRules.text(n)) {
              text(g, Files.newInputStream(p));
            }
          }
        }
      } else if (lower.endsWith(".class")) {
        classFile(g, file.name(), Files.newInputStream(file.path()));
      } else if (lower.endsWith(".jar")) {
        try (InputStream in = Files.newInputStream(file.path())) {
          jar(g, in);
        }
      } else if (ReadinessRules.text(lower)) {
        text(g, Files.newInputStream(file.path()));
      }
    } catch (IOException e) {
      g.unreadable.add(file.name() + ": " + e.getMessage());
    }
  }

  private void classFile(Gathered g, String name, InputStream stream) {
    try (InputStream in = stream) {
      record(g, ClassFileReferences.read(in));
    } catch (IOException e) {
      g.unreadable.add(name + ": " + e.getMessage());
    }
  }

  private void record(Gathered g, ClassFileReferences refs) {
    classes++;
    defined.add(refs.thisClass());
    g.classRefs.addAll(refs.referenced());
    g.maxMajor = Math.max(g.maxMajor, refs.majorVersion());
  }

  private void jar(Gathered g, InputStream in) throws IOException {
    ZipInputStream zip = new ZipInputStream(new NonClosing(in));
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      if (entry.isDirectory()) {
        continue;
      }
      String n = entry.getName();
      if (n.startsWith(MAVEN_METADATA)) {
        continue; // build metadata: a pom names javax.* properties and comments, not code
      }
      if (n.endsWith(".class")
          && !n.startsWith("META-INF/versions/")
          && !n.endsWith("module-info.class")) {
        try {
          record(g, ClassFileReferences.read(zip));
        } catch (IOException e) {
          g.unreadable.add(n + ": " + e.getMessage());
        }
      } else if (ReadinessRules.text(n)) {
        text(g, new NonClosing(zip));
      }
    }
  }

  private static void text(Gathered g, InputStream stream) throws IOException {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.contains("javax.")) {
          Matcher m = ReadinessRules.JAKARTA_TEXT.matcher(line);
          while (m.find()) {
            g.textJakarta.add(trimDot(m.group()));
          }
        }
        if (line.contains("/xml/ns/")) {
          Matcher m = ReadinessRules.JAVA_EE_NAMESPACE.matcher(line);
          while (m.find()) {
            g.namespaces.add(m.group());
          }
        }
      }
    }
  }

  private static String trimDot(String s) {
    return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * The findings, consulting {@code target} (the internal names of the classes the target's webapp
   * holds under the library roots and the moved {@code javax} packages) when present.
   */
  List<ReadinessFinding> findings(Optional<Set<String>> target) {
    List<ReadinessFinding> out = new ArrayList<>();
    for (Map.Entry<String, Gathered> e : files.entrySet()) {
      String file = e.getKey();
      Gathered g = e.getValue();
      Set<String> jakarta = new TreeSet<>();
      Set<String> shipped = new TreeSet<>();
      Set<String> missing = new TreeSet<>();
      for (String ref : g.classRefs) {
        if (ReadinessRules.jakarta(ref)) {
          (target.isPresent() && target.get().contains(ref) ? shipped : jakarta).add(dotted(ref));
        } else if (target.isPresent()
            && ReadinessRules.library(ref)
            && !defined.contains(ref)
            && !target.get().contains(ref)) {
          missing.add(dotted(ref));
        }
      }
      for (String name : g.textJakarta) {
        String internal = name.replace('.', '/');
        (target.isPresent() && target.get().contains(internal) ? shipped : jakarta).add(name);
      }
      add(out, file, ReadinessKind.JAKARTA, jakarta);
      add(out, file, ReadinessKind.JAKARTA_SHIPPED, shipped);
      add(out, file, ReadinessKind.NAMESPACE, g.namespaces);
      add(out, file, ReadinessKind.NOT_IN_TARGET, missing);
      if (g.maxMajor > ReadinessRules.JAVA_17_CLASS_VERSION) {
        add(
            out,
            file,
            ReadinessKind.BYTECODE,
            Set.of(
                "Java "
                    + ReadinessRules.javaRelease(g.maxMajor).map(String::valueOf).orElse("?")
                    + " (class file version "
                    + g.maxMajor
                    + ")"));
      }
      add(out, file, ReadinessKind.UNREADABLE, new TreeSet<>(g.unreadable));
    }
    return out;
  }

  private static void add(
      List<ReadinessFinding> out, String file, ReadinessKind kind, Set<String> refs) {
    if (!refs.isEmpty()) {
      out.add(new ReadinessFinding(file, kind, List.copyOf(refs)));
    }
  }

  private static String dotted(String internal) {
    return internal.replace('/', '.');
  }

  /**
   * The classes the target's webapp holds under the library roots and the moved {@code javax}
   * packages: {@code WEB-INF/classes} and every {@code WEB-INF/lib} jar, from a directory or
   * straight out of the war.
   */
  static Set<String> targetIndex(Path webapp) throws IOException {
    Set<String> index = new HashSet<>();
    if (Files.isDirectory(webapp)) {
      Path classes = webapp.resolve("WEB-INF").resolve("classes");
      if (Files.isDirectory(classes)) {
        try (Stream<Path> walk = Files.walk(classes)) {
          walk.filter(p -> p.getFileName().toString().endsWith(".class"))
              .forEach(p -> keep(index, classes.relativize(p).toString().replace('\\', '/')));
        }
      }
      Path lib = webapp.resolve("WEB-INF").resolve("lib");
      if (Files.isDirectory(lib)) {
        try (Stream<Path> jars = Files.list(lib)) {
          for (Path jar :
              (Iterable<Path>) jars.filter(p -> p.toString().endsWith(".jar"))::iterator) {
            try (InputStream in = Files.newInputStream(jar)) {
              jarIndex(index, in);
            }
          }
        }
      }
      return index;
    }
    try (InputStream in = Files.newInputStream(webapp);
        ZipInputStream war = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = war.getNextEntry()) != null) {
        String n = entry.getName();
        if (n.startsWith("WEB-INF/classes/") && n.endsWith(".class")) {
          keep(index, n.substring("WEB-INF/classes/".length()));
        } else if (n.startsWith("WEB-INF/lib/") && n.endsWith(".jar")) {
          jarIndex(index, war);
        }
      }
    }
    return index;
  }

  private static void jarIndex(Set<String> index, InputStream in) throws IOException {
    ZipInputStream jar = new ZipInputStream(new NonClosing(in));
    ZipEntry entry;
    while ((entry = jar.getNextEntry()) != null) {
      if (entry.getName().endsWith(".class")) {
        keep(index, entry.getName());
      }
    }
  }

  private static void keep(Set<String> index, String classEntry) {
    String name = classEntry.substring(0, classEntry.length() - ".class".length());
    if (ReadinessRules.library(name) || ReadinessRules.jakarta(name)) {
      index.add(name);
    }
  }

  /** Keeps an inner reader from closing the stream it reads one entry of. */
  private static final class NonClosing extends FilterInputStream {
    NonClosing(InputStream in) {
      super(in);
    }

    @Override
    public void close() {}
  }
}
