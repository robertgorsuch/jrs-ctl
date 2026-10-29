package com.jaspersoft.jrsctl.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterPropertiesTest {

  private static final String ORIGINAL =
      "# vendor file\nappServerType=tomcat\ndbType=postgresql\ndbPassword=keep-me\n";

  @TempDir Path tmp;
  private Path buildomatic;
  private Path runDir;
  private Path file;

  @BeforeEach
  void setUp() throws IOException {
    buildomatic = tmp.resolve("buildomatic");
    Files.createDirectories(buildomatic);
    runDir = tmp.resolve("runs").resolve("r-1");
    file = buildomatic.resolve(Buildomatic.MASTER_PROPERTIES);
  }

  @Test
  void should_backup_append_overrides_and_restore_when_file_exists() throws IOException {
    Files.writeString(file, ORIGINAL, StandardCharsets.ISO_8859_1);

    MasterProperties.Staged staged =
        MasterProperties.stage(
            buildomatic, Map.of("dbHost", "db.example", "js.path", "C:\\jrs"), runDir);

    assertThat(staged.backup()).contains(MasterProperties.backupFor(runDir));
    assertThat(Files.readString(staged.backup().get(), StandardCharsets.ISO_8859_1))
        .isEqualTo(ORIGINAL);
    String content = Files.readString(file, StandardCharsets.ISO_8859_1);
    assertThat(content).startsWith(ORIGINAL).contains(MasterProperties.OVERRIDE_HEADER);
    Map<String, String> parsed = BuildomaticLocator.parseMasterProperties(file);
    assertThat(parsed)
        .containsEntry("dbHost", "db.example")
        .containsEntry("js.path", "C:\\jrs")
        .containsEntry("appServerType", "tomcat")
        .doesNotContainKey("dbPassword");
    assertThat(content).contains("dbPassword=keep-me");

    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(ORIGINAL);
    assertThat(Files.exists(MasterProperties.backupFor(runDir))).isFalse();
  }

  @Test
  void should_create_and_then_delete_file_when_no_previous_file() throws IOException {
    MasterProperties.Staged staged =
        MasterProperties.stage(buildomatic, Map.of("dbType", "mysql"), runDir);

    assertThat(staged.backup()).isEmpty();
    assertThat(Files.exists(MasterProperties.backupFor(runDir))).isFalse();
    assertThat(BuildomaticLocator.parseMasterProperties(file)).containsEntry("dbType", "mysql");

    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void should_keep_pristine_backup_when_staged_twice() throws IOException {
    Files.writeString(file, ORIGINAL, StandardCharsets.ISO_8859_1);

    MasterProperties.stage(buildomatic, Map.of("first", "1"), runDir);
    MasterProperties.stage(buildomatic, Map.of("second", "2"), runDir);

    assertThat(Files.readString(MasterProperties.backupFor(runDir), StandardCharsets.ISO_8859_1))
        .isEqualTo(ORIGINAL);
    Map<String, String> parsed = BuildomaticLocator.parseMasterProperties(file);
    assertThat(parsed).containsEntry("second", "2").doesNotContainKey("first");

    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(ORIGINAL);
  }

  @Test
  void should_leave_the_restored_original_alone_when_restored_twice() throws IOException {
    Files.writeString(file, ORIGINAL, StandardCharsets.ISO_8859_1);
    MasterProperties.stage(buildomatic, Map.of("dbHost", "h"), runDir);

    MasterProperties.restore(buildomatic, runDir);
    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(ORIGINAL);
    assertThat(MasterProperties.isStaged(file)).isFalse();
  }

  @Test
  void should_stay_absent_when_restored_twice_without_a_previous_file() throws IOException {
    MasterProperties.stage(buildomatic, Map.of("dbHost", "h"), runDir);
    assertThat(MasterProperties.isStaged(file)).isTrue();

    MasterProperties.restore(buildomatic, runDir);
    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void should_only_write_password_keys_when_caller_passes_them() throws IOException {
    MasterProperties.stage(buildomatic, Map.of("dbHost", "h"), runDir);
    String withoutPass = Files.readString(file, StandardCharsets.ISO_8859_1);
    MasterProperties.restore(buildomatic, runDir);
    MasterProperties.stage(buildomatic, Map.of("dbPassword", "explicit"), runDir);
    String withPass = Files.readString(file, StandardCharsets.ISO_8859_1);

    assertThat(withoutPass.toLowerCase(java.util.Locale.ROOT)).doesNotContain("pass");
    assertThat(withPass).contains("dbPassword=explicit");
    assertThat(MasterProperties.isPasswordKey("dbPassword")).isTrue();
    assertThat(MasterProperties.isPasswordKey("dbHost")).isFalse();
  }

  /**
   * Review finding 10: a secret is written from its char[] with the escaping Properties applies, so
   * the vendor scripts read back exactly what was resolved, and the result keeps no copy.
   */
  @Test
  void should_write_a_secret_from_its_chars_so_that_properties_read_it_back_exactly()
      throws IOException {
    String value = " p=ss:w#rd!\\x\té中 ";
    char[] secret = value.toCharArray();

    MasterProperties.Staged staged =
        MasterProperties.stage(
            buildomatic, Map.of("dbHost", "h"), Map.of("key pass", secret), runDir);

    Properties read = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      read.load(in);
    }
    assertThat(read.getProperty("key pass")).isEqualTo(value);
    assertThat(read.getProperty("dbHost")).isEqualTo("h");
    assertThat(staged.overrides()).containsOnlyKeys("dbHost");
    assertThat(secret).as("the caller's array is left as it was").isEqualTo(value.toCharArray());
  }

  @Test
  void should_refuse_a_key_that_is_both_an_override_and_a_secret() {
    assertThatThrownBy(
            () ->
                MasterProperties.stage(
                    buildomatic,
                    Map.of("dbPassword", "plain"),
                    Map.of("dbPassword", "secret".toCharArray()),
                    runDir))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dbPassword");
  }
}
