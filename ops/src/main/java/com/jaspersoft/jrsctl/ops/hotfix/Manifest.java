package com.jaspersoft.jrsctl.ops.hotfix;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jaspersoft.jrsctl.core.config.Config;
import java.util.List;
import java.util.Optional;

/**
 * Typed view of {@code manifest.json} (spec §8.1, schema {@code hotfix-manifest.schema.json}).
 * Invariants: every list is non-null and immutable, every optional field is an {@link Optional};
 * enum constants serialise to the schema's lowercase spellings; the record carries no derived
 * state, so it round-trips through Jackson byte-for-byte except for whitespace. {@code sha256}
 * fields are optional only because {@code hotfix build} fills them in; a manifest that passed
 * {@link ManifestValidator} has them all.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record Manifest(
    String id,
    String version,
    String title,
    Optional<String> description,
    Applies applies,
    List<String> requires,
    List<String> conflicts,
    List<FileEntry> files,
    List<SqlEntry> sql,
    List<CheckFile> checks,
    Restart restart,
    List<Check> prechecks,
    List<Check> postchecks,
    Rollback rollback,
    Optional<String> rollbackNote) {

  public Manifest {
    description = orEmpty(description);
    requires = orEmpty(requires);
    conflicts = orEmpty(conflicts);
    files = orEmpty(files);
    sql = orEmpty(sql);
    checks = orEmpty(checks);
    prechecks = orEmpty(prechecks);
    postchecks = orEmpty(postchecks);
    rollbackNote = orEmpty(rollbackNote);
  }

  /** SQL entries that target {@code type}, in manifest order. */
  public List<SqlEntry> sqlFor(Config.DatabaseType type) {
    return sql.stream().filter(e -> e.db().equals(type.yamlValue())).toList();
  }

  /** True when any file path is under {@code WEB-INF/lib} or {@code WEB-INF/classes}. */
  public boolean touchesWebInf() {
    return files.stream().anyMatch(f -> HotfixPaths.requiresServiceStop(f.path()));
  }

  /** What to do with one file, relative to the Tomcat or install directory. */
  public enum Action {
    @JsonProperty("add")
    ADD,
    @JsonProperty("replace")
    REPLACE,
    @JsonProperty("delete")
    DELETE
  }

  /** Whether the service must be stopped around the file swap. */
  public enum Restart {
    @JsonProperty("required")
    REQUIRED,
    @JsonProperty("none")
    NONE
  }

  /** How the hotfix is undone. */
  public enum Rollback {
    @JsonProperty("snapshot")
    SNAPSHOT,
    @JsonProperty("irreversible")
    IRREVERSIBLE
  }

  /** Kind of a pre- or postcheck. */
  public enum CheckType {
    @JsonProperty("fileExists")
    FILE_EXISTS,
    @JsonProperty("fileAbsent")
    FILE_ABSENT,
    @JsonProperty("http")
    HTTP,
    @JsonProperty("sha256")
    SHA256
  }

  /** Server versions, editions and tenancy modes the hotfix applies to. */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  public record Applies(List<String> versions, List<String> editions, List<String> tenancy) {
    public Applies {
      versions = orEmpty(versions);
      editions = orEmpty(editions);
      tenancy = orEmpty(tenancy);
    }
  }

  /** One payload file. {@code replaces} names sibling files removed after this one lands. */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  public record FileEntry(
      Action action, String path, Optional<String> sha256, List<String> replaces) {
    public FileEntry {
      sha256 = orEmpty(sha256);
      replaces = orEmpty(replaces);
    }

    /** Payload path inside the bundle. */
    public String bundlePath() {
      return HotfixBundle.PAYLOAD_DIR + "/" + path;
    }
  }

  /**
   * One SQL script for one database type. {@code idempotent} is a claim about {@code file} and
   * {@code rollbackFile} alike: both are re-run, the first by {@code runs recover --resume} after
   * an interrupted step, the second by a compensation that is retried until it succeeds.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  public record SqlEntry(
      String db,
      String file,
      Optional<String> sha256,
      boolean idempotent,
      Optional<String> rollbackFile,
      Optional<String> rollbackSha256) {
    public SqlEntry {
      sha256 = orEmpty(sha256);
      rollbackFile = orEmpty(rollbackFile);
      rollbackSha256 = orEmpty(rollbackSha256);
    }
  }

  /** One JSON check definition shipped under {@code checks/}. */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  public record CheckFile(String file, Optional<String> sha256) {
    public CheckFile {
      sha256 = orEmpty(sha256);
    }
  }

  /** A pre- or postcheck; which fields matter depends on {@code type}. */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  public record Check(
      CheckType type,
      Optional<String> path,
      Optional<String> url,
      Optional<Integer> expect,
      Optional<String> sha256) {
    public Check {
      path = orEmpty(path);
      url = orEmpty(url);
      expect = orEmpty(expect);
      sha256 = orEmpty(sha256);
    }

    /** One-line description for plans and failures. */
    public String describe() {
      return switch (type) {
        case FILE_EXISTS -> "file exists " + path.orElse("?");
        case FILE_ABSENT -> "file absent " + path.orElse("?");
        case HTTP -> "GET " + url.orElse("?") + " expects " + expect.orElse(200);
        case SHA256 -> "sha256 of " + path.orElse("?");
      };
    }
  }

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  private static <T> Optional<T> orEmpty(Optional<T> value) {
    return value == null ? Optional.empty() : value;
  }
}
