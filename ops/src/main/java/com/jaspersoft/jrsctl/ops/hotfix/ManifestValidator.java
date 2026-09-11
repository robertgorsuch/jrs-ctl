package com.jaspersoft.jrsctl.ops.hotfix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.json.Json;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a manifest against the bundled JSON schema and then against the semantic rules of spec
 * §8.1 that a schema cannot express. Invariants: every violation is reported, not just the first; a
 * {@link Result.Valid} manifest has every {@code sha256} filled in, no {@code WEB-INF} path with
 * {@code restart: none}, a rollback script for every SQL entry unless the rollback is declared
 * irreversible, and only relative, drive-less, {@code ..}-free paths.
 */
public final class ManifestValidator {

  public static final String SCHEMA_RESOURCE = "/schema/hotfix-manifest.schema.json";

  private static final JsonSchema SCHEMA = loadSchema();

  /** Outcome of a validation. */
  public sealed interface Result permits Result.Valid, Result.Invalid {
    record Valid(Manifest manifest) implements Result {}

    record Invalid(List<String> problems) implements Result {
      public Invalid {
        problems = List.copyOf(problems);
      }
    }
  }

  /** Schema plus semantic validation of {@code json} (UTF-8 text of {@code manifest.json}). */
  public Result validate(String json) {
    JsonNode tree;
    try {
      tree = Json.mapper().readTree(json);
    } catch (JsonProcessingException e) {
      return new Result.Invalid(
          List.of("manifest.json is not valid JSON: " + e.getOriginalMessage()));
    }
    List<String> problems = schemaProblems(tree);
    if (!problems.isEmpty()) {
      return new Result.Invalid(problems);
    }
    Manifest manifest;
    try {
      manifest = Json.mapper().treeToValue(tree, Manifest.class);
    } catch (JsonProcessingException e) {
      return new Result.Invalid(List.of("manifest.json cannot be read: " + e.getOriginalMessage()));
    }
    problems = semanticProblems(manifest);
    return problems.isEmpty() ? new Result.Valid(manifest) : new Result.Invalid(problems);
  }

  /** Schema violations only, sorted, as {@code path: message}. */
  public List<String> schemaProblems(JsonNode tree) {
    Set<ValidationMessage> messages = SCHEMA.validate(tree);
    List<String> violations = new ArrayList<>();
    for (ValidationMessage m : messages) {
      String location = m.getInstanceLocation().toString();
      String path = location.startsWith("$.") ? location.substring(2) : location;
      if (path.equals("$") || path.isEmpty()) {
        path = "(root)";
      }
      String text = m.getMessage();
      String prefix = location + ": ";
      if (text.startsWith(prefix)) {
        text = text.substring(prefix.length());
      }
      violations.add(path + ": " + text);
    }
    violations.sort(String::compareTo);
    return List.copyOf(violations);
  }

