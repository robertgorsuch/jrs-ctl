package com.jaspersoft.jrsctl.core.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CompatMatrixTest {

  private final CompatMatrix matrix = CompatMatrix.load();

  @Test
  void should_load_unsigned_version_one_matrix_when_bundled() {
    assertThat(matrix.matrixVersion()).isEqualTo(1);
    assertThat(matrix.signed()).isFalse();
    assertThat(matrix.entries()).hasSize(5);
    assertThat(matrix.upgradePaths()).isNotEmpty();
  }

  @Test
  void should_find_entry_when_version_is_inside_a_range() {
    assertThat(matrix.find("7.1.0")).map(CompatMatrix.Entry::javaForBuildomatic).contains(8);
    assertThat(matrix.find("7.9.1")).map(CompatMatrix.Entry::javaForBuildomatic).contains(8);
    assertThat(matrix.find("8.2.0"))
        .map(CompatMatrix.Entry::label)
        .contains("JasperReports Server 8.x");
    assertThat(matrix.find("9.0.0")).map(CompatMatrix.Entry::javaForBuildomatic).contains(17);
    assertThat(matrix.find("10.0.0")).map(CompatMatrix.Entry::javaForBuildomatic).contains(17);
  }

  @Test
  void should_coerce_two_part_versions_when_looking_up() {
    assertThat(matrix.find("8.2")).isPresent();
    assertThat(matrix.javaRequiredFor("8.2")).isEqualTo(11);
  }

  @Test
  void should_return_empty_when_version_is_unsupported_or_garbage() {
    assertThat(matrix.find("6.4.3")).isEmpty();
    assertThat(matrix.find("11.0.0")).isEmpty();
    assertThat(matrix.find("not-a-version")).isEmpty();
    assertThat(matrix.find("")).isEmpty();
  }

  @Test
  void should_support_combination_when_every_dimension_is_listed() {
    assertThat(matrix.supports("9.0.0", "pro", "Tomcat", "PostgreSQL")).isTrue();
    assertThat(matrix.supports("7.5.0", "CE", "tomcat", "db2")).isTrue();
    assertThat(matrix.supports("9.0.0", "PRO", "jboss", "postgresql")).isFalse();
    assertThat(matrix.supports("9.0.0", "PRO", "tomcat", "sqlite")).isFalse();
    assertThat(matrix.supports("9.0.0", "ENTERPRISE", "tomcat", "postgresql")).isFalse();
    assertThat(matrix.supports("6.0.0", "PRO", "tomcat", "postgresql")).isFalse();
  }

  @Test
  void should_report_java_for_buildomatic_when_version_is_known() {
    assertThat(matrix.javaRequiredFor("7.2.0")).isEqualTo(8);
    assertThat(matrix.javaRequiredFor("8.0.4")).isEqualTo(11);
    assertThat(matrix.javaRequiredFor("9.0.0")).isEqualTo(17);
    assertThat(matrix.javaRequiredFor("10.0.0")).isEqualTo(17);
  }

  @Test
  void should_throw_unsupported_when_asking_java_for_unknown_version() {
    assertThatThrownBy(() -> matrix.javaRequiredFor("6.0.0"))
        .isInstanceOf(UnsupportedVersionException.class)
        .hasMessageContaining("6.0.0")
        .hasMessageContaining("--allow-unsupported");
  }

  @Test
  void should_accept_listed_upgrade_paths_when_target_is_newer() {
    assertThat(matrix.upgradePathSupported("7.9.1", "8.2.0")).isTrue();
    assertThat(matrix.upgradePathSupported("8.0.0", "9.0.0")).isTrue();
    assertThat(matrix.upgradePathSupported("8.2.0", "10.0.0")).isTrue();
    assertThat(matrix.upgradePathSupported("9.0.0", "10.0.0")).isTrue();
    assertThat(matrix.upgradePathSupported("8.0.0", "8.2.0")).isTrue();
  }

  @Test
  void should_reject_upgrade_path_when_unlisted_downgrade_or_same_version() {
    assertThat(matrix.upgradePathSupported("7.1.0", "9.0.0")).isFalse();
    assertThat(matrix.upgradePathSupported("7.1.0", "10.0.0")).isFalse();
    assertThat(matrix.upgradePathSupported("8.2.0", "8.0.0")).isFalse();
    assertThat(matrix.upgradePathSupported("8.2.0", "8.2.0")).isFalse();
    assertThat(matrix.upgradePathSupported("garbage", "8.2.0")).isFalse();
  }

  @Test
  void should_expect_capabilities_by_version_and_edition_when_listed() {
    assertThat(matrix.expectedCapabilities("7.1.0", "CE"))
        .containsExactlyInAnyOrder("EXPORT_ASYNC", "IMPORT_ASYNC", "REST_LOGIN");
    assertThat(matrix.expectedCapabilities("7.1.0", "PRO")).contains("ORGS");
    assertThat(matrix.expectedCapabilities("7.5.0", "CE"))
        .contains("KEYSTORE_ENCRYPTION")
        .doesNotContain("TOKEN_AUTH", "ORGS");
    assertThat(matrix.expectedCapabilities("8.0.0", "pro"))
        .contains("TOKEN_AUTH", "PREAUTH", "ORGS", "KEYSTORE_ENCRYPTION");
    assertThat(matrix.expectedCapabilities("10.0.0", "CE"))
        .contains("TOKEN_AUTH")
        .doesNotContain("ORGS");
    assertThat(matrix.expectedCapabilities("6.0.0", "PRO")).isEmpty();
  }

  @Test
  void should_parse_minimal_document_when_loaded_from_stream() throws IOException {
    String yaml =
        """
        matrixVersion: 1
        signed: false
        entries:
          - range: "1.x"
            editions: [ce]
            appServers: [Tomcat]
            javaForBuildomatic: 21
            databases: [PostgreSQL]
        """;

    CompatMatrix m =
        CompatMatrix.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertThat(m.supports("1.2.3", "CE", "tomcat", "postgresql")).isTrue();
    assertThat(m.expectedCapabilities("1.2.3", "CE")).isEmpty();
    assertThat(m.upgradePathSupported("1.0.0", "1.2.3")).isFalse();
    assertThat(m.find("1.0.0")).map(CompatMatrix.Entry::label).contains("1.x");
  }
}
