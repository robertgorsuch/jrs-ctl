package com.jaspersoft.jrsctl.jrs.vendor;

/**
 * Every command-line flag jrsctl passes to the vendor {@code js-export} and {@code js-import}
 * scripts (spec §7.4, §9). The spellings follow the JasperReports Server Administrator Guide
 * ("Import and Export from the Command Line"). Invariant: no other class spells a vendor flag, so
 * when a vendor release renames or drops an option this file is the first and only place to check;
 * the {@code KEYSTORE}/{@code STOREPASS} pair used for the keystore import step (spec §9.3) is the
 * least certain of the set and is called out in {@link VendorTools#importKeystore}.
 */
public final class VendorFlags {

  private VendorFlags() {}

  // ---- js-export ----
  public static final String OUTPUT_ZIP = "--output-zip";
  public static final String EVERYTHING = "--everything";
  public static final String URIS = "--uris";
  public static final String REPOSITORY_PERMISSIONS = "--repository-permissions";
  public static final String USERS = "--users";
  public static final String ROLES = "--roles";

  // ---- js-import ----
  public static final String INPUT_ZIP = "--input-zip";
  public static final String UPDATE = "--update";
  public static final String SKIP_USER_UPDATE = "--skip-user-update";
  public static final String SKIP_THEMES = "--skip-themes";

  // ---- shared event/settings switches ----
  public static final String INCLUDE_ACCESS_EVENTS = "--include-access-events";
  public static final String INCLUDE_AUDIT_EVENTS = "--include-audit-events";
  public static final String INCLUDE_MONITORING_EVENTS = "--include-monitoring-events";
  public static final String INCLUDE_SERVER_SETTINGS = "--include-server-settings";

  // ---- keystore (JRS 7.5+, spec §9.3); verify against the guide for the installed version ----
  public static final String KEYSTORE = "--keystore";
  public static final String STOREPASS = "--storepass";

  /** Separator js-export expects between repository URIs. */
  public static final String URI_SEPARATOR = ",";

  /** Environment variable buildomatic reads to find its JDK. */
  public static final String JAVA_HOME = "JAVA_HOME";
}
