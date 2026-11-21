/*
 */

package example;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubscribedFutureTest {
  @Test
  void producerCannotCompleteUntilSubscriberIsRegistered() throws Exception {
    SubscribedFuture<String> future = new SubscribedFuture<>();
    CountDownLatch producerStarted = new CountDownLatch(1);
    CompletableFuture<Void> producer =
        CompletableFuture.runAsync(
            () -> {
              producerStarted.countDown();
              try {
                future.awaitSubscription();
                future.complete("done");
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
            });
    assertTrue(producerStarted.await(5, TimeUnit.SECONDS));
    assertFalse(future.isDone());
    assertEquals("done", future.handle((value, error) -> value).get(5, TimeUnit.SECONDS));
    producer.get(5, TimeUnit.SECONDS);
  }

  @Test
  void cancellationReleasesProducerEvenWithoutSubscriber() throws Exception {
    SubscribedFuture<String> future = new SubscribedFuture<>();
    future.cancel(true);
    CompletableFuture<Boolean> released =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                future.awaitSubscription();
                return false;
              } catch (Exception expected) {
                return true;
              }
            });
    assertTrue(released.get(5, TimeUnit.SECONDS));
  }
}
