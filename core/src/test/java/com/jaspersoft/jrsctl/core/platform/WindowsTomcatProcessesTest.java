package com.jaspersoft.jrsctl.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.platform.FakeProcessRunner.Response;
import com.jaspersoft.jrsctl.core.platform.ServiceController.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #38: on Windows the JDK returns no process command lines, so a catalina, ctlscript or
 * manual Tomcat always looked stopped and the stop before vendor tools and jar swaps was skipped.
 * These tests drive the Win32_Process scan through a scripted runner, so they run on either OS.
 */
class WindowsTomcatProcessesTest {

  private static final Duration POLL = Duration.ofMillis(10);
  private static final String ROOT = "C:\\Windows";

  private static String b64(String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String row(long pid, String name, String exe, String commandLine, String ports) {
    return "P "
        + pid
        + " "
        + name
        + " "
        + (exe == null ? "-" : b64(exe))
        + " "
        + (commandLine == null ? "-" : b64(commandLine))
        + " "
        + (ports == null ? "-" : ports);
  }

  private static String catalinaCommandLine(Path tomcat) {
    return "\"C:\\jrs\\java\\bin\\java.exe\" -Djava.util.logging.config.file=\""
        + tomcat.resolve("conf").resolve("logging.properties")
        + "\" -classpath \""
        + tomcat.resolve("bin").resolve("bootstrap.jar")
        + "\" -Dcatalina.base=\""
        + tomcat
        + "\" -Dcatalina.home=\""
        + tomcat
        + "\" org.apache.catalina.startup.Bootstrap start";
  }

  /** A Tomcat directory whose server.xml declares shutdown port 8006 and HTTP port 8082. */
  private static Path tomcatWithPorts(Path install) throws IOException {
    Path tomcat = install.resolve("apache-tomcat").toAbsolutePath().normalize();
    Files.createDirectories(tomcat.resolve("conf"));
    Files.createDirectories(tomcat.resolve("bin"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server port=\"8006\" shutdown=\"SHUTDOWN\">\n"
            + "  <Service name=\"Catalina\">\n"
            + "    <Connector port=\"8082\" protocol=\"HTTP/1.1\"/>\n"
            + "  </Service>\n"
            + "</Server>\n",
        StandardCharsets.UTF_8);
    return tomcat;
  }

  private static ScriptServiceController catalina(
      FakeProcessRunner runner, WindowsTomcatProcesses finder, Path tomcat) {
    return new ScriptServiceController(
        runner,
        ServiceConfig.Kind.CATALINA,
        tomcat.resolve("bin").resolve("catalina.bat"),
        finder,
        POLL);
  }

  @Test
  void should_report_running_when_the_win32_process_scan_shows_the_watched_tomcat(
      @TempDir Path install) throws IOException {
    Path tomcat = tomcatWithPorts(install);
    FakeProcessRunner runner = new FakeProcessRunner();
    WindowsTomcatProcesses finder = new WindowsTomcatProcesses(runner, ROOT);
    runner.on(
        finder.command(),
        Response.ok(
            row(
                35260,
                "java.exe",
                "C:\\jrs\\java\\bin\\java.exe",
                catalinaCommandLine(tomcat),
                "8006,8082"),
            WindowsTomcatProcesses.END));

    List<TomcatProcessFinder.TomcatProcess> found = finder.find();

    assertThat(found).hasSize(1);
    assertThat(found.get(0).catalinaBase()).contains(tomcat);
    assertThat(found.get(0).listeningPorts()).containsExactlyInAnyOrder(8006, 8082);
    assertThat(catalina(runner, finder, tomcat).state()).isEqualTo(State.RUNNING);
  }

  /**
   * The development host runs an unrelated Java service under another account on port 8080: its
   * command line is unreadable, but it holds none of the watched Tomcat's ports, so a stopped
   * Tomcat must read as stopped, not unknown.
   */
  @Test
  void should_report_stopped_when_only_an_unreadable_jvm_on_unrelated_ports_runs(
      @TempDir Path install) throws IOException {
    Path tomcat = tomcatWithPorts(install);
    FakeProcessRunner runner = new FakeProcessRunner();
    WindowsTomcatProcesses finder = new WindowsTomcatProcesses(runner, ROOT);
    runner.on(
        finder.command(),
        Response.ok(row(9916, "java.exe", null, null, "8080"), WindowsTomcatProcesses.END));

    assertThat(catalina(runner, finder, tomcat).state()).isEqualTo(State.STOPPED);
  }

  @Test
  void should_report_unknown_when_an_unreadable_jvm_holds_a_watched_port(@TempDir Path install)
      throws IOException {
    Path tomcat = tomcatWithPorts(install);
    FakeProcessRunner runner = new FakeProcessRunner();
    WindowsTomcatProcesses finder = new WindowsTomcatProcesses(runner, ROOT);
    runner.on(
        finder.command(),
        Response.ok(row(4040, "java.exe", null, null, "8082"), WindowsTomcatProcesses.END));

    assertThat(catalina(runner, finder, tomcat).state()).isEqualTo(State.UNKNOWN);
  }

  @Test
  void should_classify_unreadable_jvms_as_opaque_and_leave_out_everything_else() {
    List<String> lines =
        List.of(
            "",
            row(11, "java.exe", null, null, "8080"),
            row(12, "javaw.exe", "C:\\tools\\java\\bin\\javaw.exe", "javaw -jar ide.jar", "-"),
            row(13, "tomcat10.exe", null, null, "-"),
            row(14, "tomcat10.exe", "C:\\jrs\\apache-tomcat\\bin\\tomcat10.exe", null, "8005,8081"),
            "P not-a-pid java.exe - - -",
            "P 15 java.exe - -",
            "garbage line",
            row(99, "java.exe", null, null, "-"),
            WindowsTomcatProcesses.END);

    List<TomcatProcessFinder.TomcatProcess> found = WindowsTomcatProcesses.parse(lines, 99);

    assertThat(found).extracting(TomcatProcessFinder.TomcatProcess::pid).containsExactly(11L, 14L);
    assertThat(found.get(0).opaque()).isTrue();
    assertThat(found.get(0).listeningPorts()).containsExactly(8080);
    assertThat(found.get(1).opaque()).isFalse();
    assertThat(found.get(1).listeningPorts()).containsExactlyInAnyOrder(8005, 8081);
    assertThat(found.get(1).catalinaHome())
        .contains(Path.of("C:\\jrs\\apache-tomcat").toAbsolutePath().normalize());
  }

  @Test
  void should_decode_paths_outside_the_console_code_page() {
    String commandLine =
        "java -Dcatalina.base=\"D:\\Jaspersoft Überprüfung\\apache-tomcat\""
            + " org.apache.catalina.startup.Bootstrap start";

    List<TomcatProcessFinder.TomcatProcess> found =
        WindowsTomcatProcesses.parse(List.of(row(7, "java.exe", null, commandLine, "-")), 1);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).commandLine()).contains("Überprüfung");
  }

