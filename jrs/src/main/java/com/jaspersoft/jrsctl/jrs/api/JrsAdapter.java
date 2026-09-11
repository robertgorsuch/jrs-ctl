package com.jaspersoft.jrsctl.jrs.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The one server adapter (spec §7.2, ADR-0004). Behaviour is driven entirely by {@link
 * #capabilities()} probed after {@link #identity()}; there is no per-version subclass. Invariants:
 * every method is safe to call repeatedly; downloads stream to disk; the only repository-mutating
 * methods are {@link #startImport}, {@link #createFolder}, {@link #uploadJrxmlReport} and {@link
 * #deleteResource}, and every one of them is only ever called from inside a {@code Step}.
 */
public interface JrsAdapter {

  ServerIdentity identity();

  /**
   * Re-reads the server identity from the server, ignoring and replacing any value cached by {@link
   * #identity()}. Callers that must observe the server's <em>current</em> reachability, such as the
   * step that waits for a service restart to finish, use this rather than {@link #identity()}.
   */
  ServerIdentity refreshIdentity();

  Session login(Credentials credentials);

  Handles.ExportHandle startExport(ExportRequest request);

  Handles.ExportStatus pollExport(Handles.ExportHandle handle);

  /** Streams the finished export to {@code target} and returns it. */
  Path downloadExport(Handles.ExportHandle handle, Path target);

  /**
   * As above, abandoning the transfer as soon as {@code cancelled} answers true; implementations
   * that cannot watch a transfer fall back to the plain download.
   */
  default Path downloadExport(Handles.ExportHandle handle, Path target, BooleanSupplier cancelled) {
    return downloadExport(handle, target);
  }

  Handles.ImportHandle startImport(ImportRequest request, Path archive);

  /** As above, abandoning the upload as soon as {@code cancelled} answers true. */
  default Handles.ImportHandle startImport(
      ImportRequest request, Path archive, BooleanSupplier cancelled) {
    return startImport(request, archive);
  }

  Handles.ImportStatus pollImport(Handles.ImportHandle handle);

  KeystoreInfo keystore();

  Set<Capability> capabilities();

  HealthReport health();

  // ---- read-only helpers used by doctor and smoke (spec §12) ----

  /** URIs of the direct children of {@code folderUri}, e.g. {@code "/"}. */
  List<String> listFolder(String folderUri);

  /** Runs the report at {@code reportUri} to PDF, streaming the bytes to {@code target}. */
  Path runReportToPdf(String reportUri, Path target);

  /** True when {@code GET /rest_v2/jobs} answers. */
  boolean schedulerReachable();

  // ---- mutating helpers used only by smoke --mutating, inside a Step ----

  void createFolder(String folderUri, String label);

  void uploadJrxmlReport(String folderUri, String label, Path jrxml);

  void deleteResource(String uri);
}
