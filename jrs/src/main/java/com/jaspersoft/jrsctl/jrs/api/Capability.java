package com.jaspersoft.jrsctl.jrs.api;

/**
 * Server capabilities discovered by non-mutating probes after version detection (spec §7.1). The
 * adapter's behaviour is selected by this set, never by branching on the version string.
 */
public enum Capability {
  /** {@code POST /rest_v2/export} with polling is available. */
  EXPORT_ASYNC,
  /** {@code POST /rest_v2/import} with polling is available. */
  IMPORT_ASYNC,
  /** Repository passwords are encrypted with the server keystore (~/.jrsks), JRS 7.5+. */
  KEYSTORE_ENCRYPTION,
  /** Organizations (multi-tenancy) enabled; PRO only. */
  ORGS,
  /** Token-based authentication is accepted. */
  TOKEN_AUTH,
  /** Pre-authentication ({@code pp=}) is configured. */
  PREAUTH,
  /** {@code POST /rest_v2/login} session endpoint exists. */
  REST_LOGIN
}
