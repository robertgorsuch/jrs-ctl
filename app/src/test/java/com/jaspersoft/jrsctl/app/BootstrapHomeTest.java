package com.jaspersoft.jrsctl.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsctl.core.JrsctlHome;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.platform.OperatorPrompt;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.platform.Platforms;
import com.jaspersoft.jrsctl.core.platform.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Issue #50: the Windows home jrsctl creates is private to its owner, and so is what it holds. */
@EnabledOnOs(OS.WINDOWS)
class BootstrapHomeTest {

  private final Platform platform = Platforms.detect(OperatorPrompt.nonInteractive());

  @Test
  void should_create_a_protected_owner_only_home_when_none_exists(@TempDir Path dir)
      throws IOException {
    JrsctlHome home = new JrsctlHome(dir.resolve("jrsctl"));

    Bootstrap.createHome(platform, home);
    Path file = Files.writeString(home.root().resolve("state.db"), "x", StandardCharsets.UTF_8);
    Path nested = Files.createDirectories(home.snapshots().resolve("r-1"));

    assertThat(icacls(home.root())).noneMatch(line -> line.contains("(I)"));
    assertThat(platform.files().isOwnerOnly(home.root())).isTrue();
    assertThat(platform.files().isOwnerOnly(file)).isTrue();
    assertThat(platform.files().isOwnerOnly(nested)).isTrue();
  }

  @Test
  void should_leave_an_existing_home_alone_when_it_already_exists(@TempDir Path dir)
      throws IOException {
    Path root = Files.createDirectories(dir.resolve("existing"));
    FileOps.Permissions before = platform.files().capturePermissions(root);

    Bootstrap.createHome(platform, new JrsctlHome(root));

    assertThat(platform.files().capturePermissions(root)).isEqualTo(before);
  }

  /** {@code icacls} marks inherited entries with {@code (I)}. */
  private List<String> icacls(Path path) {
    List<String> lines = new ArrayList<>();
    platform
        .processes()
        .run(
            new ProcessRunner.Request(
                List.of("icacls", path.toString()),
                Optional.empty(),
                Map.of(),
                Duration.ofSeconds(20)),
            l -> lines.add(l.text()));
    return lines;
  }
}
