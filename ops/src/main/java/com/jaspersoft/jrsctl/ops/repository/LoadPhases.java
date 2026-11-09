package com.jaspersoft.jrsctl.ops.repository;

/** Phase names of the load plan (spec §10.5), in run order. */
final class LoadPhases {
  static final String PREFLIGHT = "preflight";
  static final String CREATE = "create";
  static final String INITIALISE = "initialise";
  static final String LOAD = "load";
  static final String VERIFY = "verify";

  private LoadPhases() {}
}
