package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManifestValidatorTest {

  private static final String SHA = "a".repeat(64);
  private final ManifestValidator validator = new ManifestValidator();

  private static String manifest(String files, String restart, String rollback, String extra) {
    return """
        {
          "id": "JRS-8.2.0-HF-0001",
          "version": "1",
          "title": "t",
          "applies": { "versions": [">=8.0.0"] },
          "files": [ %s ],
          "restart": "%s",
          "rollback": "%s"%s
        }
        """
        .formatted(files, restart, rollback, extra);
  }

  private List<String> problems(String json) {
    return switch (validator.validate(json)) {
      case ManifestValidator.Result.Valid v -> List.of();
      case ManifestValidator.Result.Invalid i -> i.problems();
    };
  }

  /**
   * The documented starter template must stay a manifest {@code hotfix build} accepts once it has
   * filled in the hashes, and the authoring guide must quote it exactly, so neither can drift.
   */
  @Test
  void should_accept_the_documented_template_when_the_hashes_are_filled_in() throws IOException {
    Path docs = Path.of("..", "docs");
    String template =
        normalised(
            Files.readString(
                docs.resolve("templates").resolve("hotfix-manifest.json"), StandardCharsets.UTF_8));
    String guide =
        normalised(Files.readString(docs.resolve("hotfix-authoring.md"), StandardCharsets.UTF_8));
    String built =
        template.replace(
            "\"action\": \"add\",", "\"action\": \"add\",\n      \"sha256\": \"" + SHA + "\",");

    assertThat(built).as("the template has an add entry to hash").isNotEqualTo(template);
    assertThat(problems(built)).isEmpty();
    assertThat(guide).contains(template.strip());
  }

  private static String normalised(String text) {
    return text.replace("\r\n", "\n");
  }

  @Test
  void should_list_every_schema_violation_when_fields_missing_or_wrong() {
    List<String> problems =
        problems("{\"id\": \"bad\", \"version\": \"2\", \"files\": [{\"path\": \"x\"}]}");
    assertThat(problems).anyMatch(p -> p.contains("title"));
    assertThat(problems).anyMatch(p -> p.contains("applies"));
    assertThat(problems).anyMatch(p -> p.contains("restart"));
    assertThat(problems).anyMatch(p -> p.contains("action"));
    assertThat(problems).anyMatch(p -> p.startsWith("id:"));
    assertThat(problems).anyMatch(p -> p.startsWith("version:"));
  }

  @Test
  void should_reject_restart_none_when_path_under_web_inf_lib() {
    String json =
        manifest(
            "{\"action\":\"replace\",\"path\":\"webapps/js/WEB-INF/lib/a.jar\",\"sha256\":\""
                + SHA
                + "\"}",
            "none",
            "snapshot",
            "");
    assertThat(problems(json)).anyMatch(p -> p.contains("restart 'none' is not allowed"));
  }

  @Test
  void should_accept_restart_none_when_path_outside_web_inf() {
    String json =
        manifest(
            "{\"action\":\"add\",\"path\":\"webapps/js/scripts/a.js\",\"sha256\":\"" + SHA + "\"}",
            "none",
            "snapshot",
            "");
    assertThat(problems(json)).isEmpty();
  }

  @Test
  void should_reject_sql_without_rollback_file_when_rollback_is_snapshot() {
    String json =
        manifest(
            "",
            "required",
            "snapshot",
            ", \"sql\": [ { \"db\": \"postgresql\", \"file\": \"sql/postgresql/1.sql\","
                + " \"sha256\": \""
                + SHA
                + "\", \"idempotent\": true } ]");
    assertThat(problems(json)).anyMatch(p -> p.contains("rollbackFile is required"));
  }

  @Test
  void should_accept_sql_without_rollback_file_when_rollback_is_irreversible() {
    String json =
        manifest(
            "",
            "required",
            "irreversible",
            ", \"rollbackNote\": \"restore the database from backup\","
                + " \"sql\": [ { \"db\": \"postgresql\", \"file\": \"sql/postgresql/1.sql\","
                + " \"sha256\": \""
                + SHA
                + "\", \"idempotent\": true } ]");
    assertThat(problems(json)).isEmpty();
  }

  @Test
  void should_reject_paths_with_parent_segments_or_drive_letters() {
    assertThat(
            problems(
                manifest(
                    "{\"action\":\"add\",\"path\":\"../etc/passwd\",\"sha256\":\"" + SHA + "\"}",
                    "required",
                    "snapshot",
                    "")))
        .isNotEmpty();
    assertThat(
            problems(
                manifest(
                    "{\"action\":\"add\",\"path\":\"C:/x.jar\",\"sha256\":\"" + SHA + "\"}",
                    "required",
                    "snapshot",
                    "")))
        .isNotEmpty();
    assertThat(HotfixPaths.pathProblems("/abs/x")).isNotEmpty();
    assertThat(HotfixPaths.pathProblems("a/../b")).isNotEmpty();
    assertThat(HotfixPaths.pathProblems("webapps/x/y.jar")).isEmpty();
  }

  @Test
  void should_reject_replaces_entry_when_it_is_not_a_plain_file_name() {
    String json =
        manifest(
            "{\"action\":\"replace\",\"path\":\"webapps/js/WEB-INF/lib/a.jar\",\"sha256\":\""
                + SHA
                + "\",\"replaces\":[\"../b.jar\"]}",
            "required",
            "snapshot",
            "");
    assertThat(problems(json)).anyMatch(p -> p.contains("plain file name"));
  }

  /**
   * Assessment item O4: {@code replaces} naming the entry's own file, in any letter case, made the
   * swap delete the payload it had just installed and record the hotfix as installed.
   */
  @Test
  void should_reject_replaces_naming_the_target_itself_in_any_letter_case() {
    for (String self : List.of("a.jar", "A.JAR")) {
      String json =
          manifest(
              "{\"action\":\"replace\",\"path\":\"webapps/js/WEB-INF/lib/a.jar\",\"sha256\":\""
                  + SHA
                  + "\",\"replaces\":[\""
                  + self
                  + "\"]}",
              "required",
              "snapshot",
              "");
      assertThat(problems(json)).as(self).anyMatch(p -> p.contains("names the file itself"));
    }
  }

  @Test
  void should_reject_replaces_naming_a_file_this_hotfix_installs() {
    String json =
        manifest(
            "{\"action\":\"add\",\"path\":\"webapps/js/WEB-INF/lib/b.jar\",\"sha256\":\""
                + SHA
                + "\"},{\"action\":\"replace\",\"path\":\"webapps/js/WEB-INF/lib/a.jar\","
                + "\"sha256\":\""
                + SHA
                + "\",\"replaces\":[\"B.jar\"]}",
            "required",
            "snapshot",
            "");
    assertThat(problems(json)).anyMatch(p -> p.contains("installs"));
  }

  @Test
  void should_report_invalid_json_when_manifest_is_not_json() {
    assertThat(problems("{not json")).singleElement().asString().contains("not valid JSON");
  }
}