  /** Rules of spec §8.1 beyond the schema; empty when the manifest is acceptable. */
  public static List<String> semanticProblems(Manifest m) {
    Set<String> problems = new LinkedHashSet<>();
    Set<String> seen = new LinkedHashSet<>();
    // Assessment item O4: what this hotfix installs, by case-folded normalised key, so a
    // `replaces` sibling can be checked against the entry's own file and every other payload.
    Map<String, String> installs = new LinkedHashMap<>();
    for (Manifest.FileEntry f : m.files()) {
      if (f.action() != Manifest.Action.DELETE && HotfixPaths.pathProblems(f.path()).isEmpty()) {
        installs.putIfAbsent(HotfixPaths.key(Path.of(f.path())), f.path());
      }
    }
    for (Manifest.FileEntry f : m.files()) {
      String where = "files[" + f.path() + "]";
      for (String p : HotfixPaths.pathProblems(f.path())) {
        problems.add(where + ": " + p);
      }
      if (!seen.add(f.path().replace('\\', '/'))) {
        problems.add(where + ": listed twice");
      }
      if (f.action() != Manifest.Action.DELETE && f.sha256().isEmpty()) {
        problems.add(where + ": sha256 is required for " + f.action());
      }
      if (m.restart() == Manifest.Restart.NONE && HotfixPaths.requiresServiceStop(f.path())) {
        problems.add(
            where
                + ": restart 'none' is not allowed for files under WEB-INF/lib or"
                + " WEB-INF/classes (spec §5.3)");
      }
      for (String sibling : f.replaces()) {
        if (!HotfixPaths.isPlainFileName(sibling)) {
          problems.add(where + ": replaces entry '" + sibling + "' must be a plain file name");
          continue;
        }
        if (!HotfixPaths.pathProblems(f.path()).isEmpty()) {
          continue;
        }
        Path own = Path.of(f.path());
        Path siblingPath = own.resolveSibling(sibling);
        String siblingKey = HotfixPaths.key(siblingPath);
        if (siblingKey.equals(HotfixPaths.key(own))) {
          problems.add(
              where
                  + ": replaces entry '"
                  + sibling
                  + "' names the file itself; the swap would delete the payload it just"
                  + " installed");
        } else if (installs.containsKey(siblingKey)) {
          problems.add(
              where
                  + ": replaces entry '"
                  + sibling
                  + "' names a file this hotfix installs ("
                  + installs.get(siblingKey)
                  + ")");
        }
      }
      if (f.action() == Manifest.Action.DELETE && !f.replaces().isEmpty()) {
        problems.add(where + ": a delete entry cannot list 'replaces'");
      }
    }
    for (Manifest.SqlEntry s : m.sql()) {
      String where = "sql[" + s.file() + "]";
      if (!s.idempotent()) {
        problems.add(
            where
                + ": idempotent must be true, for the rollback script as well as the script"
                + " itself; an interrupted step is re-executed by runs recover --resume and a"
                + " compensation is re-run until it succeeds");
      }
      if (s.sha256().isEmpty()) {
        problems.add(where + ": sha256 is required");
      }
      if (s.rollbackFile().isEmpty() && m.rollback() != Manifest.Rollback.IRREVERSIBLE) {
        problems.add(
            where
                + ": rollbackFile is required unless the manifest declares"
                + " \"rollback\": \"irreversible\" with a rollbackNote");
      }
      if (s.rollbackFile().isPresent() && s.rollbackSha256().isEmpty()) {
        problems.add(where + ": rollbackSha256 is required when rollbackFile is set");
      }
    }
    for (Manifest.CheckFile c : m.checks()) {
      if (c.sha256().isEmpty()) {
        problems.add("checks[" + c.file() + "]: sha256 is required");
      }
    }
    if (m.rollback() == Manifest.Rollback.IRREVERSIBLE
        && m.rollbackNote().map(String::isBlank).orElse(true)) {
      problems.add("rollbackNote: required when rollback is irreversible");
    }
    checkProblems(m.prechecks(), "prechecks", problems);
    checkProblems(m.postchecks(), "postchecks", problems);
    if (m.requires().contains(m.id())) {
      problems.add("requires: a hotfix cannot require itself");
    }
    if (m.conflicts().contains(m.id())) {
      problems.add("conflicts: a hotfix cannot conflict with itself");
    }
    return List.copyOf(problems);
  }

  private static void checkProblems(List<Manifest.Check> checks, String where, Set<String> out) {
    for (int i = 0; i < checks.size(); i++) {
      Manifest.Check c = checks.get(i);
      String at = where + "[" + i + "]";
      switch (c.type()) {
        case FILE_EXISTS, FILE_ABSENT -> {
          if (c.path().isEmpty()) {
            out.add(at + ": 'path' is required for " + c.type());
          } else {
            HotfixPaths.pathProblems(c.path().get()).forEach(p -> out.add(at + ": " + p));
          }
        }
        case HTTP -> {
          if (c.url().map(u -> !u.startsWith("/")).orElse(true)) {
            out.add(at + ": 'url' must be a path starting with '/' for http checks");
          }
        }
        case SHA256 -> {
          if (c.path().isEmpty() || c.sha256().isEmpty()) {
            out.add(at + ": 'path' and 'sha256' are required for sha256 checks");
          }
        }
      }
    }
  }

  private static JsonSchema loadSchema() {
    try (InputStream in = ManifestValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the jar");
      }
      JsonNode node = Json.mapper().readTree(in);
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + SCHEMA_RESOURCE, e);
    }
  }
}
