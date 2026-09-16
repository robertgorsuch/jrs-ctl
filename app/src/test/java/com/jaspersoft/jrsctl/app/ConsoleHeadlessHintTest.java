package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.platform.Platform;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #64: a server without a desktop is told how to reach the console over SSH. */
class ConsoleHeadlessHintTest {

  private static final URI URL = URI.create("http://127.0.0.1:7420/#token=abc");

  @Test
  void should_print_the_ssh_tunnel_command_when_linux_has_no_display() {
    assertThat(
            ConsoleCommand.headlessHint(
                Platform.OsFamily.LINUX, Map.of("USER", "jrsadmin", "HOSTNAME", "jrs01"), URL))
        .hasValueSatisfying(
            hint ->
                assertThat(hint)
                    .contains("ssh -L 7420:127.0.0.1:7420 jrsadmin@jrs01")
                    .contains("Console URL"));
  }

  @Test
  void should_use_the_actual_port_and_placeholders_when_user_and_host_are_unknown() {
    assertThat(
            ConsoleCommand.headlessHint(
                Platform.OsFamily.LINUX, Map.of(), URI.create("http://127.0.0.1:50123/#token=x")))
        .hasValueSatisfying(
            hint -> assertThat(hint).contains("ssh -L 50123:127.0.0.1:50123 <user>@<this-server>"));
  }

  @Test
  void should_give_no_hint_when_linux_has_a_display() {
    assertThat(ConsoleCommand.headlessHint(Platform.OsFamily.LINUX, Map.of("DISPLAY", ":0"), URL))
        .isEmpty();
    assertThat(
            ConsoleCommand.headlessHint(
                Platform.OsFamily.LINUX, Map.of("WAYLAND_DISPLAY", "wayland-0"), URL))
        .isEmpty();
  }

  @Test
  void should_give_no_hint_on_windows() {
    assertThat(ConsoleCommand.headlessHint(Platform.OsFamily.WINDOWS, Map.of(), URL)).isEmpty();
  }

  @Test
  void should_give_no_hint_when_the_console_is_bound_to_a_network_address() {
    assertThat(
            ConsoleCommand.headlessHint(
                Platform.OsFamily.LINUX, Map.of(), URI.create("https://10.0.0.5:7420/#token=x")))
        .isEmpty();
  }
}
