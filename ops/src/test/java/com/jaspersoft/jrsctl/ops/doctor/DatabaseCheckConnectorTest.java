package com.jaspersoft.jrsctl.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.ReportItem;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.db.FakeJdbcConnector;
import com.jaspersoft.jrsctl.ops.db.JdbcException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseCheckConnectorTest {

  @TempDir Path tmp;

  private Services services(FakeServices fake) throws IOException {
    Path drivers = Files.createDirectories(tmp.resolve("drivers"));
    fake.env.put("DB_PW", "pw");
    return fake.yaml(
            """
            database:
              type: postgresql
              url: jdbc:postgresql://db/jrs
              username: jasperdb
              passwordRef: env:DB_PW
              driverDir: %s
            """
                .formatted(drivers.toAbsolutePath().toString().replace('\\', '/')))
        .build();
  }

  @Test
  void should_pass_when_connector_answers_probe_with_a_row() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      FakeJdbcConnector jdbc = new FakeJdbcConnector();
      ReportItem item = DatabaseCheck.check(services(fake), jdbc);
      assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(item.detail()).contains("FakeDB 1.0").contains("as jasperdb");
      assertThat(jdbc.executed).containsExactly("SELECT 1");
      assertThat(jdbc.connections).containsExactly("jdbc:postgresql://db/jrs as jasperdb");
    }
  }

  /**
   * ADR-0052: in a tools-only configuration the configured URL names the schema that is about to be
   * created, so the check asks the database server's catalogue instead of the schema itself.
   */
  @Test
  void should_probe_the_catalog_and_say_whether_the_schema_exists_when_tools_only()
      throws IOException {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      Path drivers = Files.createDirectories(tmp.resolve("drivers"));
      Path buildomatic = Files.createDirectories(tmp.resolve("buildomatic"));
      fake.env.put("DB_PW", "pw");
      Services services =
          fake.yaml(
                  """
                  server:
                    baseUrl: http://jrs:8080/jasperserver-pro
                    buildomaticDir: %s
                  database:
                    type: postgresql
                    url: jdbc:postgresql://db:5432/jasperserver_101
                    username: postgres
                    passwordRef: env:DB_PW
                    driverDir: %s
                  """
                      .formatted(
                          buildomatic.toAbsolutePath().toString().replace('\\', '/'),
                          drivers.toAbsolutePath().toString().replace('\\', '/')))
              .build();
      FakeJdbcConnector jdbc = new FakeJdbcConnector();
      jdbc.rowAnswer = sql -> !sql.contains("pg_database");

      ReportItem item = DatabaseCheck.check(services, jdbc);

      assertThat(item.status()).as(item.toString()).isEqualTo(ReportItem.Status.PASS);
      assertThat(item.detail())
          .contains("FakeDB 1.0")
          .contains("jasperserver_101")
          .contains("does not exist yet");
      assertThat(jdbc.connections)
          .containsExactly("jdbc:postgresql://db:5432/postgres as postgres");
      assertThat(jdbc.executed).anyMatch(sql -> sql.contains("pg_database"));
    }
  }

  @Test
  void should_fail_with_driver_remediation_when_no_driver_matches() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      FakeJdbcConnector jdbc = new FakeJdbcConnector();
      jdbc.connectFailure =
          Optional.of(
              new JdbcException(JdbcException.Kind.NO_MATCHING_DRIVER, "no driver accepts x"));
      ReportItem item = DatabaseCheck.check(services(fake), jdbc);
      assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(item.detail()).isEqualTo("no driver accepts x");
      assertThat(item.remediation()).contains("driver jar matches database.type");
    }
  }
}
