package com.jaspersoft.jrsctl.jrs.strategy;

import com.jaspersoft.jrsctl.core.config.Config;
import com.jaspersoft.jrsctl.core.engine.CheckResult;
import com.jaspersoft.jrsctl.core.engine.Context;
import com.jaspersoft.jrsctl.core.platform.Platform;
import com.jaspersoft.jrsctl.core.redact.Redactor;
import com.jaspersoft.jrsctl.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsctl.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsctl.jrs.vendor.VendorTools;
import java.nio.file.Path;
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

  /**
   * Review finding 2.8: {@code js-export.sh} and {@code js-import.sh} expand their arguments
   * unquoted ({@code $*}), so a path holding whitespace reaches the vendor tool in pieces. On Linux
   * such a path is refused before the service is stopped; the batch wrappers keep quoted tokens.
   */
  public static Optional<CheckResult> refuseSpaces(Context ctx, Path path, String what) {
    if (ctx.platform().os() != Platform.OsFamily.LINUX) {
      return Optional.empty();
    }
    String p = path.toString();
    for (int i = 0; i < p.length(); i++) {
      if (Character.isWhitespace(p.charAt(i))) {
        return Optional.of(
            CheckResult.fail(
                what
                    + " "
                    + path
                    + " contains a space, which the vendor shell wrappers pass unquoted",
                "choose a path without spaces for the vendor strategy"));
      }
    }
    return Optional.empty();
  }

  public Optional<Buildomatic> locate(Context ctx) {
    Config config = ctx.service(Config.class);
    return config.server().installDir().flatMap(dir -> locator.apply(ctx).locate(dir));
  }
}
