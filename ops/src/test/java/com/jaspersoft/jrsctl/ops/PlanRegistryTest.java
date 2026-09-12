package com.jaspersoft.jrsctl.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations;
import com.jaspersoft.jrsctl.ops.upgrade.UpgradeOperations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The arguments of every mutating operation, written to the {@code plans} table and read back when
 * the console or {@code runs recover} rebuilds a plan. A field that is written but not read, or
 * read under another name, turns a resumed run into a different run than the one the operator
 * confirmed, and nothing else in the suite would notice.
 */
class PlanRegistryTest {

  @Test
  void should_round_trip_hotfix_apply_arguments() throws IOException {
    JsonNode args = tree(PlanRegistry.applyArgs(Path.of("bundles", "hf-1.zip"), true));

    assertThat(args.get("bundle").asText()).endsWith("hf-1.zip");
    assertThat(Path.of(args.get("bundle").asText()).isAbsolute())
        .as("a resumed run must not depend on the working directory")
        .isTrue();
    assertThat(args.get("allowUnsigned").asBoolean()).isTrue();
  }

  @Test
  void should_round_trip_hotfix_rollback_arguments() throws IOException {
    JsonNode args = tree(PlanRegistry.rollbackArgs("hf-2024-01", true));

    assertThat(args.get("id").asText()).isEqualTo("hf-2024-01");
    assertThat(args.get("cascade").asBoolean()).isTrue();
  }

  @Test
  void should_round_trip_every_export_flag() throws IOException {
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            Set.of("/public", "/organizations"),
            true,
            false,
            true,
            false,
            true,
            false,
            Path.of("out", "export.zip"),
            Optional.of(ExportImportStrategy.Kind.REST));

    JsonNode args = tree(PlanRegistry.exportArgs(options));

    assertThat(args.get("usersRoles").asBoolean()).isTrue();
    assertThat(args.get("accessEvents").asBoolean()).isFalse();
    assertThat(args.get("auditEvents").asBoolean()).isTrue();
    assertThat(args.get("monitoring").asBoolean()).isFalse();
    assertThat(args.get("settings").asBoolean()).isTrue();
    assertThat(args.get("fullServer").asBoolean()).isFalse();
    assertThat(args.get("out").asText()).endsWith("export.zip");
    assertThat(args.get("strategy").asText()).isEqualTo("rest");
    assertThat(args.get("uris")).hasSize(2);
  }

  @Test
  void should_round_trip_every_import_flag_including_the_keystore() throws IOException {
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
            Path.of("in", "import.zip"),
            true,
            false,
            true,
            false,
            true,
            false,
            true,
            Optional.of(Path.of("keys", "source.jrsks")),
            Optional.of(SecretRef.parse("env:KEYSTORE_PASSWORD")),
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));

    JsonNode args = tree(PlanRegistry.importArgs(options));

    assertThat(args.get("archive").asText()).endsWith("import.zip");
    assertThat(args.get("update").asBoolean()).isTrue();
    assertThat(args.get("skipUserUpdate").asBoolean()).isFalse();
    assertThat(args.get("skipThemes").asBoolean()).isTrue();
    assertThat(args.get("sourceKeystore").asText()).endsWith("source.jrsks");
    assertThat(args.get("sourceKeystorePassword").asText()).isEqualTo("env:KEYSTORE_PASSWORD");
    assertThat(args.get("strategy").asText()).isEqualTo("vendor");
  }

  @Test
  void should_round_trip_upgrade_arguments() throws IOException {
    JsonNode rollback =
        tree(PlanRegistry.upgradeRollbackArgs("r-2026", UpgradeOperations.RollbackPoint.B));

    assertThat(rollback.get("runId").asText()).isEqualTo("r-2026");
    assertThat(rollback.get("point").asText()).isNotBlank();
  }

  @Test
  void should_refuse_an_operation_this_build_cannot_rebuild() {
    PlanRegistry registry =
        new PlanRegistry(
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            });

    assertThatThrownBy(() -> registry.rebuild("hotfix.teleport", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown operation")
        .hasMessageContaining(PlanRegistry.HOTFIX_APPLY);
  }

  @Test
  void should_refuse_stored_arguments_that_are_not_json() {
    PlanRegistry registry =
        new PlanRegistry(
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            },
            () -> {
              throw new IllegalStateException("not needed");
            });

    assertThatThrownBy(() -> registry.rebuild(PlanRegistry.HOTFIX_APPLY, "not json at all"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not JSON");
  }

  private static JsonNode tree(String json) throws IOException {
    return Json.mapper().readTree(json);
  }
}
