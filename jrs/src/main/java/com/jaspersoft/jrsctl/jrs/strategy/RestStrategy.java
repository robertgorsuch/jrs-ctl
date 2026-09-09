package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Export and import over the async REST endpoints while the server keeps running (spec §7.3, §9.2).
 * Invariants: no service stop; every step reads what it needs from the run context ({@code
 * JrsAdapter}, {@code Config}, {@code Redactor}, and {@code SecretResolver} only when a source
 * keystore password must be resolved); the export phase is server-read-only, the import phase is
 * repository-mutating and relies on the ops layer's pre-import snapshot for rollback; task handles
 * are persisted per run so a crashed run converges on re-execution.
 */
public final class RestStrategy implements ExportImportStrategy {

  public static final String EXPORT_PHASE = "export";
  public static final String IMPORT_PHASE = "import";

  private final Polling polling;
  private final VendorAccess vendor;

  public RestStrategy() {
    this(Polling.defaults(), VendorAccess.fromContext());
  }

  public RestStrategy(Polling polling) {
    this(polling, VendorAccess.fromContext());
  }

  public RestStrategy(Polling polling, VendorAccess vendor) {
    this.polling = Objects.requireNonNull(polling, "polling");
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  @Override
  public Kind kind() {
    return Kind.REST;
  }

  @Override
  public boolean requiresServiceStop() {
    return false;
  }

  @Override
  public List<Step> exportSteps(ExportRequest request) {
    Objects.requireNonNull(request, "request");
    return List.of(
        new StartExport(request),
        new PollExport(polling),
        new DownloadExport(request.output()),
        new WriteSidecar(EXPORT_PHASE, request, Kind.REST, polling.clock()));
  }

  @Override
  public List<Step> importSteps(ImportRequest request) {
    Objects.requireNonNull(request, "request");
    List<Step> steps = new ArrayList<>();
    steps.add(new CheckKeystoreFingerprint(IMPORT_PHASE, request));
    if (request.sourceKeystore().isPresent()) {
      steps.add(new ImportSourceKeystore(IMPORT_PHASE, request, vendor));
    }
    steps.add(new StartImport(request));
    steps.add(new PollImport(polling));
    steps.add(new VerifyImport());
    return List.copyOf(steps);
  }
}
