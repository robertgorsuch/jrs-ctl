package com.jaspersoft.jrsctl.app.console;

import com.jaspersoft.jrsctl.core.engine.Plan;

/**
 * Builds the {@link Plan} of a registered operation from its JSON arguments; the CLI's plan
 * registry adapted for the console so the same builders (and therefore the same fingerprints) serve
 * {@code POST /api/plan}, {@code POST /api/run} and {@code runs recover}. Invariants: an operation
 * this build does not know raises {@link UnsupportedOperationException} (the console answers 501);
 * arguments that do not fit the operation raise {@link IllegalArgumentException} (400); building
 * never mutates anything.
 */
@FunctionalInterface
public interface PlanBuilder {

  Plan build(String operation, String argsJson);
}
