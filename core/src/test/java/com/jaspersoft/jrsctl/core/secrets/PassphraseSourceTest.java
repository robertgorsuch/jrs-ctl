package com.jaspersoft.jrsctl.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PassphraseSourceTest {

  @TempDir Path tmp;

  @Test
  void should_read_env_var_when_jrsctl_passphrase_is_set() {
    try (Secret s = new PassphraseSource.FromEnv(Map.of("JRSCTL_PASSPHRASE", "pp")).require()) {
      assertThat(s.chars()).containsExactly('p', 'p');
    }
    assertThat(new PassphraseSource.FromEnv(Map.of()).read()).isEmpty();
    assertThat(new PassphraseSource.FromEnv(Map.of("JRSCTL_PASSPHRASE", "")).read()).isEmpty();
  }

  @Test
  void should_strip_trailing_newline_when_reading_passphrase_file() throws IOException {
    Path f = tmp.resolve("pp");
    Files.writeString(f, "correct horse\r\n", StandardCharsets.UTF_8);

    try (Secret s = new PassphraseSource.FromFile(f).require()) {
      assertThat(new String(s.chars())).isEqualTo("correct horse");
    }
  }

  @Test
  void should_fail_with_path_when_passphrase_file_is_missing() {
    Path missing = tmp.resolve("nope");

    assertThatThrownBy(() -> new PassphraseSource.FromFile(missing).read())
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void should_take_first_available_source_when_chained() {
    PassphraseSource chain =
        new PassphraseSource.Chain(
            List.of(
                new PassphraseSource.FromEnv(Map.of()),
                new PassphraseSource.Fixed(Secret.fromString("second")),
                new PassphraseSource.Fixed(Secret.fromString("third"))));

    try (Secret s = chain.require()) {
      assertThat(new String(s.chars())).isEqualTo("second");
    }
  }

  @Test
  void should_explain_non_interactive_options_when_no_source_is_available() {
    PassphraseSource standard = PassphraseSource.standard(Map.of(), Optional.empty());

    assertThat(standard.read()).isEmpty();
    assertThatThrownBy(standard::require)
        .isInstanceOf(PassphraseUnavailableException.class)
        .hasMessageContaining(
            "set JRSCTL_PASSPHRASE or pass --passphrase-file for non-interactive runs");
  }

  @Test
  void should_prefer_env_over_file_when_both_are_given() throws IOException {
    Path f = tmp.resolve("pp");
    Files.writeString(f, "from-file", StandardCharsets.UTF_8);
    PassphraseSource standard =
        PassphraseSource.standard(Map.of("JRSCTL_PASSPHRASE", "from-env"), Optional.of(f));

    try (Secret s = standard.require()) {
      assertThat(new String(s.chars())).isEqualTo("from-env");
    }
  }
}
