package com.jaspersoft.jrsctl.ops.passwords;

/** Phase names of the migration plan (spec §10.6), in run order. */
final class PasswordPhases {
  static final String PREFLIGHT = "preflight";
  static final String MIGRATE = "migrate";
  static final String VERIFY = "verify";
  static final String CLEANUP = "cleanup";

  private PasswordPhases() {}
}
