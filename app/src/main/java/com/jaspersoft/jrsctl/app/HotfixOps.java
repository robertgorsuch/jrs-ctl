package com.jaspersoft.jrsctl.app;

import com.jaspersoft.jrsctl.ops.Services;
import com.jaspersoft.jrsctl.ops.hotfix.HotfixOperations;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;

/**
 * Where the CLI obtains its {@link HotfixOperations}. Invariants: production code loads {@code
 * ops.hotfix.DefaultHotfixOperations(Services)} by its fixed name so the CLI compiles and its unit
 * tests run against a fake even while that implementation is being built in another branch; the
 * implementation's own constructor exception is rethrown untouched so configuration and secret
 * problems keep their exit codes; the factory is replaced only by tests.
 */
final class HotfixOps {

  static final String DEFAULT_CLASS = "com.jaspersoft.jrsctl.ops.hotfix.DefaultHotfixOperations";

  static final Function<Services, HotfixOperations> DEFAULT_FACTORY = HotfixOps::loadDefault;

  static volatile Function<Services, HotfixOperations> factory = DEFAULT_FACTORY;

  private HotfixOps() {}

  static HotfixOperations open(Services services) {
    return factory.apply(services);
  }

  private static HotfixOperations loadDefault(Services services) {
    try {
      Class<?> type = Class.forName(DEFAULT_CLASS);
      Object instance = type.getConstructor(Services.class).newInstance(services);
      return HotfixOperations.class.cast(instance);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException("cannot create hotfix operations: " + e.getCause(), e);
    } catch (ReflectiveOperationException | ClassCastException e) {
      throw new IllegalStateException(
          "hotfix operations are not available in this build (" + DEFAULT_CLASS + ")", e);
    }
  }
}
