package com.jaspersoft.jrsctl.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rewrites the values a console document cannot repeat between runs so a golden file stays stable.
 * Invariants: structure and key order are untouched; a JSON {@code null} stays {@code null},
 * because whether a key is absent, null or set is exactly what the goldens guard; replacement is by
 * substring, so text around an id or a path survives.
 */
final class ConsoleScrub {

  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern RUN_ID = Pattern.compile("r-\\d{8}-\\d{6}-[0-9a-f]{4}");
  private static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
  private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{8,}");
  private static final Pattern HOSTPORT = Pattern.compile("127\\.0\\.0\\.1:\\d+");

  /** Numeric keys whose value is a measurement, replaced by 0 when not null. */
  private static final List<String> MEASURED = List.of("durationMs", "bytes");

  /**
   * String keys holding a version, replaced by {@code <version>} when non-empty so a golden
   * survives a release version bump. An empty string is left untouched: {@code server-unreachable}
   * uses {@code ""} for {@code version} and {@code database.version} as its "server not reachable"
   * signal.
   */
  private static final List<String> VERSIONED = List.of("version", "matrixVersion");

  /**
   * String keys holding a process id, replaced by {@code <pid>} when non-empty because {@code pid}
   * is the test JVM's own process id and varies run to run; {@code held == false} never carries
   * this key, so there is no empty-string case to preserve, unlike {@link #VERSIONED}.
   */
  private static final List<String> PID = List.of("pid");

  private ConsoleScrub() {}

  /** A copy of {@code node} with volatile values replaced; {@code home} becomes {@code <home>}. */
  static JsonNode scrub(JsonNode node, Path home) {
    return walk(node.deepCopy(), home, "");
  }

  /**
   * A copy of {@code node} that keeps key names, key order and nesting, truncates every array to at
   * most its first element, and replaces every leaf with a type token ({@code "<string>"}, {@code
   * "<int>"}, {@code "<number>"}, {@code "<bool>"}) so a golden built from it asserts shape only. A
   * JSON {@code null} leaf stays {@code null}, for the same reason {@link #scrub} keeps it: whether
   * a key is absent, null or set is precisely what these goldens guard.
   */
  static JsonNode shape(JsonNode node) {
    return shapeOf(node.deepCopy());
  }

  private static JsonNode shapeOf(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (String name :
          List.copyOf(object.properties().stream().map(Map.Entry::getKey).toList())) {
        object.set(name, shapeOf(object.get(name)));
      }
      return object;
    }
    if (node instanceof ArrayNode array) {
      JsonNode first = array.isEmpty() ? null : shapeOf(array.get(0));
      array.removeAll();
      if (first != null) {
        array.add(first);
      }
      return array;
    }
    if (node.isNull()) {
      return NullNode.getInstance();
    }
    if (node.isBoolean()) {
      return TextNode.valueOf("<bool>");
    }
    if (node.isIntegralNumber()) {
      return TextNode.valueOf("<int>");
    }
    if (node.isNumber()) {
      return TextNode.valueOf("<number>");
    }
    if (node.isTextual()) {
      return TextNode.valueOf("<string>");
    }
    return node;
  }

  private static JsonNode walk(JsonNode node, Path home, String key) {
    if (node instanceof ObjectNode object) {
      for (String name :
          List.copyOf(object.properties().stream().map(Map.Entry::getKey).toList())) {
        object.set(name, walk(object.get(name), home, name));
      }
      return object;
    }
    if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, walk(array.get(i), home, key));
      }
      return array;
    }
    if (node.isNull()) {
      return node;
    }
    if (node.isNumber() && MEASURED.contains(key)) {
      return LongNode.valueOf(0L);
    }
    if (node.isTextual() && VERSIONED.contains(key) && !node.asText().isEmpty()) {
      return TextNode.valueOf("<version>");
    }
    if (node.isTextual() && PID.contains(key) && !node.asText().isEmpty()) {
      return TextNode.valueOf("<pid>");
    }
    if (node.isTextual()) {
      return TextNode.valueOf(text(node.asText(), home));
    }
    return node;
  }

  private static String text(String value, Path home) {
    String out =
        value
            .replace(home.toString(), "<home>")
            .replace(home.toString().replace("\\", "/"), "<home>");
    out = RUN_ID.matcher(out).replaceAll("<runId>");
    out = UUID.matcher(out).replaceAll("<uuid>");
    out = INSTANT.matcher(out).replaceAll("<instant>");
    out = SHA.matcher(out).replaceAll("sha256:<hex>");
    out = HOSTPORT.matcher(out).replaceAll("127.0.0.1:<port>");
    return out;
  }
}
