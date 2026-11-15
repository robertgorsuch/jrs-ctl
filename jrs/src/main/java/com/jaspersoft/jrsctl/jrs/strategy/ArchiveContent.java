package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.jrs.api.ResourceFields;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * What {@code import verify --content} compares on the archive side (issue #307, ADR-0060), read in
 * one streamed pass. Files: every {@code resources/<path>.data} entry is the content of the file
 * resource at {@code /<path>}, a top-level file next to its descriptor ({@code <id>.data}) or a
 * report unit's own file under {@code <unit>_files/} (layout and URIs verified against a 10.0.0
 * export of {@code /public/Samples}: all 494 files fetched back byte for byte). Descriptors: every
 * top-level {@code resources/<path>.xml} that is not a folder marker, with its root element name
 * and {@link ResourceFields}. Invariants: names are decoded from the vendor's escaping as {@link
 * ExportArchiveInventory} decodes them, so the URIs agree with the {@code resources} item; content
 * is hashed as it streams and never held in memory; descriptor XML is parsed with DTDs and external
 * entities off; only URIs {@code include} accepts are kept; maps are sorted by URI.
 */
public record ArchiveContent(Map<String, String> fileSha256, Map<String, Descriptor> descriptors) {

  /** The root element of a top-level descriptor ({@code fileResource}, {@code reportUnit}, ...). */
  public record Descriptor(String type, ResourceFields fields) {
    public Descriptor {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(fields, "fields");
    }

    public boolean file() {
      return FILE_RESOURCE.equals(type);
    }

    public boolean reportUnit() {
      return REPORT_UNIT.equals(type);
    }
  }

  public static final String FILE_RESOURCE = "fileResource";
  public static final String REPORT_UNIT = "reportUnit";

  private static final String RESOURCES = "resources/";
  private static final String DATA = ".data";
  private static final String XML = ".xml";
  private static final String FOLDER_MARKER = ".folder.xml";
  private static final String LOCAL_FILES = "_files/";

  public ArchiveContent {
    fileSha256 = Map.copyOf(new TreeMap<>(fileSha256));
    descriptors = Map.copyOf(new TreeMap<>(descriptors));
  }

  /** Reads {@code archive}, keeping the URIs {@code include} accepts. */
  public static ArchiveContent read(Path archive, Predicate<String> include) throws IOException {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(include, "include");
    Map<String, String> files = new TreeMap<>();
    Map<String, Descriptor> descriptors = new TreeMap<>();
    try (InputStream in = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory() || !name.startsWith(RESOURCES)) {
          continue;
        }
        String rel = name.substring(RESOURCES.length());
        if (rel.endsWith(DATA)) {
          String uri = "/" + ExportArchiveInventory.unescapePath(strip(rel, DATA));
          if (include.test(uri)) {
            files.put(uri, sha256(zip));
          }
        } else if (rel.endsWith(XML)
            && !rel.endsWith(FOLDER_MARKER)
            && !rel.contains(LOCAL_FILES)) {
          String uri = "/" + ExportArchiveInventory.unescapePath(strip(rel, XML));
          if (include.test(uri)) {
            descriptors.put(uri, descriptor(zip, name));
          }
        }
      }
    }
    return new ArchiveContent(files, descriptors);
  }

  private static String strip(String s, String suffix) {
    return s.substring(0, s.length() - suffix.length());
  }

  private static String sha256(InputStream in) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
    byte[] buffer = new byte[64 * 1024];
    int n;
    while ((n = in.read(buffer)) >= 0) {
      digest.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * The root element and the fields of one descriptor. Only direct children of the root count:
   * {@code label}, {@code description}, {@code dataSource} and each {@code inputControl}, a
   * reference being a {@code <uri>} or a {@code <localResource>} (its {@code folder} + {@code /} +
   * {@code name}).
   */
  static Descriptor descriptor(InputStream entry, String name) throws IOException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
    try {
      XMLStreamReader xml = factory.createXMLStreamReader(new NonClosing(entry));
      try {
        while (xml.hasNext() && xml.next() != XMLStreamConstants.START_ELEMENT) {
          // skip the prolog
        }
        String type = xml.getLocalName();
        String label = "";
        String description = "";
        Optional<String> dataSource = Optional.empty();
        List<String> controls = new ArrayList<>();
        while (xml.hasNext()) {
          int event = xml.next();
          if (event == XMLStreamConstants.END_ELEMENT) {
            break; // the root closed
          }
          if (event != XMLStreamConstants.START_ELEMENT) {
            continue;
          }
          switch (xml.getLocalName()) {
            case "label" -> label = xml.getElementText();
            case "description" -> description = xml.getElementText();
            case "dataSource" -> dataSource = reference(xml);
            case "inputControl" -> reference(xml).ifPresent(controls::add);
            default -> skip(xml);
          }
        }
        return new Descriptor(type, new ResourceFields(label, description, dataSource, controls));
      } finally {
        xml.close();
      }
    } catch (XMLStreamException e) {
      throw new IOException("cannot read " + name + ": " + e.getMessage(), e);
    }
  }

  /** At a reference's start tag; returns its URI and leaves the reader at its end tag. */
  private static Optional<String> reference(XMLStreamReader xml) throws XMLStreamException {
    Optional<String> uri = Optional.empty();
    while (xml.hasNext()) {
      int event = xml.next();
      if (event == XMLStreamConstants.END_ELEMENT) {
        return uri;
      }
      if (event != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      if (xml.getLocalName().equals("uri")) {
        uri = Optional.of(xml.getElementText().strip());
      } else if (xml.getLocalName().equals("localResource")) {
        uri = localResource(xml);
      } else {
        skip(xml);
      }
    }
    return uri;
  }

  /** At {@code <localResource>}; {@code folder/name}, leaving the reader at its end tag. */
  private static Optional<String> localResource(XMLStreamReader xml) throws XMLStreamException {
    String folder = null;
    String name = null;
    while (xml.hasNext()) {
      int event = xml.next();
      if (event == XMLStreamConstants.END_ELEMENT) {
        break;
      }
      if (event != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      switch (xml.getLocalName()) {
        case "folder" -> folder = xml.getElementText().strip();
        case "name" -> name = xml.getElementText().strip();
        default -> skip(xml);
      }
    }
    if (folder == null || name == null) {
      return Optional.empty();
    }
    return Optional.of((folder.endsWith("/") ? folder : folder + "/") + name);
  }

  /** At a start tag; moves the reader to its matching end tag. */
  private static void skip(XMLStreamReader xml) throws XMLStreamException {
    int depth = 1;
    while (depth > 0 && xml.hasNext()) {
      int event = xml.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }
  }

  /** Keeps the XML reader from closing the zip stream it reads one entry of. */
  private static final class NonClosing extends FilterInputStream {
    NonClosing(InputStream in) {
      super(in);
    }

    @Override
    public void close() {}
  }
}
