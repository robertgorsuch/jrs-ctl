package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.passwords.DefaultPasswordOperations;
import com.jaspersoft.jrsctl.ops.passwords.PasswordOperations;
import java.util.function.Function;

/**
 * Where the CLI obtains its {@link PasswordOperations}. Invariant: production code always builds
 * {@link DefaultPasswordOperations} over the bootstrapped {@link Services}; the factory is replaced
 * only by tests, and always restored to {@link #DEFAULT_FACTORY} afterwards.
 */
final class PasswordOps {

  static final Function<Services, PasswordOperations> DEFAULT_FACTORY =
      DefaultPasswordOperations::new;

  static volatile Function<Services, PasswordOperations> factory = DEFAULT_FACTORY;

  private PasswordOps() {}

  static PasswordOperations open(Services services) {
    return factory.apply(services);
  }
}
