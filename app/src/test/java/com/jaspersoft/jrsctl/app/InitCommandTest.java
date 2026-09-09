package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class InitCommandTest {

  @TempDir Path tmp;

  static Path fakeLayout(Path install) throws Exception {
    Path tomcat = install.resolve("apache-tomcat");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webapp.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8089\" protocol=\"HTTP/1.1\"/></Service></Server>",
        StandardCharsets.UTF_8);
    Path buildomatic = Files.createDirectories(install.resolve("buildomatic"));
    for (String ext : new String[] {".sh", ".bat"}) {
      Files.writeString(install.resolve("ctlscript" + ext), "", StandardCharsets.UTF_8);
      for (String script : new String[] {"js-export", "js-import", "js-ant"}) {
        Files.writeString(buildomatic.resolve(script + ext), "", StandardCharsets.UTF_8);
      }
    }
    Files.writeString(
        buildomatic.resolve("default_master.properties"),
        "dbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "dbPassword=TopSecret\njs.dbName=jasperserver\n",
        StandardCharsets.UTF_8);
    return install;
  }

  record Run(int code, String out, String err) {}

  static Run run(String... args) {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd =
        new CommandLine(new JrsctlCommand()).setExecutionExceptionHandler(new ExitCodes.Handler());
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    int code = cmd.execute(args);
    return new Run(code, out.toString(), err.toString());
  }

  @Test
  void should_write_schema_valid_config_when_yes_and_install_dir_given() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    Path file = home.resolve("config.yaml");
    assertThat(file).exists();
    String yaml = Files.readString(file, StandardCharsets.UTF_8);
    assertThat(yaml)
        .contains("webappName: jasperserver-pro")
        .contains("baseUrl: http://localhost:8089/jasperserver-pro")
        .contains("passwordRef: env:JRS_PASSWORD")
        .doesNotContain("TopSecret");
    Config loaded = new ConfigLoader().load(new JrsctlHome(home), Map.of(), Map.of());
    assertThat(loaded.server().baseUrl())
        .contains(URI.create("http://localhost:8089/jasperserver-pro"));
    assertThat(loaded.database().type()).contains(Config.DatabaseType.POSTGRESQL);
    assertThat(run.out()).contains("wrote ").contains("server.webappName");
  }

  @Test
  void should_refuse_to_overwrite_when_config_exists_and_no_force() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");
    run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());

    Run second =
        run("init", "--yes", "--home", home.toString(), "--install-dir", install.toString());
    Run forced =
        run(
            "init",
            "--yes",
            "--force",
            "--home",
            home.toString(),
            "--install-dir",
            install.toString());

    assertThat(second.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(second.err()).contains("--force");
    assertThat(forced.code()).isZero();
  }

  @Test
  void should_print_json_report_and_not_write_when_json_without_yes() throws Exception {
    Path install = fakeLayout(tmp.resolve("jrs"));
    Path home = tmp.resolve("home");

    Run run = run("init", "--json", "--home", home.toString(), "--install-dir", install.toString());

    assertThat(run.code()).isZero();
    assertThat(run.out()).contains("\"detectedInstall\" : true").contains("\"values\"");
    assertThat(home.resolve("config.yaml")).doesNotExist();
  }
}
