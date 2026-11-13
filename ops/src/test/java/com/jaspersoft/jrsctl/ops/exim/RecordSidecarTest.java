package com.jaspersoft.jrsctl.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.strategy.Sidecar;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.RecordSidecarOptions;
import com.jaspersoft.jrsctl.ops.exim.ExportImportOperations.RecordedSidecar;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #263: {@code export record} writes the sidecar for an archive {@code js-export} made by
 * hand, so {@code import} can verify the hash, adopt the key alias, scope its snapshot and judge
 * the source version as it does for an archive jrsctl exported.
 */
class RecordSidecarTest {

  @TempDir Path tmp;

  private EximFixture fx;

  private static final String EVERYTHING =
      "<export><property name=\"keyalias\" value=\"e0ddc2e1-7ba1-4e76-a8fa-901aa34a458e\"/>"
          + "<module id=\"tenants\"><organization>organizations</organization></module>"
          + "<module id=\"authority\"><user>superuser</user><role>ROLE_USER</role></module>"
          + "<module id=\"repositoryResources\"><folder>/</folder></module>"
          + "<module id=\"calendars\"/><module id=\"reportJobs\"/><module id=\"audit\"/>"
          + "<module id=\"reportMonitoring\"/>"
          + "<property name=\"jsVersion\" value=\"8.2.0 PRO\"/></export>";

  private static final String FOLDER_LEGACY =
      "<export><module id=\"repositoryResources\"><folder>/public/Samples</folder></module>"
          + "<property name=\"keyalias\" value=\"deprecatedImportExportEncSecret\"/></export>";

