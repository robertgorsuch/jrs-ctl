package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.jobs.DefaultJobsOperations;
import com.jaspersoft.jrsctl.ops.jobs.JobsOperations;
import java.util.function.Function;

/**
 * Where the CLI obtains its {@link JobsOperations}. Invariant: production code always builds {@link
 * DefaultJobsOperations} over the bootstrapped {@link Services}; the factory is replaced only by
 * tests, and always restored to {@link #DEFAULT_FACTORY} afterwards.
 */
final class JobsOps {

  static final Function<Services, JobsOperations> DEFAULT_FACTORY = DefaultJobsOperations::new;

  static volatile Function<Services, JobsOperations> factory = DEFAULT_FACTORY;

  private JobsOps() {}

  static JobsOperations open(Services services) {
    return factory.apply(services);
  }
}
