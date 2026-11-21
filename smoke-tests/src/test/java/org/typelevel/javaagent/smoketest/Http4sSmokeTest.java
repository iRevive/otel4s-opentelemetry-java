/*
 */

package org.typelevel.javaagent.smoketest;

import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;

class Http4sSmokeTest extends SmokeTest {

  @Override
  protected Map<String, String> getExtraEnv() {
    return Map.of(
        "JAVA_TOOL_OPTIONS",
        "-javaagent:/opentelemetry-javaagent.jar -Dcats.effect.trackFiberContext=true");
  }

  @Override
  protected String getTargetImage(int jdk, String scala) {
    return "smoke-test-http4s:jdk" + jdk + "-scala" + scala + "-latest";
  }

  @Override
  protected WaitStrategy getTargetWaitStrategy() {
    return Wait.forLogMessage(".*Started http4s server.*", 1)
        .withStartupTimeout(Duration.ofMinutes(1));
  }

  @Test
  void contextSurvivesNettyFutureListener() throws IOException, InterruptedException {
    assertContextSurvives("netty");
  }

  @Test
  void contextSurvivesWarmNettyFutureListener() throws IOException, InterruptedException {
    assertContextSurvives("netty-warm");
  }

  @Test
  void contextSurvivesPlainCompletableFuture() throws IOException, InterruptedException {
    assertContextSurvives("plain");
  }

  private void assertContextSurvives(String boundary) throws IOException, InterruptedException {
    startTarget();
    try {
      Request request =
          new Request.Builder()
              .url(
                  String.format(
                      "http://%s:%d/context/%s",
                      target.getHost(), target.getMappedPort(8080), boundary))
              .build();
      String[] contexts;
      try (Response response = client.newCall(request).execute()) {
        Assertions.assertEquals(200, response.code());
        contexts = response.body().string().split("\n");
      }

      Assertions.assertEquals(6, contexts.length);
      Assertions.assertTrue(TraceId.isValid(contexts[0].split("/")[0]));
      String[] observations = {
        "Java before", "otel4s before", "Java callback", "Java after", "otel4s after"
      };
      for (int i = 1; i < contexts.length; i++) {
        Assertions.assertEquals(contexts[0], contexts[i], observations[i - 1] + " " + boundary);
      }

      Collection<ExportTraceServiceRequest> traces =
          waitForSpans(boundary + ".parent", boundary + ".child");
      Span parent = singleSpan(traces, boundary + ".parent");
      Span child = singleSpan(traces, boundary + ".child");
      Assertions.assertEquals(parent.getTraceId(), child.getTraceId());
      Assertions.assertEquals(parent.getSpanId(), child.getParentSpanId());
    } finally {
      stopTarget();
    }
  }

  private static Span singleSpan(Collection<ExportTraceServiceRequest> traces, String name) {
    List<Span> spans =
        getSpanStream(traces)
            .filter(span -> span.getName().equals(name))
            .collect(Collectors.toList());
    Assertions.assertEquals(1, spans.size(), name);
    return spans.get(0);
  }

  @Test
  public void http4sSmokeTestOnJDK() throws IOException, InterruptedException {
    startTarget();
    String param = "test";
    String url = String.format("http://localhost:%d/welcome/" + param, target.getMappedPort(8080));
    Request request = new Request.Builder().url(url).get().build();

    String currentAgentVersion =
        (String)
            new JarFile(agentPath)
                .getManifest()
                .getMainAttributes()
                .get(Attributes.Name.IMPLEMENTATION_VERSION);

    Response response = client.newCall(request).execute();
    System.out.println(response.headers().toString());

    Collection<ExportTraceServiceRequest> traces = waitForSpans("request.handler");

    Assertions.assertNotNull(response.header("traceparent"));
    Assertions.assertTrue(TraceId.isValid(response.header("traceparent").split("-")[1]));
    Assertions.assertEquals(param, response.body().string());
    Assertions.assertEquals(1, countSpansByName(traces, "request.handler"));
    Assertions.assertEquals(1, countSpansByAttributeValue(traces, "str", param));
    Assertions.assertNotEquals(
        0, countResourcesByValue(traces, "telemetry.distro.version", currentAgentVersion));
    Assertions.assertNotEquals(
        0, countResourcesByValue(traces, "telemetry.distro.name", "otel4s-opentelemetry-java"));

    stopTarget();
  }
}
