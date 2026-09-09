package com.jaspersoft.jrsctl.ops.exim;

import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.Credentials;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.Handles;
import com.jaspersoft.jrsctl.jrs.api.HealthReport;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsctl.jrs.api.ServerIdentity;
import com.jaspersoft.jrsctl.jrs.api.Session;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A scriptable {@link JrsAdapter} for export/import tests: records every export and import start,
 * answers export polls with {@code READY}, and answers import polls from a queue of phases (empty
 * queue means {@code READY}) so a test can make the first import fail and the restore succeed.
 */
final class EximFakeAdapter implements JrsAdapter {

  static final URI BASE = URI.create("http://localhost:8080/jasperserver-pro");
  static final String FINGERPRINT =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  /** One recorded {@link #startImport} call. */
  record ImportCall(ImportRequest request, Path archive) {}

  final List<ExportRequest> exports = new ArrayList<>();
  final List<ImportCall> imports = new ArrayList<>();
  final Deque<Handles.Phase> importPhases = new ArrayDeque<>();
  Set<Capability> capabilities =
      EnumSet.of(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.KEYSTORE_ENCRYPTION);
  KeystoreInfo keystore =
      new KeystoreInfo(
          true,
          Optional.of(Path.of("/home/jasperserver/.jrsks")),
          Optional.of(Path.of("/home/jasperserver/.jrsksp")),
          Optional.of(FINGERPRINT),
          Optional.empty());
  int exportBytes = 4096;

  @Override
  public ServerIdentity identity() {
    return new ServerIdentity(
        BASE,
        "8.2.0",
        ServerIdentity.Edition.PRO,
        ServerIdentity.Tenancy.MULTI,
        Set.of("Fusion", "MT"),
        "20240101_1200",
        "yyyy-MM-dd");
  }

  @Override
  public Session login(Credentials credentials) {
    return new Session(Session.AuthMode.BASIC, Optional.empty(), Instant.EPOCH);
  }

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    exports.add(request);
    return new Handles.ExportHandle("exp-" + exports.size());
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    return new Handles.ExportStatus(
        Handles.Phase.READY, Optional.empty(), Optional.of("export.zip"), Optional.empty());
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    byte[] bytes = new byte[exportBytes];
    bytes[0] = 'P';
    bytes[1] = 'K';
    try (OutputStream out = Files.newOutputStream(target)) {
      out.write(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return target;
  }

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    imports.add(new ImportCall(request, archive));
    return new Handles.ImportHandle("imp-" + imports.size());
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    Handles.Phase phase = importPhases.isEmpty() ? Handles.Phase.READY : importPhases.poll();
    return new Handles.ImportStatus(
        phase,
        phase == Handles.Phase.FAILED ? Optional.of("simulated import failure") : Optional.empty(),
        Optional.empty());
  }

  @Override
  public KeystoreInfo keystore() {
    return keystore;
  }

  @Override
  public Set<Capability> capabilities() {
    return capabilities;
  }

  @Override
  public HealthReport health() {
    return new HealthReport(true, Duration.ofMillis(1), List.of());
  }

  @Override
  public List<String> listFolder(String folderUri) {
    return List.of("/public");
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    throw new UnsupportedOperationException("not used by export/import");
  }

  @Override
  public boolean schedulerReachable() {
    return true;
  }

  @Override
  public void createFolder(String folderUri, String label) {}

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {}

  @Override
  public void deleteResource(String uri) {}
}
