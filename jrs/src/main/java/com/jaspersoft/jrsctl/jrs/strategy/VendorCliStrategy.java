package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.engine.Step;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Export and import with the vendor {@code js-export}/{@code js-import} tools (spec §7.3, §7.4,
 * §9.2). Invariants: the service is stopped before the tool runs and started (and waited for)
 * afterwards, in the returned steps themselves; the vendor tree is located from {@code
 * server.installDir} and verified before the service is touched; it is not a {@code JrsAdapter} and
 * only uses the context's adapter for the post-start wait and the sidecar; the export sidecar is
 * identical to the REST strategy's so imports can verify either.
 */
public final class VendorCliStrategy implements ExportImportStrategy {

  public static final String EXPORT_PHASE = "export";
  public static final String IMPORT_PHASE = "import";

  private final VendorAccess vendor;
  private final Polling polling;

  public VendorCliStrategy(BuildomaticLocator locator, VendorTools tools) {
    this(VendorAccess.fixed(locator, tools), Polling.defaults());
  }

  public VendorCliStrategy(BuildomaticLocator locator, VendorTools tools, Polling polling) {
    this(VendorAccess.fixed(locator, tools), polling);
  }

  public VendorCliStrategy(VendorAccess vendor, Polling polling) {
    this.vendor = Objects.requireNonNull(vendor, "vendor");
    this.polling = Objects.requireNonNull(polling, "polling");
  }

  @Override
  public Kind kind() {
    return Kind.VENDOR_CLI;
  }

  @Override
  public boolean requiresServiceStop() {
    return true;
  }

  @Override
  public List<Step> exportSteps(ExportRequest request) {
    Objects.requireNonNull(request, "request");
    return List.of(
        new LocateVendorTools(EXPORT_PHASE, List.of(Buildomatic.EXPORT_SCRIPT), vendor),
        ServiceSteps.stop(EXPORT_PHASE),
        new RunJsExport(request, vendor),
        ServiceSteps.start(EXPORT_PHASE),
        ServiceSteps.waitForServer(EXPORT_PHASE, polling),
        new WriteSidecar(EXPORT_PHASE, request, Kind.VENDOR_CLI, polling.clock()));
  }

  @Override
  public List<Step> importSteps(ImportRequest request) {
    Objects.requireNonNull(request, "request");
    List<Step> steps = new ArrayList<>();
    steps.add(new CheckKeystoreFingerprint(IMPORT_PHASE, request));
    steps.add(new LocateVendorTools(IMPORT_PHASE, List.of(Buildomatic.IMPORT_SCRIPT), vendor));
    steps.add(ServiceSteps.stop(IMPORT_PHASE));
    if (request.sourceKeystore().isPresent()) {
      steps.add(new ImportSourceKeystore(IMPORT_PHASE, request, vendor));
    }
    steps.add(new RunJsImport(request, vendor));
    steps.add(ServiceSteps.start(IMPORT_PHASE));
    steps.add(ServiceSteps.waitForServer(IMPORT_PHASE, polling));
    return List.copyOf(steps);
  }
}
