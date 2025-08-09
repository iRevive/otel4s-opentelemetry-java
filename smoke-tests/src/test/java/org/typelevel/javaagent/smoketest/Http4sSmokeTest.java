/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.typelevel.javaagent.smoketest;

import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;

class Http4sSmokeTest extends SmokeTest {

  @Override
  protected String getTargetImage(int jdk, String scala) {
    return "smoke-test-http4s:jdk"
        + jdk
        + "-scala"
        + scala
        + "-latest";
  }

  @Override
  protected WaitStrategy getTargetWaitStrategy() {
    return Wait.forLogMessage(".*Started http4s server.*", 1)
        .withStartupTimeout(Duration.ofMinutes(1));
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

    Collection<ExportTraceServiceRequest> traces = waitForTraces();

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
