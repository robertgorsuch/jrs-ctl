package com.jaspersoft.jrsctl.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #315: buildomatic builds {@code iecp.jar} once; after a hotfix swaps jars in its {@code lib}, the
 * old manifest names files that are gone and every Ant import fails. Seen on a real 10.0 install:
 * 123 of 511 entries missing ({@code spring-context-6.2.11.jar} where {@code 6.2.17} now is).
 */
class IeClasspathJarTest {

  @TempDir Path tmp;

  private Path lib(String edition) throws IOException {
    return Files.createDirectories(tmp.resolve("conf_source").resolve(edition).resolve("lib"));
  }

  private static void touch(Path lib, String... names) throws IOException {
    for (String n : names) {
      Files.writeString(lib.resolve(n), "jar", StandardCharsets.UTF_8);
    }
  }

  private static void classpathJar(Path lib, String classPath) throws IOException {
    Manifest m = new Manifest();
    m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (classPath != null) {
      m.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
    }
    try (OutputStream out = Files.newOutputStream(lib.resolve(IeClasspathJar.NAME));
        JarOutputStream jar = new JarOutputStream(out, m)) {
      jar.flush();
    }
  }

  @Test
  void should_report_a_jar_whose_class_path_names_files_that_are_gone() throws Exception {
    Path lib = lib("iePro");
    touch(lib, "a-1.0.jar", "spring-context-6.2.17.jar");
    classpathJar(lib, "a-1.0.jar spring-context-6.2.11.jar spring-core-6.2.11.jar");

    IeClasspathJar.Stale stale = IeClasspathJar.stale(tmp).orElseThrow();

    assertThat(stale.jar()).isEqualTo(lib.resolve("iecp.jar"));
    assertThat(stale.entries()).isEqualTo(3);
    assertThat(stale.missing())
        .containsExactly("spring-context-6.2.11.jar", "spring-core-6.2.11.jar");
    assertThat(stale.detail()).contains("2 of its 3").contains("spring-context-6.2.11.jar");
    assertThat(stale.remediation()).startsWith("delete " + lib.resolve("iecp.jar"));
  }

  @Test
  void should_pass_a_jar_whose_entries_all_exist_including_percent_encoded_names()
      throws Exception {
    Path lib = lib("ieCe");
    touch(lib, "a-1.0.jar", "with space.jar");
    classpathJar(lib, "a-1.0.jar with%20space.jar");

    assertThat(IeClasspathJar.stale(tmp)).isEmpty();
  }

  @Test
  void should_pass_when_there_is_no_class_path_jar_or_it_cannot_be_judged() throws Exception {
    Path lib = lib("iePro");
    assertThat(IeClasspathJar.stale(tmp)).isEmpty();

    classpathJar(lib, null);
    assertThat(IeClasspathJar.stale(tmp)).isEmpty();

    Files.writeString(lib.resolve("iecp.jar"), "not a jar", StandardCharsets.UTF_8);
    assertThat(IeClasspathJar.stale(tmp)).isEmpty();
  }

  @Test
  void should_list_at_most_three_examples_in_the_detail() throws Exception {
    Path lib = lib("iePro");
    classpathJar(lib, String.join(" ", List.of("a.jar", "b.jar", "c.jar", "d.jar", "e.jar")));

    IeClasspathJar.Stale stale = IeClasspathJar.stale(tmp).orElseThrow();

    assertThat(stale.missing()).hasSize(5);
    assertThat(stale.detail()).contains("a.jar, b.jar, c.jar)").doesNotContain("d.jar");
  }
}
