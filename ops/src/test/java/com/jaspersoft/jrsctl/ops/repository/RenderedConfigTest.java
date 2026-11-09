package com.jaspersoft.jrsctl.ops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.redact.Redactor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #279 item 6: the rendered configuration is kept without the passwords it holds. */
class RenderedConfigTest {

  @TempDir Path tmp;

  @Test
  void should_mask_the_value_of_every_password_like_key_whatever_the_separator() {
    Redactor redactor = new Redactor();

    assertThat(RenderedConfig.mask("metadata.jdbc.password=TopSecret", redactor))
        .isEqualTo("metadata.jdbc.password=" + Redactor.MASK);
    assertThat(RenderedConfig.mask("metadata.jdbc.password.escapeXML=Top&amp;Secret", redactor))
        .isEqualTo("metadata.jdbc.password.escapeXML=" + Redactor.MASK);
    assertThat(RenderedConfig.mask("dbPassword : TopSecret", redactor))
        .isEqualTo("dbPassword=" + Redactor.MASK);
    assertThat(RenderedConfig.mask("encrypt.keypass=changeit", redactor))
        .isEqualTo("encrypt.keypass=" + Redactor.MASK);
  }

  @Test
  void should_pass_other_lines_through_the_redactor_and_leave_comments_alone() {
    Redactor redactor = new Redactor();
    redactor.register("db-pw-for-tests");

    assertThat(
            RenderedConfig.mask(
                "metadata.jdbc.url=jdbc:postgresql://db:5432/jrs_101?x=db-pw-for-tests", redactor))
        .isEqualTo("metadata.jdbc.url=jdbc:postgresql://db:5432/jrs_101?x=" + Redactor.MASK);
    assertThat(RenderedConfig.mask("# a comment with password in it", redactor))
        .isEqualTo("# a comment with password in it");
    assertThat(RenderedConfig.mask("", redactor)).isEmpty();
  }

  @Test
  void should_copy_only_the_properties_files_and_read_the_rendered_url_back() throws Exception {
    Path from = Files.createDirectories(tmp.resolve("default"));
    Files.writeString(
        from.resolve(RenderedConfig.JDBC_PROPERTIES),
        "metadata.jdbc.url=jdbc:postgresql://db:5432/jrs_101\nmetadata.jdbc.password=TopSecret\n",
        StandardCharsets.ISO_8859_1);
    Files.writeString(from.resolve("hibernate.cfg.xml"), "<x/>", StandardCharsets.UTF_8);
    Path to = tmp.resolve("copy");

    assertThat(RenderedConfig.copy(from, to, new Redactor()))
        .containsExactly(RenderedConfig.JDBC_PROPERTIES);
    assertThat(to.resolve("hibernate.cfg.xml")).doesNotExist();
    assertThat(Files.readString(to.resolve(RenderedConfig.JDBC_PROPERTIES)))
        .contains("jrs_101")
        .doesNotContain("TopSecret");
    assertThat(
            RenderedConfig.property(
                from.resolve(RenderedConfig.JDBC_PROPERTIES), RenderedConfig.JDBC_URL_KEY))
        .contains("jdbc:postgresql://db:5432/jrs_101");
    assertThat(RenderedConfig.property(from.resolve(RenderedConfig.JDBC_PROPERTIES), "absent"))
        .isEmpty();

    RenderedConfig.deleteCopy(to);
    RenderedConfig.deleteCopy(to);
    assertThat(to).doesNotExist();
  }
}
