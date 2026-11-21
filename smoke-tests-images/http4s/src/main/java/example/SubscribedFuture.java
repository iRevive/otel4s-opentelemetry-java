/*
 */

package example;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Keeps completion behind Cats Effect's callback registration, without a timing-based delay. */
public final class SubscribedFuture<T> extends CompletableFuture<T> {
  private final CompletableFuture<Void> subscribed = new CompletableFuture<>();

  @Override
  public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> callback) {
    CompletableFuture<U> result = super.handle(callback);
    subscribed.complete(null);
    return result;
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    boolean canceled = super.cancel(mayInterruptIfRunning);
    if (canceled) {
      subscribed.completeExceptionally(new CancellationException());
    }
    return canceled;
  }

  public void awaitSubscription() throws Exception {
    subscribed.get(30, TimeUnit.SECONDS);
  }
}
