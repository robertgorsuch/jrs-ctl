package com.jaspersoft.jrsctl.jrs.vendor;

import com.jaspersoft.jrsctl.core.platform.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Finds {@code buildomatic/} under an installation or upgrade package directory (spec §7.4).
 * Invariants: read-only; script names get {@code .bat} on Windows and {@code .sh} on Linux
 * according to the {@link Platform}, never by probing both; {@code default_master.properties} is
 * parsed with {@link Properties} and stripped of every key containing {@code pass} before it is
 * handed out; an unreadable properties file yields an empty map rather than a failure, because
 * locating the tree must not depend on it.
 */
public final class BuildomaticLocator {

  public static final String DIR_NAME = "buildomatic";

  private final Platform platform;

  public BuildomaticLocator(Platform platform) {
    this.platform = Objects.requireNonNull(platform, "platform");
  }

  /** Empty when {@code installDir/buildomatic} is not a directory. */
  public Optional<Buildomatic> locate(Path installDir) {
    Objects.requireNonNull(installDir, "installDir");
    Path dir = installDir.resolve(DIR_NAME);
    if (!Files.isDirectory(dir)) {
      return Optional.empty();
    }
    String ext = scriptExtension();
    Map<String, Path> scripts = new LinkedHashMap<>();
    for (String name : Buildomatic.SCRIPT_NAMES) {
      Path script = dir.resolve(name + ext);
      if (Files.isRegularFile(script)) {
        scripts.put(name, script);
      }
    }
    Path master = dir.resolve(Buildomatic.MASTER_PROPERTIES);
    Optional<Path> masterFile =
        Files.isRegularFile(master) ? Optional.of(master) : Optional.empty();
    Map<String, String> properties = Map.of();
    if (masterFile.isPresent()) {
      try {
        properties = parseMasterProperties(master);
      } catch (IOException e) {
        properties = Map.of();
      }
    }
    return Optional.of(new Buildomatic(dir, scripts, masterFile, properties));
  }

  /** {@code .bat} on Windows, {@code .sh} on Linux. */
  public String scriptExtension() {
    return switch (platform.os()) {
      case WINDOWS -> ".bat";
      case LINUX -> ".sh";
    };
  }

  /** Loads a properties file and drops every key whose name contains {@code pass}. */
  public static Map<String, String> parseMasterProperties(Path file) throws IOException {
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      p.load(in);
    }
    Map<String, String> out = new TreeMap<>();
    for (String name : p.stringPropertyNames()) {
      if (!name.toLowerCase(Locale.ROOT).contains("pass")) {
        out.put(name, p.getProperty(name));
      }
    }
    return Map.copyOf(out);
  }
}
