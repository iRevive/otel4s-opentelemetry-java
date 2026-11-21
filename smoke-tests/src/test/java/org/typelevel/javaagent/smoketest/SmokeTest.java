/*
 */

package org.typelevel.javaagent.smoketest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.MountableFile;

abstract class SmokeTest {
  private static final Logger logger = LoggerFactory.getLogger(SmokeTest.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // use JDK 11 for local tests
  private static final int SMOKE_TEST_JAVA_VERSION =
      Integer.parseInt(Objects.requireNonNullElse(System.getenv("SMOKE_TEST_JAVA_VERSION"), "11"));

  // use Scala 3 for local tests
  private static final String SMOKE_TEST_SCALA_VERSION =
      Objects.requireNonNullElse(System.getenv("SMOKE_TEST_SCALA_VERSION"), "3");

  protected static OkHttpClient client = OkHttpUtils.client();

  private static final Network network = Network.newNetwork();
  protected static final String agentPath =
      System.getProperty("io.opentelemetry.smoketest.agent.shadowJar.path");

  protected abstract String getTargetImage(int jdk, String scala);

  protected abstract WaitStrategy getTargetWaitStrategy();

  /** Subclasses can override this method to customise target application's environment */
  protected Map<String, String> getExtraEnv() {
    return Collections.emptyMap();
  }

  private static GenericContainer backend;

  @BeforeAll
  static void setup() {
    backend =
        new GenericContainer<>(
                "ghcr.io/open-telemetry/opentelemetry-java-instrumentation/smoke-test-fake-backend:20221127.3559314891")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forPort(8080))
            .withNetwork(network)
            .withNetworkAliases("backend")
            .withLogConsumer(new Slf4jLogConsumer(logger));
    backend.start();
  }

  protected GenericContainer target;

  void startTarget() {
    target =
        new GenericContainer<>(getTargetImage(SMOKE_TEST_JAVA_VERSION, SMOKE_TEST_SCALA_VERSION))
            .withExposedPorts(8080)
            .withNetwork(network)
            .withLogConsumer(new Slf4jLogConsumer(logger))
            .withCopyFileToContainer(
                MountableFile.forHostPath(agentPath), "/opentelemetry-javaagent.jar")
            .withEnv("JAVA_TOOL_OPTIONS", "-javaagent:/opentelemetry-javaagent.jar")
            .withEnv("OTEL_BSP_MAX_EXPORT_BATCH", "1")
            .withEnv("OTEL_BSP_SCHEDULE_DELAY", "10")
            .withEnv("OTEL_PROPAGATORS", "tracecontext,baggage")
            .withEnv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://backend:8080")
            .withEnv("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc")
            .withEnv(getExtraEnv())
            .waitingFor(getTargetWaitStrategy());
    target.start();
  }

  @AfterEach
  void reset() throws IOException {
    client
        .newCall(
            new Request.Builder()
                .url(
                    String.format(
                        "http://%s:%d/clear", backend.getHost(), backend.getMappedPort(8080)))
                .build())
        .execute()
        .close();
  }

  void stopTarget() {
    target.stop();
  }

  @AfterAll
  static void cleanup() {
    backend.stop();
  }

  protected static int countResourcesByValue(
      Collection<ExportTraceServiceRequest> traces, String resourceName, String value) {
    return (int)
        traces.stream()
            .flatMap(it -> it.getResourceSpansList().stream())
            .flatMap(it -> it.getResource().getAttributesList().stream())
            .filter(
                kv ->
                    kv.getKey().equals(resourceName)
                        && kv.getValue().getStringValue().equals(value))
            .count();
  }

  protected static int countSpansByName(
      Collection<ExportTraceServiceRequest> traces, String spanName) {
    return (int) getSpanStream(traces).filter(it -> it.getName().equals(spanName)).count();
  }

  protected static int countSpansByAttributeValue(
      Collection<ExportTraceServiceRequest> traces, String attributeName, String attributeValue) {
    return (int)
        getSpanStream(traces)
            .flatMap(it -> it.getAttributesList().stream())
            .filter(
                kv ->
                    kv.getKey().equals(attributeName)
                        && kv.getValue().getStringValue().equals(attributeValue))
            .count();
  }

  protected static Stream<Span> getSpanStream(Collection<ExportTraceServiceRequest> traces) {
    return traces.stream()
        .flatMap(it -> it.getResourceSpansList().stream())
        .flatMap(it -> it.getScopeSpansList().stream())
        .flatMap(it -> it.getSpansList().stream());
  }

  protected Collection<ExportTraceServiceRequest> waitForSpans(String... names)
      throws IOException, InterruptedException {
    return TracePoller.awaitSpans(this::fetchTraces, Duration.ofSeconds(30), names);
  }

  private Collection<ExportTraceServiceRequest> fetchTraces() throws IOException {
    Request request =
        new Request.Builder()
            .url(
                String.format(
                    "http://%s:%d/get-traces", backend.getHost(), backend.getMappedPort(8080)))
            .build();
    String content;
    try (ResponseBody body = client.newCall(request).execute().body()) {
      content = body.string();
    }
    Collection<ExportTraceServiceRequest> traces = new java.util.ArrayList<>();
    for (com.fasterxml.jackson.databind.JsonNode node : OBJECT_MAPPER.readTree(content)) {
      ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
      JsonFormat.parser().merge(OBJECT_MAPPER.writeValueAsString(node), builder);
      traces.add(builder.build());
    }
    return traces;
  }
}