  @BeforeEach
  void setUp() throws IOException {
    fx = new EximFixture(tmp, EximFakeAdapter::new);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Path archive(String name, Optional<String> index) throws IOException {
    Path file = Files.createDirectories(tmp.resolve("in")).resolve(name);
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("resources/"));
      zip.closeEntry();
      if (index.isPresent()) {
        zip.putNextEntry(new ZipEntry("index.xml"));
        zip.write(index.get().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return file;
  }

  private static RecordSidecarOptions options(Path archive) {
    return new RecordSidecarOptions(
        archive, Optional.empty(), Optional.empty(), Optional.empty(), false);
  }

  @Test
  void should_write_a_sidecar_with_the_hash_version_uris_and_flags_from_index_xml()
      throws Exception {
    Path archive = archive("js-8.2-export.zip", Optional.of(EVERYTHING));

    RecordedSidecar recorded = fx.ops().recordSidecar(options(archive));

    assertThat(recorded.file()).isEqualTo(Sidecar.pathFor(archive)).exists();
    Sidecar sidecar = Sidecar.read(recorded.file()).orElseThrow();
    assertThat(sidecar.sha256()).isEqualTo(EximFixture.sha256(archive));
    assertThat(sidecar.serverVersion()).isEqualTo("8.2.0");
    assertThat(sidecar.origin()).isEqualTo(Sidecar.ORIGIN_RECORD);
    assertThat(sidecar.serverIdentity()).contains("record").contains("8.2.0 PRO");
    assertThat(sidecar.strategy()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(sidecar.keystoreFingerprint()).isEmpty();
    Sidecar.Flags flags = sidecar.flags();
    assertThat(flags.fullServer()).isTrue();
    assertThat(flags.scope()).isEqualTo(ExportRequest.Scope.EVERYTHING);
    assertThat(flags.uris()).containsExactly("/");
    assertThat(flags.includeUsersRoles()).isTrue();
    assertThat(flags.includeSettings()).isTrue();
    assertThat(flags.includeAuditEvents()).isTrue();
    assertThat(flags.includeMonitoring()).isTrue();
    assertThat(flags.includeAccessEvents()).isFalse();
    // the server's own import/export key, not an alias an import could name
    assertThat(flags.keyAlias()).isEmpty();
    assertThat(recorded.index().modules()).contains("reportJobs");
  }

  @Test
  void should_record_the_keystore_fingerprint_and_key_alias_when_given() throws Exception {
    Path archive = archive("part.zip", Optional.of(FOLDER_LEGACY));
    Path jrsks = Files.writeString(tmp.resolve(".jrsks"), "keystore bytes");

    RecordedSidecar recorded =
        fx.ops()
            .recordSidecar(
                new RecordSidecarOptions(
                    archive,
                    Optional.of("8.2.0"),
                    Optional.of(jrsks),
                    Optional.of("custom-alias"),
                    false));

    Sidecar sidecar = recorded.sidecar();
    assertThat(sidecar.keystoreFingerprint()).contains(EximFixture.sha256(jrsks));
    assertThat(sidecar.flags().keyAlias()).contains("custom-alias");
    assertThat(sidecar.serverVersion()).isEqualTo("8.2.0");
    assertThat(sidecar.flags().fullServer()).isFalse();
    assertThat(sidecar.flags().scope()).isEqualTo(ExportRequest.Scope.REPOSITORY);
    assertThat(sidecar.flags().uris()).containsExactly("/public/Samples");
  }

  /** Issue #317: the source database survives a write and a read of the sidecar. */
  @Test
  void should_record_the_source_database_when_given() throws Exception {
    Path archive = archive("everything.zip", Optional.of(EVERYTHING));

    RecordedSidecar recorded =
        fx.ops()
            .recordSidecar(
                new RecordSidecarOptions(
                    archive,
                    Optional.of("8.2.0"),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    Optional.of(" jasperserver ")));

    assertThat(recorded.sidecar().sourceDatabase()).contains("jasperserver");
    assertThat(Sidecar.read(recorded.file()).orElseThrow().sourceDatabase())
        .contains("jasperserver");
    assertThat(
            fx.ops()
                .recordSidecar(options(archive("other.zip", Optional.of(EVERYTHING))))
                .sidecar()
                .sourceDatabase())
        .isEmpty();
  }

  /** An archive made with {@code --keyalias deprecatedImportExportEncSecret} says so itself. */
  @Test
  void should_take_the_legacy_key_alias_from_index_xml_without_a_flag() throws Exception {
    Path archive = archive("part.zip", Optional.of(FOLDER_LEGACY));

    Sidecar sidecar = fx.ops().recordSidecar(options(archive)).sidecar();

    assertThat(sidecar.flags().keyAlias()).contains(ExportRequest.PORTABLE_KEY_ALIAS);
    assertThat(sidecar.serverVersion()).isEqualTo("unknown");
  }

  @Test
  void should_refuse_to_overwrite_an_existing_sidecar_without_force() throws Exception {
    Path archive = archive("twice.zip", Optional.of(EVERYTHING));
    fx.ops().recordSidecar(options(archive));

    assertThatThrownBy(() -> fx.ops().recordSidecar(options(archive)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("--force");
    assertThat(
            fx.ops()
                .recordSidecar(
                    new RecordSidecarOptions(
                        archive, Optional.empty(), Optional.empty(), Optional.empty(), true))
                .file())
        .exists();
  }

  @Test
  void should_refuse_an_archive_without_index_xml() throws Exception {
    Path archive = archive("none.zip", Optional.empty());

    assertThatThrownBy(() -> fx.ops().recordSidecar(options(archive)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("index.xml");
    assertThat(Sidecar.pathFor(archive)).doesNotExist();
  }

  @Test
  void should_refuse_a_source_keystore_that_does_not_exist() throws Exception {
    Path archive = archive("ks.zip", Optional.of(EVERYTHING));
    Path missing = tmp.resolve("missing.jrsks");

    assertThatThrownBy(
            () ->
                fx.ops()
                    .recordSidecar(
                        new RecordSidecarOptions(
                            archive,
                            Optional.empty(),
                            Optional.of(missing),
                            Optional.empty(),
                            false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing.toString());
    assertThat(Sidecar.pathFor(archive)).doesNotExist();
    assertThat(Map.of()).isEmpty();
  }
}
