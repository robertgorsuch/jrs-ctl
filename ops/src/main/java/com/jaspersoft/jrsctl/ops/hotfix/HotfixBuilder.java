package com.jaspersoft.jrsctl.ops.hotfix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.crypto.Ed25519;
import com.jaspersoft.jrsctl.core.json.Json;
import com.jaspersoft.jrsctl.core.platform.FileOps;
import com.jaspersoft.jrsctl.core.secrets.Secret;
import com.jaspersoft.jrsctl.core.secrets.SecretException;
import com.jaspersoft.jrsctl.core.secrets.SecretRef;
import com.jaspersoft.jrsctl.core.secrets.SecretResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code hotfix build} (spec §8.4): completes the SHA-256 fields of a manifest from the files in a
 * bundle directory, validates it, signs it with an Ed25519 private key and writes the ZIP with
 * {@code manifest.json} first and {@code SIGNATURE} second. Invariants: the source directory is
 * never modified; every regular file in it must be the manifest or listed by the manifest,
 * otherwise the build is refused; the private key lives in a {@code char[]} that is wiped after
 * signing and is never written anywhere; the ZIP is streamed entry by entry.
 */
public final class HotfixBuilder {

  private static final int BUFFER = 64 * 1024;

  private final FileOps files;
  private final SecretResolver secrets;
  private final ManifestValidator validator = new ManifestValidator();

  public HotfixBuilder(FileOps files, SecretResolver secrets) {
    this.files = Objects.requireNonNull(files, "files");
    this.secrets = Objects.requireNonNull(secrets, "secrets");
  }

  /** Builds {@code out} from {@code bundleDir}; returns {@code out}. */
  public Path build(Path bundleDir, SecretRef privateKeyRef, Path out) {
    Path root = bundleDir.toAbsolutePath().normalize();
    Path manifestFile = root.resolve(HotfixBundle.MANIFEST);
    if (!Files.isRegularFile(manifestFile)) {
      throw refused("no " + HotfixBundle.MANIFEST + " in " + root, "create the manifest first");
    }
    ObjectNode tree = readTree(manifestFile);
    List<String> order = new ArrayList<>();
    try {
      complete(tree, root, order);
    } catch (IOException e) {
      throw refused("cannot hash bundle files: " + e.getMessage(), "check the bundle directory");
    }
    byte[] manifestBytes = Json.writePretty(tree).getBytes(StandardCharsets.UTF_8);
    ManifestValidator.Result result =
        validator.validate(new String(manifestBytes, StandardCharsets.UTF_8));
    Manifest manifest =
        switch (result) {
          case ManifestValidator.Result.Valid v -> v.manifest();
          case ManifestValidator.Result.Invalid i ->
              throw refused(
                  "manifest is not valid:\n  " + String.join("\n  ", i.problems()),
                  "fix the listed problems in " + manifestFile);
        };
    refuseUnknownFiles(root, new LinkedHashSet<>(order));
    String signature = sign(manifestBytes, privateKeyRef);
    try {
      write(out, manifestBytes, signature, root, order, manifest.id());
    } catch (IOException e) {
      throw refused("cannot write " + out + ": " + e.getMessage(), "check the output location");
    }
    return out;
  }

  private void complete(ObjectNode tree, Path root, List<String> order) throws IOException {
    for (JsonNode node : tree.path("files")) {
      if (!(node instanceof ObjectNode f)) {
        continue;
      }
      String path = f.path("path").asText("");
      String action = f.path("action").asText("");
      String entry = HotfixBundle.PAYLOAD_DIR + "/" + path;
      Path payload = root.resolve(entry);
      if (action.equals("delete")) {
        if (Files.exists(payload)) {
          throw refused(
              entry + " is present but its manifest entry is a delete",
              "remove the payload file or change the action");
        }
        continue;
      }
      if (!Files.isRegularFile(payload)) {
        throw refused("payload file missing: " + entry, "add the file or fix files[].path");
      }
      f.put("sha256", files.sha256(payload));
      order.add(entry);
    }
    for (JsonNode node : tree.path("sql")) {
      if (!(node instanceof ObjectNode s)) {
        continue;
      }
      hashInto(s, "file", "sha256", root, order);
      if (s.hasNonNull("rollbackFile")) {
        hashInto(s, "rollbackFile", "rollbackSha256", root, order);
      }
    }
    for (JsonNode node : tree.path("checks")) {
      if (node instanceof ObjectNode c) {
        hashInto(c, "file", "sha256", root, order);
      }
    }
  }

