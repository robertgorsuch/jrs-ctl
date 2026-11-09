package com.jaspersoft.jrsctl.jrs.strategy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * What an export archive's {@code index.xml} says about itself (issue #263): the {@code jsVersion}
 * property of the exporting server, the key alias its passwords were encrypted with, the module ids
 * it holds ({@code tenants}, {@code authority}, {@code repositoryResources}, {@code reportJobs},
 * {@code audit}, {@code reportMonitoring}, ...) and the folders and resources under {@code
 * repositoryResources}. Invariants: the archive is read as a stream up to the index entry and never
 * held in memory; the parser has external entities and DTDs off; an archive without {@code
 * index.xml}, or that is not a ZIP, is an {@link IllegalArgumentException} naming the entry, never
 * an empty index; nothing here decides what the import does with the answers.
 */
public record ExportIndex(
    Optional<String> jsVersion, Optional<String> keyAlias, Set<String> modules, List<String> uris) {

  /** The module the vendor lists users and roles under. */
  public static final String AUTHORITY = "authority";

  public static final String REPOSITORY_RESOURCES = "repositoryResources";
  public static final String REPORT_JOBS = "reportJobs";
  public static final String AUDIT = "audit";
  public static final String MONITORING = "reportMonitoring";
  public static final String ACCESS = "access";

  public ExportIndex {
    Objects.requireNonNull(jsVersion, "jsVersion");
    Objects.requireNonNull(keyAlias, "keyAlias");
    modules = Set.copyOf(modules);
    uris = List.copyOf(uris);
  }

  /** The version number alone: {@code 8.2.0} from {@code 8.2.0 PRO}. */
  public Optional<String> version() {
    return jsVersion
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(
            s -> {
              int space = s.indexOf(' ');
              return space < 0 ? s : s.substring(0, space);
            });
  }

  /** The root folder with users, roles and report jobs: what {@code --everything} produces. */
  public boolean fullServer() {
    return uris.contains("/") && modules.contains(AUTHORITY) && modules.contains(REPORT_JOBS);
  }

  public boolean hasUsersRoles() {
    return modules.contains(AUTHORITY);
  }

  public boolean hasAuditEvents() {
    return modules.contains(AUDIT);
  }

  public boolean hasMonitoringEvents() {
    return modules.contains(MONITORING);
  }

  public boolean hasAccessEvents() {
    return modules.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).startsWith(ACCESS));
  }

  /** Reads {@code index.xml} out of {@code archive}. */
  public static ExportIndex read(Path archive) throws IOException {
    Objects.requireNonNull(archive, "archive");
    try (InputStream in = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals(ExportArchives.INDEX)) {
          return parse(zip, archive);
        }
      }
    }
    throw new IllegalArgumentException(
        archive + " holds no " + ExportArchives.INDEX + ": not a JasperReports Server export");
  }

  private static ExportIndex parse(InputStream in, Path archive) throws IOException {
    Document doc;
    try {
      doc = secureBuilder().parse(in);
    } catch (SAXException | ParserConfigurationException e) {
      throw new IllegalArgumentException(
          ExportArchives.INDEX + " in " + archive + " cannot be parsed: " + e.getMessage(), e);
    }
    Element root = doc.getDocumentElement();
    Optional<String> version = Optional.empty();
    Optional<String> alias = Optional.empty();
    Set<String> modules = new LinkedHashSet<>();
    Set<String> uris = new TreeSet<>();
    NodeList children = root.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (!(node instanceof Element element)) {
        continue;
      }
      switch (element.getTagName()) {
        case "property" -> {
          String name = element.getAttribute("name");
          String value = element.getAttribute("value");
          if (name.equals("jsVersion")) {
            version = Optional.of(value);
          } else if (name.equals("keyalias")) {
            alias = Optional.of(value);
          }
        }
        case "module" -> {
          String id = element.getAttribute("id");
          if (!id.isEmpty()) {
            modules.add(id);
          }
          if (id.equals(REPOSITORY_RESOURCES)) {
            NodeList items = element.getChildNodes();
            for (int j = 0; j < items.getLength(); j++) {
              if (items.item(j) instanceof Element item
                  && (item.getTagName().equals("folder") || item.getTagName().equals("resource"))) {
                String text = item.getTextContent().trim();
                if (!text.isEmpty()) {
                  uris.add(text);
                }
              }
            }
          }
        }
        default -> {}
      }
    }
    return new ExportIndex(version, alias, modules, new ArrayList<>(uris));
  }

  private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder();
  }
}
