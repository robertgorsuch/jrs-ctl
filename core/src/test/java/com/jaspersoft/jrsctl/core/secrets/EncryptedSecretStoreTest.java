package com.jaspersoft.jrsctl.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedSecretStoreTest {

  @TempDir Path tmp;

  private static PassphraseSource passphrase(String text) {
    return new PassphraseSource.Fixed(Secret.fromString(text));
  }

  private EncryptedSecretStore store(String passphrase, String machine) {
    return new EncryptedSecretStore(tmp.resolve("secrets.enc"), passphrase(passphrase), machine);
  }

  @Test
  void should_write_documented_layout_when_initialised() throws IOException {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    JsonNode root;
    try (InputStream in = Files.newInputStream(store.file())) {
      root = new ObjectMapper().readTree(in);
    }
    assertThat(root.get("version").asInt()).isEqualTo(1);
    assertThat(root.get("kdf").asText()).isEqualTo("PBKDF2WithHmacSHA256");
    assertThat(root.get("iterations").asInt()).isEqualTo(600_000);
    assertThat(root.get("salt").asText()).isNotBlank();
    assertThat(root.get("entries").isObject()).isTrue();
    assertThat(store.list()).isEmpty();
  }

  @Test
  void should_round_trip_set_get_list_remove_when_passphrase_matches() {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    try (Secret value = Secret.fromString("s3cr3t-value")) {
      store.set("db", value);
    }
    assertThat(store.list()).containsExactly("db");

    try (Secret got = store.get("db").orElseThrow()) {
      assertThat(new String(got.chars())).isEqualTo("s3cr3t-value");
    }
    assertThat(store.get("missing")).isEmpty();
    assertThat(store.remove("db")).isTrue();
    assertThat(store.remove("db")).isFalse();
    assertThat(store.list()).isEmpty();

    String raw;
    try {
      raw = Files.readString(store.file(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    assertThat(raw).doesNotContain("s3cr3t-value");
  }

  @Test
  void should_keep_ciphertext_out_of_file_when_secret_is_stored() throws IOException {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();
    try (Secret value = Secret.fromString("plain-text-marker")) {
      store.set("db", value);
    }

    assertThat(Files.readString(store.file(), StandardCharsets.UTF_8))
        .doesNotContain("plain-text-marker")
        .doesNotContain("pp\"");
  }

  @Test
  void should_reject_wrong_passphrase_without_revealing_it_when_unlocking() {
    store("alpha-pass-1", "host-a").init();
    EncryptedSecretStore wrong = store("beta-pass-2", "host-a");

    assertThatThrownBy(() -> wrong.get("db"))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("passphrase does not unlock")
        .satisfies(
            t ->
                assertThat(t.getMessage())
                    .doesNotContain("alpha-pass-1")
                    .doesNotContain("beta-pass-2"));
  }

  @Test
  void should_refuse_to_unlock_when_store_is_moved_to_another_machine() {
    EncryptedSecretStore origin = store("pp", "host-a");
    origin.init();
    try (Secret value = Secret.fromString("value")) {
      origin.set("db", value);
    }
    EncryptedSecretStore elsewhere = store("pp", "host-b");

    assertThatThrownBy(() -> elsewhere.get("db")).isInstanceOf(SecretException.class);
  }

  @Test
  void should_refuse_to_overwrite_when_already_initialised() {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    assertThatThrownBy(store::init)
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void should_point_at_secrets_init_when_file_is_missing() {
    EncryptedSecretStore store = store("pp", "host-a");

    assertThatThrownBy(store::list)
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("jrsctl secrets init");
  }

  @Test
  void should_reject_invalid_entry_names_when_setting() {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    try (Secret value = Secret.fromString("v")) {
      assertThatThrownBy(() -> store.set("has space", value))
          .isInstanceOf(SecretException.class)
          .hasMessageContaining("invalid secret name");
    }
  }

  @Test
  void should_not_need_passphrase_when_listing_or_removing() {
    store("pp", "host-a").init();
    EncryptedSecretStore noPassphrase =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"),
            PassphraseSource.standard(java.util.Map.of(), java.util.Optional.empty()),
            "host-a");

    assertThat(noPassphrase.list()).isEmpty();
    assertThat(noPassphrase.remove("nothing")).isFalse();
    assertThatThrownBy(() -> noPassphrase.get("x"))
        .isInstanceOf(PassphraseUnavailableException.class);
  }
}
