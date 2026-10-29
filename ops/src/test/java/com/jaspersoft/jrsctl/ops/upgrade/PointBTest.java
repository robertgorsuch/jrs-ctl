package com.jaspersoft.jrsctl.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PointB}'s tree moves: a move that fails leaves the source where it was and nothing at the
 * destination that a later undo or resume could take for a complete copy.
 */
class PointBTest {

  private static final String GOOD = "WEB-INF/classes/good.properties";
  private static final String BROKEN = "WEB-INF/lib/broken.jar";

  @TempDir Path tmp;

  /**
   * Review finding 1 (2026-10-29): across file stores {@code moveTree} copies, and a copy that
   * fails half-way left a partial tree at the destination. {@code restoreDir} on resume and {@code
   * undoRestore} both read an existing {@code aside} as the whole earlier tree and delete the
   * intact original in its favour. A zip file system stands in for the other volume: a move out of
   * it cannot be a rename, so it takes the copy path, and a corrupt entry fails the copy.
   */
  @Test
  void should_leave_nothing_at_the_destination_when_a_cross_volume_copy_fails() throws Exception {
    Path zip = zipWithACorruptEntry(tmp.resolve("other-volume.zip"));
    Path aside = tmp.resolve("aside");
    try (FileSystem other = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of())) {
      Path webapp = other.getPath("/webapp");

      assertThatThrownBy(() -> PointB.moveTree(webapp, aside)).isInstanceOf(IOException.class);

      assertThat(webapp.resolve(GOOD)).exists();
      assertThat(webapp.resolve(BROKEN)).exists();
      assertThat(aside)
          .as("a failed move must not leave an aside tree that undoRestore would trust")
          .doesNotExist();
    }
  }

  @Test
  void should_move_the_whole_tree_and_leave_no_part_copy_when_a_cross_volume_copy_succeeds()
      throws Exception {
    Path zip = zip(tmp.resolve("other-volume.zip"));
    Path aside = tmp.resolve("aside");
    try (FileSystem other = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of())) {
      Path webapp = other.getPath("/webapp");

      PointB.moveTree(webapp, aside);

      assertThat(webapp).doesNotExist();
      assertThat(Files.readString(aside.resolve(GOOD)))
          .isEqualTo(("content of " + GOOD + "\n").repeat(64));
      assertThat(aside.resolve(BROKEN)).exists();
      assertThat(tmp.resolve("aside.part")).doesNotExist();
    }
  }

  /**
   * A zip holding {@code webapp/} with one good entry and one whose deflate stream starts with an
   * invalid block type, so reading it fails while the archive itself still opens.
   */
  private static Path zipWithACorruptEntry(Path zip) throws IOException {
    zip(zip);
    byte[] bytes = Files.readAllBytes(zip);
    byte[] name = ("webapp/" + BROKEN).getBytes(StandardCharsets.UTF_8);
    int at = indexOf(bytes, name); // the local header's copy comes before the central directory's
    int extra = (bytes[at - 2] & 0xff) | (bytes[at - 1] & 0xff) << 8;
    bytes[at + name.length + extra] = (byte) 0xff; // BFINAL=1, BTYPE=11: reserved, so invalid
    Files.write(zip, bytes);
    return zip;
  }

  private static Path zip(Path zip) throws IOException {
    try (OutputStream file = Files.newOutputStream(zip);
        ZipOutputStream out = new ZipOutputStream(file)) {
      for (String name : new String[] {GOOD, BROKEN}) {
        out.putNextEntry(new ZipEntry("webapp/" + name));
        out.write(("content of " + name + "\n").repeat(64).getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return zip;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    throw new AssertionError("entry name not found in the zip");
  }
}
