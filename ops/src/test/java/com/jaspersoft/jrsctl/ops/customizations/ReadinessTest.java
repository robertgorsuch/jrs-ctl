package com.jaspersoft.jrsctl.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsctl.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsctl.ops.FakeServices;
import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.Readiness;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessFinding;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessKind;
import com.jaspersoft.jrsctl.ops.customizations.CustomizationOperations.ReadinessOptions;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #306, ADR-0061: {@code customizations readiness} over classes compiled at test time against
 * stub {@code javax}, {@code jakarta} and Spring classes, so the class files are the real output of
 * {@code javac}, not hand-made bytes.
 */
class ReadinessTest {

  @TempDir static Path compiled;
  @TempDir Path tmp;

  private FakeServices fake;
  private DefaultCustomizationOperations ops;

  /** Stubs: compiled with the site classes, never packaged with them. */
  private static final Map<String, String> STUBS = new LinkedHashMap<>();

  /** Site classes: package/Name to source. */
  private static final Map<String, String> SITE = new LinkedHashMap<>();

  static {
    STUBS.put(
        "javax/servlet/http/HttpServlet",
        "package javax.servlet.http; public abstract class HttpServlet {}");
    STUBS.put(
        "javax/persistence/EntityManager",
        "package javax.persistence; public interface EntityManager {}");
    STUBS.put(
        "javax/annotation/PostConstruct",
        "package javax.annotation; import java.lang.annotation.*;"
            + " @Retention(RetentionPolicy.RUNTIME) public @interface PostConstruct {}");
    STUBS.put("javax/xml/bind/JAXBContext", "package javax.xml.bind; public class JAXBContext {}");
    STUBS.put(
        "jakarta/servlet/http/HttpServlet",
        "package jakarta.servlet.http; public abstract class HttpServlet {}");
    STUBS.put(
        "org/springframework/kept/Kept", "package org.springframework.kept; public class Kept {}");
    STUBS.put(
        "org/springframework/gone/Gone", "package org.springframework.gone; public class Gone {}");

    SITE.put(
        "site/OldServlet",
        "package site;"
            + " public class OldServlet extends javax.servlet.http.HttpServlet {"
            + "  javax.sql.DataSource ds;"
            + "  javax.transaction.xa.XAResource xa;"
            + "  public void use(javax.persistence.EntityManager em) {}"
            + "  @javax.annotation.PostConstruct public void init() {}"
            + " }");
    SITE.put(
        "site/Ported",
        "package site; public class Ported extends jakarta.servlet.http.HttpServlet {}");
    SITE.put(
        "site/Libraries",
        "package site; public class Libraries {"
            + "  org.springframework.kept.Kept kept;"
            + "  org.springframework.gone.Gone gone;"
            + "  javax.xml.bind.JAXBContext jaxb;"
            + "  com.jaspersoft.custom.Helper helper;"
            + " }");
    SITE.put(
        "com/jaspersoft/custom/Helper", "package com.jaspersoft.custom; public class Helper {}");
  }

