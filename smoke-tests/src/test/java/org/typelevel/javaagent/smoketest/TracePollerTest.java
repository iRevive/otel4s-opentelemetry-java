/*
 */

package org.typelevel.javaagent.smoketest;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TracePollerTest {
  @Test
  void waitsForParentEvenWhenPartialResultsStopChanging() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Collection<ExportTraceServiceRequest> partial = traces("child");
    Collection<ExportTraceServiceRequest> complete = traces("child", "parent");
    Collection<ExportTraceServiceRequest> result =
        TracePoller.awaitSpans(
            () -> calls.incrementAndGet() < 8 ? partial : complete,
            Duration.ofSeconds(5),
            "child",
            "parent");
    assertSame(complete, result);
    assertEquals(8, calls.get());
  }

  @Test
  void failsWithExpectedAndReceivedSpansAtDeadline() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () -> TracePoller.awaitSpans(() -> traces("child"), Duration.ZERO, "child", "parent"));
    assertTrue(error.getMessage().contains("parent"));
    assertTrue(error.getMessage().contains("received [child]"));
  }

  private static Collection<ExportTraceServiceRequest> traces(String... names) {
    ScopeSpans.Builder scope = ScopeSpans.newBuilder();
    for (String name : names) {
      scope.addSpans(Span.newBuilder().setName(name));
    }
    return Collections.singletonList(
        ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(scope))
            .build());
  }
}
