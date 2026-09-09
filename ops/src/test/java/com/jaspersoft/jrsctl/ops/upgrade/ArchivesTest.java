package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.engine.CancellationToken;
import com.jaspersoft.jrsctl.core.platform.Platform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ArchivesTest {

  @TempDir Path tmp;

  @ParameterizedTest
  @EnumSource(Platform.OsFamily.class)
  void should_round_trip_a_directory_tree_when_creating_and_extracting(Platform.OsFamily os)
      throws Exception {
    Path source = tmp.resolve("src");
    Files.createDirectories(source.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(source.resolve("empty"));
    Files.writeString(source.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
    Files.writeString(
        source.resolve("WEB-INF").resolve("lib").resolve("x.jar"), "jar", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("out").resolve("webapp" + Archives.extension(os));

    long written = Archives.create(os, source, archive, new CancellationToken());
    Path target = tmp.resolve("restored");
    long read = Archives.extract(os, archive, target, new CancellationToken());

    assertThat(archive).exists();
    assertThat(written).isEqualTo(5);
    assertThat(read).isEqualTo(5);
    assertThat(target.resolve("a.txt")).hasContent("alpha");
    assertThat(target.resolve("WEB-INF").resolve("lib").resolve("x.jar")).hasContent("jar");
    assertThat(target.resolve("empty")).isDirectory();
    List<String> names = Archives.entries(os, archive);
    assertThat(names).contains("a.txt", "WEB-INF/lib/x.jar", "empty/");
    assertThat(names).allSatisfy(n -> assertThat(n).doesNotContain("\\"));
  }

  @Test
  void should_move_current_tree_aside_and_undo_when_restoring() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.WINDOWS;
    Path source = tmp.resolve("src");
    Files.createDirectories(source);
    Files.writeString(source.resolve("v.txt"), "old", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("webapp.zip");
    Archives.create(os, source, archive, new CancellationToken());
    Files.writeString(source.resolve("v.txt"), "new", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("extra.txt"), "new file", StandardCharsets.UTF_8);
    Path aside = tmp.resolve("run").resolve("aside").resolve("src");

    PointB.restoreDir(os, archive, source, aside, new CancellationToken());

    assertThat(source.resolve("v.txt")).hasContent("old");
    assertThat(source.resolve("extra.txt")).doesNotExist();
    assertThat(aside.resolve("v.txt")).hasContent("new");
    assertThat(aside.resolve("extra.txt")).exists();

    assertThat(PointB.undoRestore(source, aside)).isTrue();
    assertThat(source.resolve("v.txt")).hasContent("new");
    assertThat(source.resolve("extra.txt")).exists();
    assertThat(aside).doesNotExist();
    assertThat(PointB.undoRestore(source, aside)).isFalse();
  }
}
