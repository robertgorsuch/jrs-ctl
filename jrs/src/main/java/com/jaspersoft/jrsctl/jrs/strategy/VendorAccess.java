package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * How a step obtains the vendor-tool wrappers: either fixed instances handed to the strategy at
 * construction ({@link #fixed}) or built from the run context's platform and redactor at execution
 * time ({@link #fromContext}). Invariant: {@link #locate} always resolves {@code buildomatic/}
 * under {@code server.installDir} of the context's {@code Config}, so every step agrees on which
 * vendor tree is invoked.
 */
public record VendorAccess(
    Function<Context, BuildomaticLocator> locator, Function<Context, VendorTools> tools) {

  public VendorAccess {
    Objects.requireNonNull(locator, "locator");
    Objects.requireNonNull(tools, "tools");
  }

  public static VendorAccess fixed(BuildomaticLocator locator, VendorTools tools) {
    Objects.requireNonNull(locator, "locator");
    Objects.requireNonNull(tools, "tools");
    return new VendorAccess(ctx -> locator, ctx -> tools);
  }

  /** Builds both from {@code ctx.platform()} and the context's {@link Redactor}. */
  public static VendorAccess fromContext() {
    return new VendorAccess(
        ctx -> new BuildomaticLocator(ctx.platform()),
        ctx ->
            new VendorTools(
                ctx.platform().processes(),
                ctx.platform().files(),
                ctx.has(Redactor.class) ? ctx.service(Redactor.class) : Redactor.global()));
  }

  public Optional<Buildomatic> locate(Context ctx) {
    Config config = ctx.service(Config.class);
    return config.server().installDir().flatMap(dir -> locator.apply(ctx).locate(dir));
  }
}