  @BeforeAll
  static void compile() throws Exception {
    Path src = compiled.resolve("src");
    List<String> args =
        new ArrayList<>(List.of("--release", "17", "-d", compiled.resolve("17").toString()));
    for (Map<String, String> m : List.of(STUBS, SITE)) {
      for (Map.Entry<String, String> e : m.entrySet()) {
        Path f = src.resolve(e.getKey() + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
        args.add(f.toString());
      }
    }
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    assertThat(javac.run(null, null, null, args.toArray(String[]::new))).isZero();
    Path java21 = src.resolve("site21/New.java");
    Files.createDirectories(java21.getParent());
    Files.writeString(java21, "package site21; public class New {}", StandardCharsets.UTF_8);
    assertThat(
            javac.run(
                null,
                null,
                null,
                "--release",
                "21",
                "-d",
                compiled.resolve("21").toString(),
                java21.toString()))
        .isZero();
  }

  private static Path classFile(String release, String name) {
    return compiled.resolve(release).resolve(name + ".class");
  }

  private static void jar(Path jar, Map<String, Path> classes, Map<String, String> texts)
      throws Exception {
    Files.createDirectories(jar.getParent());
    try (OutputStream out = Files.newOutputStream(jar);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, Path> e : classes.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey() + ".class"));
        try (InputStream in = Files.newInputStream(e.getValue())) {
          in.transferTo(zip);
        }
        zip.closeEntry();
      }
      for (Map.Entry<String, String> e : texts.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    Services services = fake.build();
    ops =
        new DefaultCustomizationOperations(
            services, new SnapshotStore(fake.home, services.platform().files(), fake.clock));
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  private static List<String> refs(Readiness r, String file, ReadinessKind kind) {
    return r.findings().stream()
        .filter(f -> f.file().endsWith(file) && f.kind() == kind)
        .map(ReadinessFinding::references)
        .findFirst()
        .orElse(List.of());
  }

  @Test
  void should_read_every_class_a_class_file_names_from_its_constant_pool() throws Exception {
    ClassFileReferences refs;
    try (InputStream in = Files.newInputStream(classFile("17", "site/OldServlet"))) {
      refs = ClassFileReferences.read(in);
    }

    assertThat(refs.thisClass()).isEqualTo("site/OldServlet");
    assertThat(refs.majorVersion()).isEqualTo(61);
    assertThat(refs.referenced())
        .contains(
            "javax/servlet/http/HttpServlet",
            "javax/persistence/EntityManager",
            "javax/annotation/PostConstruct",
            "javax/sql/DataSource",
            "javax/transaction/xa/XAResource")
        .doesNotContain("site/OldServlet");
  }

  @Test
  void should_report_renamed_javax_names_but_not_the_jdks_own_nor_a_ported_class()
      throws Exception {
    Path siteJar = tmp.resolve("site.jar");
    jar(
        siteJar,
        Map.of(
            "site/OldServlet", classFile("17", "site/OldServlet"),
            "site/Ported", classFile("17", "site/Ported")),
        Map.of(
            "META-INF/maven/acme/site/pom.xml",
            "<properties><javax.activation.version>1.1.1</javax.activation.version></properties>"
                + "<!-- only classes with javax.persistence.Entity -->\n",
            "META-INF/spring/context.xml",
            "<beans xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\">\n"
                + "<bean class=\"javax.servlet.Filter\"/><bean class=\"javax.sql.DataSource\"/>\n"
                + "</beans>\n"));

    Readiness r =
        ops.readiness(
            new ReadinessOptions(
                "10.0", Optional.empty(), Optional.empty(), Optional.empty(), List.of(siteJar)));

    assertThat(r.classesChecked()).isEqualTo(2);
    assertThat(refs(r, "site.jar", ReadinessKind.JAKARTA))
        .containsExactly(
            "javax.annotation.PostConstruct",
            "javax.persistence.EntityManager",
            "javax.servlet.Filter",
            "javax.servlet.http.HttpServlet");
    assertThat(refs(r, "site.jar", ReadinessKind.NAMESPACE))
        .containsExactly("http://xmlns.jcp.org/xml/ns/javaee");
    assertThat(r.findings()).noneMatch(f -> f.kind() == ReadinessKind.NOT_IN_TARGET);
  }

  @Test
  void should_look_library_classes_up_in_the_targets_webapp_from_a_directory_or_a_war()
      throws Exception {
    Path targetLib = tmp.resolve("target/jasperserver-pro/WEB-INF/lib/vendor.jar");
    jar(
        targetLib,
        Map.of(
            "org/springframework/kept/Kept", classFile("17", "org/springframework/kept/Kept"),
            "javax/xml/bind/JAXBContext", classFile("17", "javax/xml/bind/JAXBContext")),
        Map.of());
    Path war = tmp.resolve("target-war/jasperserver-pro.war");
    Files.createDirectories(war.getParent());
    try (OutputStream out = Files.newOutputStream(war);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("WEB-INF/lib/vendor.jar"));
      Files.copy(targetLib, zip);
      zip.closeEntry();
    }
    Path siteJar = tmp.resolve("libs.jar");
    jar(
        siteJar,
        Map.of(
            "site/Libraries", classFile("17", "site/Libraries"),
            "com/jaspersoft/custom/Helper", classFile("17", "com/jaspersoft/custom/Helper")),
        Map.of());

    for (Path target : List.of(tmp.resolve("target/jasperserver-pro"), war)) {
      Readiness r =
          ops.readiness(
              new ReadinessOptions(
                  "10.0",
                  Optional.empty(),
                  Optional.empty(),
                  Optional.of(target),
                  List.of(siteJar)));

      assertThat(refs(r, "libs.jar", ReadinessKind.NOT_IN_TARGET))
          .as(target.toString())
          .containsExactly("org.springframework.gone.Gone");
      assertThat(refs(r, "libs.jar", ReadinessKind.JAKARTA_SHIPPED))
          .containsExactly("javax.xml.bind.JAXBContext");
      assertThat(refs(r, "libs.jar", ReadinessKind.JAKARTA)).isEmpty();
    }
  }

