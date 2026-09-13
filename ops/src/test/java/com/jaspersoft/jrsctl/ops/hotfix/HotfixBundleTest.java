package com.jaspersoft.jrsctl.ops.hotfix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotfixBundleTest {

  @TempDir Path tmp;

  /**
   * The payload is unpacked under the jrsctl home before the disk-space preflight sizes anything,
   * so the unpacking itself has a ceiling (assessment item H7).
   */
  @Test
  void should_refuse_a_bundle_whose_payload_exceeds_the_ceiling() throws IOException {
    Path zip = tmp.resolve("big.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry("manifest.json"));
      out.write("{}".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
      out.putNextEntry(new ZipEntry("payload/big.jar"));
      out.write(new byte[64]);
      out.closeEntry();
    }

    assertThatThrownBy(() -> HotfixBundle.extract(zip, tmp.resolve("unpacked"), 32))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("payload/big.jar")
        .hasMessageContaining("bundle ceiling 32");
  }
}
