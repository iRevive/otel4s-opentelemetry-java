/*
 */

package io.opentelemetry.javaagent.bootstrap.catseffect.v3_6;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** The helper stores a reference to the fiber's context storage. */
public final class FiberLocalContextHelper {

  private static final Logger logger = Logger.getLogger(FiberLocalContextHelper.class.getName());

  private static final AtomicReference<ContextStorage> fiberContextStorage =
      new AtomicReference<>();

  private static final AtomicReference<Supplier<Boolean>> isUnderFiberContextSupplier =
      new AtomicReference<>(() -> false);

  public static void initialize(ContextStorage storage, Supplier<Boolean> isUnderFiberContext) {
    if (fiberContextStorage.compareAndSet(null, storage)) {
      isUnderFiberContextSupplier.set(isUnderFiberContext);
      logger.fine("The fiber context storage is configured");
    } else if (fiberContextStorage.get() != storage) {
      logger.warning("The fiber context storage is already configured. Ignoring subsequent calls.");
    }
  }

  public static Boolean isUnderFiberContext() {
    return isUnderFiberContextSupplier.get().get();
  }

  // @Nullable
  public static Context current() {
    ContextStorage storage = fiberContextStorage.get();
    return storage != null ? storage.current() : null;
  }

  public static Scope attach(Context toAttach) {
    ContextStorage storage = fiberContextStorage.get();
    return storage != null ? storage.attach(toAttach) : Scope.noop();
  }

  private FiberLocalContextHelper() {}
}
