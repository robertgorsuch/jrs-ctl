package com.jaspersoft.jrsctl.ops.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlScriptTest {

  @Test
  void should_split_on_semicolon_at_line_end_and_drop_comments() {
    String script =
        "-- header comment\n"
            + "CREATE TABLE t (\n"
            + "  id int, -- inline; stays\n"
            + "  name varchar(10)\n"
            + ");\n"
            + "\n"
            + "  -- another comment\n"
            + "INSERT INTO t VALUES (1, 'a;b');\n"
            + "UPDATE t SET name = 'x'\n"
            + "WHERE id = 1;   \n"
            + "SELECT 1";
    assertThat(SqlScript.statements(script))
        .containsExactly(
            "CREATE TABLE t (\n  id int, -- inline; stays\n  name varchar(10)\n)",
            "INSERT INTO t VALUES (1, 'a;b')",
            "UPDATE t SET name = 'x'\nWHERE id = 1",
            "SELECT 1");
  }

  @Test
  void should_return_nothing_when_script_is_only_comments_and_blank_lines() {
    assertThat(SqlScript.statements("-- a\n\n   \n-- b;\n")).isEmpty();
    assertThat(SqlScript.statements(";\n;\n")).isEmpty();
  }
}
