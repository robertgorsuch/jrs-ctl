package com.jaspersoft.jrsctl.core.state;

/** Terminal state of a run; a run without one is pending recovery (spec §5.5). */
public enum TerminalState {
  SUCCEEDED,
  ROLLED_BACK,
  FAILED,
  CANCELLED,
  PRECHECK_FAILED
}