  @Test
  void should_report_a_class_compiled_for_a_java_newer_than_17() throws Exception {
    Readiness r =
        ops.readiness(
            new ReadinessOptions(
                "10.0",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(classFile("21", "site21/New"), classFile("17", "site/Ported"))));

    assertThat(refs(r, "New.class", ReadinessKind.BYTECODE))
        .containsExactly("Java 21 (class file version 65)");
    assertThat(r.findings()).noneMatch(f -> f.file().endsWith("Ported.class"));
  }

  @Test
  void should_check_the_sites_changed_and_added_files_from_a_scan() throws Exception {
    Path vendor = tmp.resolve("vendor/jasperserver-pro");
    Files.createDirectories(vendor.resolve("WEB-INF/classes"));
    Files.writeString(vendor.resolve("WEB-INF/web.xml"), "<web-app/>\n", StandardCharsets.UTF_8);
    Path site = tmp.resolve("site/jasperserver-pro");
    Files.createDirectories(site.resolve("WEB-INF/classes/site"));
    Files.copy(
        classFile("17", "site/OldServlet"), site.resolve("WEB-INF/classes/site/OldServlet.class"));
    Files.writeString(
        site.resolve("WEB-INF/web.xml"),
        "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\"/>\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        site.resolve("WEB-INF/classes/notes.txt"),
        "javax.servlet.Filter\n",
        StandardCharsets.UTF_8);

    Readiness r =
        ops.readiness(
            new ReadinessOptions(
                "10.0", Optional.of(vendor), Optional.of(site), Optional.empty(), List.of()));

    assertThat(r.filesChecked()).isEqualTo(2);
    assertThat(refs(r, "WEB-INF/classes/site/OldServlet.class", ReadinessKind.JAKARTA))
        .contains("javax.servlet.http.HttpServlet");
    assertThat(refs(r, "WEB-INF/web.xml", ReadinessKind.NAMESPACE))
        .containsExactly("http://java.sun.com/xml/ns/javaee");
    assertThat(r.findings()).noneMatch(f -> f.file().endsWith("notes.txt"));
  }

  @Test
  void should_report_an_unreadable_class_and_refuse_when_there_is_nothing_to_check()
      throws Exception {
    Path bad = tmp.resolve("Bad.class");
    Files.writeString(bad, "not a class", StandardCharsets.UTF_8);

    Readiness r =
        ops.readiness(
            new ReadinessOptions(
                "10.0", Optional.empty(), Optional.empty(), Optional.empty(), List.of(bad)));

    assertThat(refs(r, "Bad.class", ReadinessKind.UNREADABLE)).hasSize(1);
    assertThatThrownBy(
            () ->
                ops.readiness(
                    new ReadinessOptions(
                        "10.0", Optional.empty(), Optional.empty(), Optional.empty(), List.of())))
        .isInstanceOf(CustomizationException.class)
        .hasMessageContaining("nothing to check");
  }
}
