package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.api.Capability;
import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsctl.jrs.api.ExportRequest;
import com.jaspersoft.jrsctl.jrs.api.ImportRequest;
import com.jaspersoft.jrsctl.jrs.api.JrsAdapter;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Strategy selection (spec §9.2): REST when the {@code EXPORT_ASYNC}/{@code IMPORT_ASYNC} probe
 * passes and the request is not a full-server export; vendor CLI when the request is full-server,
 * when the probe fails or the server cannot be reached, or when {@code --strategy} forces it.
 * Invariants: every selection carries a one-line reason for the plan summary; a forced kind always
 * wins; probing never throws out of here, an unreachable server simply selects the vendor tools.
 */
public final class Strategies {

  /** The chosen strategy and why, in one line for the plan summary. */
  public record Selection(ExportImportStrategy strategy, String reason) {
    public Selection {
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(reason, "reason");
    }

    public ExportImportStrategy.Kind kind() {
      return strategy.kind();
    }
  }

  private final RestStrategy rest;
  private final VendorCliStrategy vendor;

  public Strategies(RestStrategy rest, VendorCliStrategy vendor) {
    this.rest = Objects.requireNonNull(rest, "rest");
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  /** Production wiring: real polling, vendor tools built on the platform. */
  public static Strategies standard(Platform platform, Redactor redactor) {
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(redactor, "redactor");
    return new Strategies(
        new RestStrategy(),
        new VendorCliStrategy(
            new BuildomaticLocator(platform),
            new VendorTools(platform.processes(), platform.files(), redactor)));
  }

  public Selection select(
      Config config,
      JrsAdapter adapter,
      ExportRequest request,
      Optional<ExportImportStrategy.Kind> forced) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(request, "request");
    return select(adapter, Capability.EXPORT_ASYNC, request.fullServer(), forced);
  }

  public Selection select(
      Config config,
      JrsAdapter adapter,
      ImportRequest request,
      Optional<ExportImportStrategy.Kind> forced) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(request, "request");
    return select(adapter, Capability.IMPORT_ASYNC, false, forced);
  }

  private Selection select(
      JrsAdapter adapter,
      Capability needed,
      boolean fullServer,
      Optional<ExportImportStrategy.Kind> forced) {
    Objects.requireNonNull(adapter, "adapter");
    Objects.requireNonNull(forced, "forced");
    if (forced.isPresent()) {
      return switch (forced.get()) {
        case REST -> new Selection(rest, "REST forced by --strategy rest");
        case VENDOR_CLI -> new Selection(vendor, "vendor CLI forced by --strategy vendor");
      };
    }
    if (fullServer) {
      return new Selection(
          vendor, "vendor CLI: full-server export requires js-export with the service stopped");
    }
    Set<Capability> caps;
    try {
      caps = adapter.capabilities();
    } catch (RuntimeException e) {
      return new Selection(
          vendor, "vendor CLI: capability probe failed (" + Failures.describe(e) + ")");
    }
    if (caps.contains(needed)) {
      return new Selection(rest, "REST: " + needed + " probe passed, service stays up");
    }
    return new Selection(vendor, "vendor CLI: " + needed + " probe failed on this server");
  }
}