  @Test
  void should_throw_rather_than_report_nothing_when_the_scan_cannot_run() {
    FakeProcessRunner failing = new FakeProcessRunner();
    WindowsTomcatProcesses exits = new WindowsTomcatProcesses(failing, ROOT);
    failing.on(exits.command(), Response.failing(1, "Get-CimInstance : Access denied"));

    FakeProcessRunner truncated = new FakeProcessRunner();
    WindowsTomcatProcesses cutOff = new WindowsTomcatProcesses(truncated, ROOT);
    truncated.on(cutOff.command(), Response.ok(row(11, "java.exe", null, null, "-")));

    assertThatThrownBy(exits::find)
        .isInstanceOf(TomcatScanException.class)
        .hasMessageContaining("exit 1");
    assertThatThrownBy(cutOff::find).isInstanceOf(TomcatScanException.class);
  }

  @Test
  void should_run_powershell_by_absolute_path_with_a_fixed_encoded_script() {
    List<String> command = WindowsTomcatProcesses.command("D:\\WINDOWS");

    assertThat(command.get(0))
        .isEqualTo("D:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
    assertThat(command).contains("-NoProfile", "-NonInteractive", "-EncodedCommand");
    String script =
        new String(
            Base64.getDecoder().decode(command.get(command.size() - 1)), StandardCharsets.UTF_16LE);
    assertThat(script)
        .contains("Win32_Process")
        .contains("Get-NetTCPConnection")
        .contains("'" + WindowsTomcatProcesses.END + "'");
    assertThat(WindowsTomcatProcesses.command(null).get(0)).startsWith("C:\\Windows\\");
  }

  @Test
  void should_report_unknown_not_stopped_when_the_scan_fails_or_an_opaque_jvm_may_be_the_tomcat(
      @TempDir Path install) {
    TomcatProcessFinder blind =
        () -> {
          throw new TomcatScanException("no scan");
        };
    TomcatProcessFinder.TomcatProcess opaqueOn8082 =
        new TomcatProcessFinder.TomcatProcess(
            11, "", Optional.empty(), Optional.empty(), Optional.empty(), Set.of(8082));
    TomcatProcessFinder.TomcatProcess ours = FakeTomcatProcessFinder.tomcatUnder(install);
    Set<Integer> watched = Set.of(8006, 8082);

    assertThat(TomcatState.of(blind, Optional.of(install), watched)).isEqualTo(State.UNKNOWN);
    assertThat(TomcatState.of(() -> List.of(opaqueOn8082), Optional.of(install), watched))
        .isEqualTo(State.UNKNOWN);
    assertThat(TomcatState.of(() -> List.of(opaqueOn8082), Optional.of(install), Set.of(9000)))
        .isEqualTo(State.STOPPED);
    assertThat(TomcatState.of(() -> List.of(opaqueOn8082), Optional.of(install), Set.of()))
        .as("ports unknown: stay conservative")
        .isEqualTo(State.UNKNOWN);
    assertThat(TomcatState.of(() -> List.of(opaqueOn8082, ours), Optional.of(install), watched))
        .isEqualTo(State.RUNNING);
    assertThat(TomcatState.of(List::of, Optional.of(install), watched)).isEqualTo(State.STOPPED);
  }
}