  private void hashInto(
      ObjectNode node, String pathField, String hashField, Path root, List<String> order)
      throws IOException {
    String entry = node.path(pathField).asText("");
    Path file = root.resolve(entry);
    if (entry.isEmpty() || !Files.isRegularFile(file)) {
      throw refused("bundle file missing: " + entry, "add the file or fix " + pathField);
    }
    node.put(hashField, files.sha256(file));
    order.add(entry);
  }

  private static void refuseUnknownFiles(Path root, Set<String> listed) {
    List<String> unknown = new ArrayList<>();
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String rel = HotfixBundle.relative(root, file);
              if (!rel.equals(HotfixBundle.MANIFEST) && !listed.contains(rel)) {
                unknown.add(rel);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw refused("cannot list " + root + ": " + e.getMessage(), "check the bundle directory");
    }
    if (!unknown.isEmpty()) {
      throw refused(
          "files not listed in the manifest: " + String.join(", ", unknown),
          "list them in files[], sql[] or checks[], or remove them");
    }
  }

  private String sign(byte[] manifestBytes, SecretRef ref) {
    try (Secret secret = secrets.resolve(ref)) {
      char[] chars = secret.chars();
      byte[] ascii = new byte[chars.length];
      try {
        for (int i = 0; i < chars.length; i++) {
          ascii[i] = (byte) chars[i];
        }
        byte[] der = Base64.getMimeDecoder().decode(ascii);
        try {
          PrivateKey key =
              KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
          return Ed25519.encodeSignature(Ed25519.sign(key, manifestBytes));
        } finally {
          Arrays.fill(der, (byte) 0);
        }
      } finally {
        Arrays.fill(chars, '\0');
        Arrays.fill(ascii, (byte) 0);
      }
    } catch (SecretException e) {
      throw refused(e.getMessage(), "fix " + ref.render());
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw refused(
          ref.render() + " does not hold a base64 PKCS#8 Ed25519 private key",
          "generate one with jrsctl keys generate and store it behind " + ref.render());
    }
  }

  private static void write(
      Path out, byte[] manifestBytes, String signature, Path root, List<String> order, String id)
      throws IOException {
    Path parent = out.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (OutputStream raw = Files.newOutputStream(out);
        ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
      zip.setComment("jrsctl hotfix " + id);
      zip.putNextEntry(new ZipEntry(HotfixBundle.MANIFEST));
      zip.write(manifestBytes);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry(HotfixBundle.SIGNATURE));
      zip.write((signature + "\n").getBytes(StandardCharsets.US_ASCII));
      zip.closeEntry();
      for (String entry : new LinkedHashSet<>(order)) {
        zip.putNextEntry(new ZipEntry(entry));
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = Files.newInputStream(root.resolve(entry))) {
          int read;
          while ((read = in.read(buffer)) != -1) {
            zip.write(buffer, 0, read);
          }
        }
        zip.closeEntry();
      }
    }
  }

  private static ObjectNode readTree(Path manifestFile) {
    try (InputStream in = Files.newInputStream(manifestFile)) {
      JsonNode node = Json.mapper().readTree(in);
      if (!(node instanceof ObjectNode object)) {
        throw refused(manifestFile + " is not a JSON object", "fix the manifest");
      }
      return object;
    } catch (JsonProcessingException e) {
      throw refused(
          manifestFile + " is not valid JSON: " + e.getOriginalMessage(), "fix the manifest");
    } catch (IOException e) {
      throw refused("cannot read " + manifestFile + ": " + e.getMessage(), "check the file");
    }
  }

  private static HotfixException refused(String message, String remediation) {
    return new HotfixException(HotfixException.PRECHECK, message, remediation);
  }
}
