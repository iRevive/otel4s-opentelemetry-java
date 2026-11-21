/*
 */

package org.typelevel.javaagent.smoketest;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

final class TracePoller {
  @FunctionalInterface
  interface TraceSource {
    Collection<ExportTraceServiceRequest> fetch() throws IOException;
  }

  static Collection<ExportTraceServiceRequest> awaitSpans(
      TraceSource source, Duration timeout, String... names)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    Set<String> expected = new java.util.HashSet<>(Arrays.asList(names));
    while (true) {
      Collection<ExportTraceServiceRequest> traces = source.fetch();
      Set<String> received =
          SmokeTest.getSpanStream(traces).map(span -> span.getName()).collect(Collectors.toSet());
      if (received.containsAll(expected)) {
        return traces;
      }
      if (System.nanoTime() - started >= timeout.toNanos()) {
        throw new AssertionError(
            "Timed out waiting for spans " + expected + "; received " + received);
      }
      Thread.sleep(100);
    }
  }

  private TracePoller() {}
}
