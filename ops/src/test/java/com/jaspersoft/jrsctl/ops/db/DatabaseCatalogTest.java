package com.jaspersoft.jrsctl.ops.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.config.Config;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0052: the catalogue questions {@code repository load} asks, per database type. */
class DatabaseCatalogTest {

  private static JdbcSettings settings(Config.DatabaseType type, String url) {
    return new JdbcSettings(type, url, Optional.of("admin"), Optional.empty(), Optional.empty());
  }

  @Test
  void should_point_at_the_maintenance_database_and_ask_pg_database_when_postgresql() {
    DatabaseCatalog catalog =
        DatabaseCatalog.of(
                settings(Config.DatabaseType.POSTGRESQL, "jdbc:postgresql://db:5432/jrs_101"))
            .orElseThrow();

    assertThat(catalog.adminUrl()).isEqualTo("jdbc:postgresql://db:5432/postgres");
    assertThat(catalog.schemaName()).isEqualTo("jrs_101");
    assertThat(catalog.existsSql("jrs_101"))
        .isEqualTo("SELECT 1 FROM pg_database WHERE datname = 'jrs_101'");
    assertThat(catalog.dropSql("jrs_101")).isEqualTo("DROP DATABASE \"jrs_101\"");
  }

  @Test
  void should_keep_query_parameters_of_the_url_when_replacing_the_database_name() {
    DatabaseCatalog catalog =
        DatabaseCatalog.of(
                settings(
                    Config.DatabaseType.POSTGRESQL,
                    "jdbc:postgresql://db:5432/jrs_101?ssl=true&sslmode=require"))
            .orElseThrow();

    assertThat(catalog.adminUrl())
        .isEqualTo("jdbc:postgresql://db:5432/postgres?ssl=true&sslmode=require");
    assertThat(catalog.schemaName()).isEqualTo("jrs_101");
  }

  @Test
  void should_name_the_same_server_when_only_the_query_parameters_differ() {
    DatabaseCatalog configured =
        DatabaseCatalog.of(settings(Config.DatabaseType.MYSQL, "jdbc:mysql://DB:3306/jrs_101"))
            .orElseThrow();
    DatabaseCatalog rendered =
        DatabaseCatalog.of(
                settings(
                    Config.DatabaseType.MYSQL,
                    "jdbc:mysql://db:3306/jrs_101?useUnicode=true&characterEncoding=UTF-8"))
            .orElseThrow();

    assertThat(rendered.sameServerAs(configured)).isTrue();
    assertThat(configured.sameServerAs(rendered)).isTrue();
  }

  @Test
  void should_name_another_server_when_the_host_or_port_differs() {
    DatabaseCatalog configured =
        DatabaseCatalog.of(settings(Config.DatabaseType.MYSQL, "jdbc:mysql://db:3306/jrs_101"))
            .orElseThrow();

    assertThat(
            DatabaseCatalog.of(
                    settings(Config.DatabaseType.MYSQL, "jdbc:mysql://other:3306/jrs_101?a=b"))
                .orElseThrow()
                .sameServerAs(configured))
        .isFalse();
    assertThat(
            DatabaseCatalog.of(settings(Config.DatabaseType.MYSQL, "jdbc:mysql://db:3307/jrs_101"))
                .orElseThrow()
                .sameServerAs(configured))
        .isFalse();
  }

  @Test
  void should_ask_information_schema_and_quote_with_backticks_when_mysql() {
    DatabaseCatalog catalog =
        DatabaseCatalog.of(settings(Config.DatabaseType.MYSQL, "jdbc:mysql://db:3306/jrs_101"))
            .orElseThrow();

    assertThat(catalog.adminUrl()).isEqualTo("jdbc:mysql://db:3306/information_schema");
    assertThat(catalog.existsSql("jrs_101"))
        .isEqualTo("SELECT 1 FROM information_schema.schemata WHERE schema_name = 'jrs_101'");
    assertThat(catalog.dropSql("jrs_101")).isEqualTo("DROP DATABASE `jrs_101`");
  }

  @Test
  void should_be_empty_for_a_database_type_without_a_catalog_rule() {
    assertThat(
            DatabaseCatalog.of(
                settings(Config.DatabaseType.ORACLE, "jdbc:oracle:thin:@db:1521:ORCL")))
        .isEmpty();
    assertThat(
            DatabaseCatalog.of(
                settings(Config.DatabaseType.MSSQL, "jdbc:sqlserver://db:1433;databaseName=jrs")))
        .isEmpty();
  }

  /** The name reaches SQL text, so only a plain identifier is ever accepted. */
  @Test
  void should_refuse_a_schema_name_that_is_not_a_plain_identifier() {
    DatabaseCatalog catalog =
        DatabaseCatalog.of(
                settings(Config.DatabaseType.POSTGRESQL, "jdbc:postgresql://db:5432/jrs_101"))
            .orElseThrow();

    assertThat(DatabaseCatalog.validName("jrs_101")).isTrue();
    assertThat(DatabaseCatalog.validName("jrs-101")).isFalse();
    assertThat(DatabaseCatalog.validName("jrs'; DROP DATABASE x; --")).isFalse();
    assertThat(DatabaseCatalog.validName("")).isFalse();
    assertThatThrownBy(() -> catalog.existsSql("jrs'1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema name");
    assertThatThrownBy(() -> catalog.dropSql("a b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema name");
  }

  @Test
  void should_be_empty_when_the_url_names_no_database() {
    assertThat(
            DatabaseCatalog.of(
                settings(Config.DatabaseType.POSTGRESQL, "jdbc:postgresql://db:5432/")))
        .isEmpty();
  }
}
