package com.jaspersoft.jrsctl.ops;

import com.jaspersoft.jrsctl.jrs.api.ExportImportStrategy;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code --strategy rest|vendor} flag shared by {@code export} and {@code import}. Invariant:
 * the two spellings accepted here are the ones the operator guide and the console send; anything
 * else is a usage error (exit 1) raised before any bootstrap happens.
 */
public final class StrategyFlag {

  public static final String REST = "rest";
  public static final String VENDOR = "vendor";

  private StrategyFlag() {}

  /** Empty for a null or blank flag (automatic selection). */
  public static Optional<ExportImportStrategy.Kind> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case REST -> Optional.of(ExportImportStrategy.Kind.REST);
      case VENDOR -> Optional.of(ExportImportStrategy.Kind.VENDOR_CLI);
      default ->
          throw new IllegalArgumentException(
              "--strategy must be '" + REST + "' or '" + VENDOR + "', not '" + value + "'");
    };
  }

  /** The flag spelling of a kind, for stored arguments and JSON. */
  public static String render(ExportImportStrategy.Kind kind) {
    return switch (kind) {
      case REST -> REST;
      case VENDOR_CLI -> VENDOR;
    };
  }
}
