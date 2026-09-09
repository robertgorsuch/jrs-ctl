package com.jaspersoft.jrsctl.jrs.api;

import java.nio.file.Path;
import java.util.Set;

/**
 * The one server adapter (spec §7.2, ADR-0004). Behaviour is driven entirely by {@link
 * #capabilities()} probed after {@link #identity()}; there is no per-version subclass. Invariants:
 * every method is safe to call repeatedly; downloads stream to disk; no method here mutates the
 * repository except {@code startImport}, which is always wrapped in a {@code Step}.
 */
public interface JrsAdapter {

  ServerIdentity identity();

  Session login(Credentials credentials);

  Handles.ExportHandle startExport(ExportRequest request);

  Handles.ExportStatus pollExport(Handles.ExportHandle handle);

  /** Streams the finished export to {@code target} and returns it. */
  Path downloadExport(Handles.ExportHandle handle, Path target);

  Handles.ImportHandle startImport(ImportRequest request, Path archive);

  Handles.ImportStatus pollImport(Handles.ImportHandle handle);

  KeystoreInfo keystore();

  Set<Capability> capabilities();

  HealthReport health();
}
