package com.jaspersoft.jrsctl.jrs.vendor;

import com.jaspersoft.jrsctl.core.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A located {@code buildomatic/} directory (spec §7.4). Invariants: {@code scripts} holds only
 * scripts that exist, keyed by their base name ({@code js-export}, {@code js-import}, {@code
 * js-ant}) with the platform's extension already applied; {@code masterProperties} is the parsed
 * {@code default_master.properties} with every key containing {@code pass} (case-insensitive)
 * removed, so no database or keystore password ever reaches a caller; nothing here modifies the
 * vendor tree.
 */
public record Buildomatic(
    Path dir,
    Map<String, Path> scripts,
    Optional<Path> masterPropertiesFile,
    Map<String, String> masterProperties) {

  public static final String EXPORT_SCRIPT = "js-export";
  public static final String IMPORT_SCRIPT = "js-import";
  public static final String ANT_SCRIPT = "js-ant";
  public static final List<String> SCRIPT_NAMES = List.of(EXPORT_SCRIPT, IMPORT_SCRIPT, ANT_SCRIPT);
  public static final String MASTER_PROPERTIES = "default_master.properties";

  public Buildomatic {
    Objects.requireNonNull(dir, "dir");
    scripts = Map.copyOf(scripts);
    Objects.requireNonNull(masterPropertiesFile, "masterPropertiesFile");
    masterProperties = Map.copyOf(masterProperties);
  }

  /** Path of {@code js-export}, {@code js-import} or {@code js-ant}; empty if missing. */
  public Optional<Path> scriptFor(String name) {
    return Optional.ofNullable(scripts.get(name));
  }

  /** Expected scripts that were not found; empty means the layout is complete. */
  public List<String> missingScripts() {
    List<String> missing = new ArrayList<>();
    for (String n : SCRIPT_NAMES) {
      if (!scripts.containsKey(n)) {
        missing.add(n);
      }
    }
    return List.copyOf(missing);
  }

  /** {@code conf_source/db/<type>/jdbc} and the jars inside it. */
  public Optional<JdbcDriverDir> driverDir(String dbType) {
    Objects.requireNonNull(dbType, "dbType");
    String type = dbType.strip().toLowerCase(Locale.ROOT);
    Path d = dir.resolve("conf_source").resolve("db").resolve(type).resolve("jdbc");
    if (!Files.isDirectory(d)) {
      return Optional.empty();
    }
    List<Path> jars;
    try (Stream<Path> s = Files.list(d)) {
      jars =
          s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
              .sorted()
              .toList();
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.of(new JdbcDriverDir(type, d, jars));
  }

  public Optional<JdbcDriverDir> driverDir(Config.DatabaseType type) {
    return driverDir(type.yamlValue());
  }
}
