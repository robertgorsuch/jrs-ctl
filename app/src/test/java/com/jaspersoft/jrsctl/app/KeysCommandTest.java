package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.keys.KeyRing;
import com.jaspersoft.jrsctl.core.platform.OperatorPrompt;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.state.StateStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeysCommandTest {

  @TempDir Path tmp;

  private Path home;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(tmp.resolve("home"));
  }

  @Test
  void should_write_owner_only_private_key_and_trust_public_key_when_generate_given()
      throws Exception {
    Path privateOut = tmp.resolve("keys").resolve("customer.key");

    InitCommandTest.Run run =
        InitCommandTest.run(
            "keys",
            "generate",
            "customer",
            "--private-out",
            privateOut.toString(),
            "--home",
            home.toString());

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(privateOut).exists();
    String base64 = Files.readString(privateOut, StandardCharsets.US_ASCII).strip();
    PrivateKey key = Ed25519.decodePrivate(base64);
    assertThat(key.getAlgorithm()).isEqualTo("EdDSA");
    assertThat(Platforms.detect(OperatorPrompt.nonInteractive()).files().isOwnerOnly(privateOut))
        .as("private key file is owner-only")
        .isTrue();
    KeyRing ring = new KeyRing(new JrsctlHome(home));
    assertThat(ring.find("customer")).isPresent();
    assertThat(run.out())
        .contains("generated key customer")
        .contains("file:" + privateOut.toAbsolutePath().normalize())
        .doesNotContain(base64);
    byte[] signature = Ed25519.sign(key, "manifest".getBytes(StandardCharsets.UTF_8));
    assertThat(ring.verify("manifest".getBytes(StandardCharsets.UTF_8), signature)).isPresent();
    try (StateStore store = StateStore.open(new JrsctlHome(home), Clock.systemUTC())) {
      assertThat(store.auditRows(5)).anyMatch(a -> a.action().equals("keys.generate"));
    }
  }

  @Test
  void should_refuse_to_overwrite_private_key_when_file_exists() throws Exception {
    Path privateOut = tmp.resolve("existing.key");
    Files.writeString(privateOut, "keep me");

    InitCommandTest.Run run =
        InitCommandTest.run(
            "keys",
            "generate",
            "customer",
            "--private-out",
            privateOut.toString(),
            "--home",
            home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("already exists");
    assertThat(Files.readString(privateOut)).isEqualTo("keep me");
    assertThat(new KeyRing(new JrsctlHome(home)).find("customer")).isEmpty();
  }

  @Test
  void should_add_list_and_remove_public_key_when_key_file_valid() throws Exception {
    KeyPair pair = Ed25519.generate();
    Path pub = tmp.resolve("partner.pub");
    Files.writeString(pub, Ed25519.encodePublic(pair.getPublic()) + "\n");
    String fingerprint = Ed25519.fingerprint(pair.getPublic());

    InitCommandTest.Run add =
        InitCommandTest.run("keys", "add", "partner", pub.toString(), "--home", home.toString());
    InitCommandTest.Run list = InitCommandTest.run("keys", "list", "--home", home.toString());
    InitCommandTest.Run listJson =
        InitCommandTest.run("keys", "list", "--json", "--home", home.toString());
    InitCommandTest.Run remove =
        InitCommandTest.run("keys", "remove", "partner", "--home", home.toString());
    InitCommandTest.Run removeAgain =
        InitCommandTest.run("keys", "remove", "partner", "--home", home.toString());
    InitCommandTest.Run listAfter = InitCommandTest.run("keys", "list", "--home", home.toString());

    assertThat(add.code()).as(add.err()).isZero();
    assertThat(add.out()).contains("partner").contains(fingerprint);
    assertThat(list.code()).isZero();
    assertThat(list.out()).contains("partner").contains(fingerprint).contains("customer");
    assertThat(listJson.out()).contains("\"name\" : \"partner\"");
    assertThat(remove.code()).isZero();
    assertThat(removeAgain.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(listAfter.out()).doesNotContain("partner");
  }

  @Test
  void should_exit_2_when_public_key_file_is_not_a_key() throws Exception {
    Path bad = tmp.resolve("bad.pub");
    Files.writeString(bad, "not a key");

    InitCommandTest.Run run =
        InitCommandTest.run("keys", "add", "partner", bad.toString(), "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("not an Ed25519 public key");
  }

  @Test
  void should_exit_1_when_publisher_key_is_targeted() {
    InitCommandTest.Run run =
        InitCommandTest.run("keys", "remove", KeyRing.PUBLISHER, "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("publisher");
  }
}
