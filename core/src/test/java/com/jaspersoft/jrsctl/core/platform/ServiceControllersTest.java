package com.jaspersoft.jrsctl.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.platform.FakeProcessRunner.Response;
import com.jaspersoft.jrsctl.core.platform.ServiceController.State;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceControllersTest {

  private static final Duration POLL = Duration.ofMillis(10);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String SERVICE = "jasperreportsTomcat";
  private static final List<String> SC_QUERY = List.of("sc.exe", "query", SERVICE);
  private static final List<String> SC_STOP = List.of("sc.exe", "stop", SERVICE);
  private static final List<String> SC_START = List.of("sc.exe", "start", SERVICE);

  private static Response scState(String state) {
    return Response.ok(
        "SERVICE_NAME: " + SERVICE,
        "        TYPE               : 10  WIN32_OWN_PROCESS",
        "        STATE              : 3  " + state,
        "                                (STOPPABLE, NOT_PAUSABLE, ACCEPTS_SHUTDOWN)");
  }

  @Test
  void should_return_stopped_when_windows_service_passes_through_stop_pending() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                SC_QUERY,
                scState("RUNNING"),
                scState("STOP_PENDING"),
                scState("STOP_PENDING"),
                scState("STOPPED"))
            .on(SC_STOP, Response.ok("[SC] ControlService SUCCESS"));
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    State result = controller.stop(TIMEOUT);

    assertThat(result).isEqualTo(State.STOPPED);
    assertThat(runner.countOf(SC_STOP)).isEqualTo(1);
    assertThat(runner.countOf(SC_QUERY)).isGreaterThanOrEqualTo(4);
    assertThat(controller.describe()).isEqualTo("Windows service " + SERVICE);
  }

  @Test
  void should_return_last_state_when_windows_service_never_finishes_stopping() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(SC_QUERY, scState("RUNNING"), scState("STOP_PENDING"))
            .on(SC_STOP, Response.ok());
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    assertThat(controller.stop(Duration.ofMillis(80))).isEqualTo(State.STOPPING);
  }

  @Test
  void should_return_running_when_windows_service_passes_through_start_pending() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(SC_QUERY, scState("STOPPED"), scState("START_PENDING"), scState("RUNNING"))
            .on(SC_START, Response.ok());
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(runner.countOf(SC_START)).isEqualTo(1);
  }

  @Test
  void should_report_unknown_when_windows_service_does_not_exist() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                SC_QUERY,
                Response.failing(1060, "[SC] EnumQueryServicesStatus:OpenService FAILED"));

    assertThat(new WindowsServiceController(runner, SERVICE, POLL).state())
        .isEqualTo(State.UNKNOWN);
  }

  @Test
  void should_return_stopped_when_systemd_unit_passes_through_deactivating() {
    List<String> isActive = List.of("systemctl", "is-active", "jasperserver");
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                isActive,
                Response.ok("active"),
                Response.failing(3, "deactivating"),
                Response.failing(3, "inactive"))
            .on(List.of("systemctl", "stop", "jasperserver"), Response.ok());
    SystemdServiceController controller =
        new SystemdServiceController(runner, "jasperserver", POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(runner.countOf(List.of("systemctl", "stop", "jasperserver"))).isEqualTo(1);
    assertThat(controller.describe()).isEqualTo("systemd unit jasperserver");
  }

  @Test
  void should_return_running_when_systemd_unit_passes_through_activating() {
    List<String> isActive = List.of("systemctl", "is-active", "jasperserver");
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                isActive,
                Response.failing(3, "inactive"),
                Response.failing(3, "activating"),
                Response.ok("active"))
            .on(List.of("systemctl", "start", "jasperserver"), Response.ok());

    assertThat(new SystemdServiceController(runner, "jasperserver", POLL).start(TIMEOUT))
        .isEqualTo(State.RUNNING);
  }

  @Test
  void should_stop_via_ctlscript_when_tomcat_process_disappears(@TempDir Path install) {
    Path script = install.resolve("ctlscript.sh");
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(running, running, List.of()));
    FakeProcessRunner runner = new FakeProcessRunner();
    List<String> stopCommand =
        List.of(script.toAbsolutePath().normalize().toString(), "stop", "tomcat");
    runner.on(stopCommand, Response.ok("Stopped tomcat"));
    ScriptServiceController controller =
        new ScriptServiceController(runner, ServiceConfig.Kind.CTLSCRIPT, script, finder, POLL);

    assertThat(controller.state()).isEqualTo(State.RUNNING);
    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(runner.invocations()).containsExactly(stopCommand);
    assertThat(controller.watchedDir()).isEqualTo(install.toAbsolutePath().normalize());
    assertThat(controller.describe()).startsWith("ctlscript ");
  }

  @Test
  void should_start_via_catalina_script_when_tomcat_process_appears(@TempDir Path install) {
    Path script = install.resolve("apache-tomcat").resolve("bin").resolve("catalina.sh");
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(List.of(), List.of(), running));
    FakeProcessRunner runner = new FakeProcessRunner();
    List<String> startCommand = List.of(script.toAbsolutePath().normalize().toString(), "start");
    runner.on(startCommand, Response.ok());
    ScriptServiceController controller =
        new ScriptServiceController(runner, ServiceConfig.Kind.CATALINA, script, finder, POLL);

    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(runner.invocations()).containsExactly(startCommand);
    assertThat(controller.watchedDir())
        .isEqualTo(install.resolve("apache-tomcat").toAbsolutePath().normalize());
  }

  @Test
  void should_ignore_tomcats_of_other_installs_when_deriving_script_state(
      @TempDir Path install, @TempDir Path other) {
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(List.of(FakeTomcatProcessFinder.tomcatUnder(other))));
    ScriptServiceController controller =
        new ScriptServiceController(
            new FakeProcessRunner(),
            ServiceConfig.Kind.CTLSCRIPT,
            install.resolve("ctlscript.sh"),
            finder,
            POLL);

    assertThat(controller.state()).isEqualTo(State.STOPPED);
  }

  @Test
  void should_return_unknown_immediately_when_manual_kind_runs_non_interactively(
      @TempDir Path install) {
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    List<String> instructions = new ArrayList<>();
    OperatorPrompt prompt = recordingPrompt(instructions, false);
    ManualServiceController controller =
        new ManualServiceController(
            new FakeProcessRunner(),
            prompt,
            Optional.of(install),
            new FakeTomcatProcessFinder(List.of(running)),
            POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.UNKNOWN);
    assertThat(instructions).isEmpty();
  }

  @Test
  void should_instruct_operator_and_poll_when_manual_kind_is_interactive(@TempDir Path install) {
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    List<String> instructions = new ArrayList<>();
    ManualServiceController controller =
        new ManualServiceController(
            new FakeProcessRunner(),
            recordingPrompt(instructions, true),
            Optional.of(install),
            new FakeTomcatProcessFinder(List.of(running, running, running, List.of())),
            POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(instructions).hasSize(1);
    assertThat(instructions.get(0)).contains("Stop").contains(install.toAbsolutePath().toString());
    assertThat(controller.describe()).startsWith("manual");
  }

  @Test
  void should_parse_sc_and_systemctl_states_when_given_raw_output() {
    assertThat(WindowsServiceController.parseState(List.of("STATE : 4 RUNNING")))
        .isEqualTo(State.RUNNING);
    assertThat(WindowsServiceController.parseState(List.of("  STATE   : 2  START_PENDING")))
        .isEqualTo(State.STARTING);
    assertThat(WindowsServiceController.parseState(List.of("nothing"))).isEqualTo(State.UNKNOWN);
    assertThat(SystemdServiceController.parseState(List.of("failed"))).isEqualTo(State.STOPPED);
    assertThat(SystemdServiceController.parseState(List.of("", "activating")))
        .isEqualTo(State.STARTING);
    assertThat(SystemdServiceController.parseState(List.of())).isEqualTo(State.UNKNOWN);
  }

  private static OperatorPrompt recordingPrompt(List<String> sink, boolean interactive) {
    return new OperatorPrompt() {
      @Override
      public void instruct(String message) {
        sink.add(message);
      }

      @Override
      public boolean interactive() {
        return interactive;
      }
    };
  }
}
