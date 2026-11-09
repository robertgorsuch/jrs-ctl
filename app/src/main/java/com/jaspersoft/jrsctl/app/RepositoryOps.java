package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.repository.DefaultRepositoryOperations;
import com.jaspersoft.jrsctl.ops.repository.RepositoryOperations;
import java.util.function.Function;

/**
 * Where the CLI obtains its {@link RepositoryOperations}. Invariant: production code always builds
 * {@link DefaultRepositoryOperations} over the bootstrapped {@link Services}; the factory is
 * replaced only by tests, and always restored to {@link #DEFAULT_FACTORY} afterwards.
 */
final class RepositoryOps {

  static final Function<Services, RepositoryOperations> DEFAULT_FACTORY =
      DefaultRepositoryOperations::new;

  static volatile Function<Services, RepositoryOperations> factory = DEFAULT_FACTORY;

  private RepositoryOps() {}

  static RepositoryOperations open(Services services) {
    return factory.apply(services);
  }
}
