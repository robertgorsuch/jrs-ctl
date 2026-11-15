package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsctl.core.secrets.PassphraseSource;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.ops.ReportItem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #308, ADR-0058: {@code --fail-on} and {@code --junit} on the report commands. */
class GateOptionsTest {

  @TempDir Path tmp;

  @AfterEach
  void reset() {
    TestAdapterFactory.unreachable = false;
    TestAdapterFactory.adapter = new AppFakeAdapter();
  }

  private static final List<ReportItem> WARNED =
      List.of(ReportItem.warn("disk", "low", "free space"), ReportItem.pass("server", "up"));

  private static GateOptions gate(GateOptions.FailOn failOn) {
    GateOptions g = new GateOptions();
    g.failOn = failOn;
    return g;
  }

  @Test
  void should_keep_the_report_exit_when_fail_on_is_the_default() {
    assertThat(gate(GateOptions.FailOn.FAIL).exit(0, WARNED)).isZero();
  }

  @Test
  void should_exit_2_when_fail_on_warn_meets_a_warn() {
    assertThat(gate(GateOptions.FailOn.WARN).exit(0, WARNED)).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(gate(GateOptions.FailOn.WARN).exit(0, List.of(ReportItem.pass("a", "b")))).isZero();
  }

  @Test
  void should_never_lower_or_replace_a_non_zero_report_exit() {
    assertThat(gate(GateOptions.FailOn.WARN).exit(ExitCodes.UNSUPPORTED, WARNED))
        .isEqualTo(ExitCodes.UNSUPPORTED);
  }

  private Path home(Path install) throws Exception {
    Path home = Files.createDirectories(tmp.resolve("home"));
    JrsctlHome jh = new JrsctlHome(home);
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            jh.secretsFile(), new PassphraseSource.Fixed(Secret.fromString("pp-for-tests")));
    store.init();
    store.set("jrs", Secret.fromString("pw-for-tests"));
    Files.writeString(tmp.resolve("pp.txt"), "pp-for-tests", StandardCharsets.UTF_8);
    TestFiles.ownerOnly(tmp.resolve("pp.txt"));
    Files.writeString(
        jh.configFile(),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          auth:
            username: jasperadmin
            passwordRef: enc:jrs
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(install.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    return home;
  }

  /** {@code service.kind: manual} is always a WARN item, so the gate always applies here. */
  @Test
  void should_exit_2_and_write_junit_with_the_warn_as_failure_when_doctor_fails_on_warn()
      throws Exception {
    Path home = home(InitCommandTest.fakeLayout(tmp.resolve("jrs")));
    Path junit = tmp.resolve("reports").resolve("doctor.xml");
    Files.createDirectories(junit.getParent());

    InitCommandTest.Run run =
        InitCommandTest.run(
            "doctor",
            "--json",
            "--fail-on",
            "warn",
            "--junit",
            junit.toString(),
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    assertThat(run.code()).as(run.out() + run.err()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(new ObjectMapper().readTree(run.out()).get("exitCode").asInt())
        .isEqualTo(ExitCodes.PRECHECK_FAILED);
    String xml = Files.readString(junit, StandardCharsets.UTF_8);
    assertThat(xml)
        .contains("<testsuite name=\"jrsctl doctor\"")
        .containsPattern("name=\"service\" time=\"0\">\\s*<failure type=\"WARN\"")
        .doesNotContain("pw-for-tests");
    assertThat(junit.resolveSibling("doctor.xml.tmp")).doesNotExist();
  }

  @Test
  void should_write_a_warn_as_a_pass_with_its_text_when_fail_on_is_the_default() throws Exception {
    Path home = home(InitCommandTest.fakeLayout(tmp.resolve("jrs")));
    Path junit = tmp.resolve("doctor.xml");

    InitCommandTest.Run run =
        InitCommandTest.run(
            "doctor",
            "--junit",
            junit.toString(),
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    assertThat(run.code()).as(run.out() + run.err()).isIn(ExitCodes.SUCCESS, 2);
    assertThat(Files.readString(junit, StandardCharsets.UTF_8))
        .containsPattern("name=\"service\" time=\"0\">\\s*<system-out>WARN: ");
  }

  @Test
  void should_exit_2_and_say_so_when_the_junit_file_cannot_be_written() throws Exception {
    Path home = home(InitCommandTest.fakeLayout(tmp.resolve("jrs")));
    Path junit = tmp.resolve("no-such-dir").resolve("doctor.xml");

    InitCommandTest.Run run =
        InitCommandTest.run(
            "smoke",
            "--junit",
            junit.toString(),
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    assertThat(run.code()).as(run.out() + run.err()).isNotZero();
    assertThat(run.err()).contains("cannot write the JUnit report");
  }

  @Test
  void should_keep_exit_6_when_fail_on_warn_meets_an_unsupported_version() throws Exception {
    Path home = home(InitCommandTest.fakeLayout(tmp.resolve("jrs")));
    TestAdapterFactory.adapter.version = "6.4.0";
    String[] base = {
      "doctor", "--home", home.toString(), "--passphrase-file", tmp.resolve("pp.txt").toString()
    };

    InitCommandTest.Run plain = InitCommandTest.run(base);
    String[] gated = new String[base.length + 2];
    System.arraycopy(base, 0, gated, 0, base.length);
    gated[base.length] = "--fail-on";
    gated[base.length + 1] = "warn";
    InitCommandTest.Run withGate = InitCommandTest.run(gated);

    assertThat(plain.code()).isNotZero();
    assertThat(withGate.code()).isEqualTo(plain.code());
  }
}
