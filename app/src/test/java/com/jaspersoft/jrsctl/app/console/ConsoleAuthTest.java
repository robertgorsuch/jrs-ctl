package com.jaspersoft.jrsctl.app.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.OperatorPrompt;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleAuthTest {

  private static final byte[] PASSWORD = "operator-pw".getBytes(StandardCharsets.UTF_8);

  @TempDir Path tmp;

  private ConsoleToken token;

  @BeforeEach
  void issue() throws Exception {
    token =
        ConsoleToken.issue(
            new JrsctlHome(tmp), Platforms.detect(OperatorPrompt.nonInteractive()), new Redactor());
  }

  @AfterEach
  void discard() {
    token.close();
  }

  private ConsoleAuth tokenMode(String bind) {
    return new ConsoleAuth(token, Config.ConsoleAuthMode.TOKEN, Optional.empty(), bind, () -> 7420);
  }

  @Test
  void should_accept_loopback_hosts_and_bound_address_with_matching_port_only() {
    ConsoleAuth auth = tokenMode("127.0.0.1");
    assertThat(auth.hostAllowed("localhost")).isTrue();
    assertThat(auth.hostAllowed("LOCALHOST:7420")).isTrue();
    assertThat(auth.hostAllowed("127.0.0.1:7420")).isTrue();
    assertThat(auth.hostAllowed("[::1]")).isTrue();
    assertThat(auth.hostAllowed("[::1]:7420")).isTrue();
    assertThat(auth.hostAllowed("127.0.0.1:7421")).isFalse();
    assertThat(auth.hostAllowed("evil.example")).isFalse();
    assertThat(auth.hostAllowed("evil.example:7420")).isFalse();
    assertThat(auth.hostAllowed("")).isFalse();
    assertThat(auth.hostAllowed(null)).isFalse();
    assertThat(tokenMode("192.168.1.20").hostAllowed("192.168.1.20:7420")).isTrue();
    assertThat(tokenMode("192.168.1.20").hostAllowed("192.168.1.21:7420")).isFalse();
  }

  @Test
  void should_require_bearer_token_when_mode_is_token() {
    ConsoleAuth auth = tokenMode("127.0.0.1");
    assertThat(auth.authorised("Bearer " + token.text())).isTrue();
    assertThat(auth.authorised("bearer " + token.text())).isTrue();
    assertThat(auth.authorised("Bearer " + token.text() + "x")).isFalse();
    assertThat(auth.authorised("Basic " + basic(token.text(), "operator-pw"))).isFalse();
    assertThat(auth.authorised(null)).isFalse();
    assertThat(auth.authorised("")).isFalse();
  }

  @Test
  void should_require_basic_token_and_password_when_mode_is_local() {
    ConsoleAuth auth =
        new ConsoleAuth(
            token, Config.ConsoleAuthMode.LOCAL, Optional.of(PASSWORD), "127.0.0.1", () -> 7420);
    assertThat(auth.authorised("Basic " + basic(token.text(), "operator-pw"))).isTrue();
    assertThat(auth.authorised("Basic " + basic(token.text(), "wrong"))).isFalse();
    assertThat(auth.authorised("Basic " + basic("not-the-token", "operator-pw"))).isFalse();
    assertThat(auth.authorised("Bearer " + token.text())).isFalse();
    assertThat(auth.authorised("Basic not-base64!")).isFalse();
  }

  @Test
  void should_refuse_local_mode_without_a_password() {
    assertThatThrownBy(
            () ->
                new ConsoleAuth(
                    token, Config.ConsoleAuthMode.LOCAL, Optional.empty(), "127.0.0.1", () -> 7420))
        .isInstanceOf(ConsoleRefusedException.class)
        .hasMessageContaining("console.auth.passwordRef");
  }

  private static String basic(String user, String password) {
    return Base64.getEncoder()
        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }
}
