# otel4s-opentelemetry-java

> [!WARNING]
> The `otel4s-opentelemetry-java` is an **experimental** distribution of the [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation) 
> that includes instrumentation for [Cats Effect](https://github.com/typelevel/cats-effect) and [otel4s](https://github.com/typelevel/otel4s).
> 
> These instrumentations are somewhat hacky and may behave unpredictably in complex or non-trivial environments.
> Please refer to the `Limitations` section before using the agent.

## Introduction

The agent is identical to the [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation), 
but also includes instrumentation for [Cats Effect](https://github.com/typelevel/cats-effect) and [otel4s](https://github.com/typelevel/otel4s).

See https://github.com/iRevive/otel4s-showcase demo.

## Versions

- OpenTelemetry SDK: 1.52.0
- OpenTelemetry Java Agent: 2.18.1

## Getting started

The agent can be configured via [sbt-javaagent](https://github.com/sbt/sbt-javaagent) plugin:
```sbt
lazy val service = project
  .enablePlugins(JavaAgent)
  .in(file("service"))
  .settings(
    name := "service",
    javaAgents += "io.github.irevive" % "otel4s-opentelemetry-javaagent" % "0.0.1", // <1>
    run / fork  := true,                                                            // <2>
    javaOptions += "-Dcats.effect.trackFiberContext=true",                          // <3>
    libraryDependencies ++= Seq(                                                    // <4>
      "org.typelevel"   %% "otel4s-oteljava"                           % "0.13.1",
      "org.typelevel"   %% "otel4s-oteljava-context-storage"           % "0.13.1",
      "io.opentelemetry" % "opentelemetry-exporter-otlp"               % "1.52.0" % Runtime,
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % "1.52.0" % Runtime
    )
  )
```

1. Register `otel4s-opentelemetry-javaagent` as a Java agent
2. Make sure the VM will be forked when running
3. Enable Cats Effect fiber context tracking
4. Add all necessary dependencies

____

The application can be configured in the following way: 

```scala
object Server extends IOApp.Simple {
  def run: IO[Unit] = {
    given LocalProvider[IO, Context] = IOLocalContextStorage.localProvider[IO] // <1>

    for {
      otel4s                   <- Resource.eval(OtelJava.global[IO])           // <2>
      given MeterProvider[IO]  <- Resource.pure(otel4s.meterProvider)
      given TracerProvider[IO] <- Resource.pure(otel4s.tracerProvider)
      given Meter[IO]          <- Resource.eval(MeterProvider[IO].get("service"))
      given Tracer[IO]         <- Resource.eval(TracerProvider[IO].get("service"))
      // your app logic
    } yield ()
  }.useForever
}
```

1. `IOLocalContextStorage.localProvider` - will automatically pick up agent's context when available
2. `OtelJava.global[IO]` - you must use the global instance, since the agent will autoconfigure it

If everything is configured correctly, you will see the following log entries:
```
[otel.javaagent 2025-07-27 09:37:18:069 +0300] [main] INFO io.opentelemetry.javaagent.tooling.VersionLogger - opentelemetry-javaagent - version: otel4s-0.0.1-otel-2.18.1
IOLocalContextStorage: agent-provided IOLocal is detected
```

## How the instrumentation works

Cats Effect has its context propagation mechanism known as [IOLocal](https://typelevel.org/cats-effect/docs/core/io-local). The [3.6.0](https://github.com/typelevel/cats-effect/releases/tag/v3.6.0) release provides a way to represent IOLocal as a `ThreadLocal`, which creates an opportunity to manipulate the context from the outside.

- Agent instruments the constructor of `IORuntime` and stores a `ThreadLocal` representation of the `IOLocal[Context]` in the **bootstrap** classloader, so the agent and application both access the same instance
- Instrumentation installs a custom `ContextStorage` wrapper (for the agent context storage). This wrapper uses `FiberLocalContextHelper` to retrieve the fiber's current context (if available)
- Agent instruments `IOFiber`'s constructor and starts the fiber with the currently available context

## Limitations

This instrumentation is not designed to support multiple deployments within the same JVM. 
For example, when deploying two WAR files to the same Tomcat instance, issues may arise since both instances will try
to configure the bootstrap's shared context.

Check the https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/13576 for more information.

## Development

### How to run smoke tests

Scala 2.13:
```shell
$ export SMOKE_TEST_JAVA_VERSION=11 
$ export SMOKE_TEST_SCALA_VERSION=2.13
$ ./gradlew smoke-tests-images:http4s:jibDockerBuild -Djib.dockerClient.executable=$(which docker)                         
$ ./gradlew smoke-tests:build
```

Scala 3:
```shell
$ export SMOKE_TEST_JAVA_VERSION=11 
$ export SMOKE_TEST_SCALA_VERSION=3
$ ./gradlew smoke-tests-images:http4s:jibDockerBuild -Djib.dockerClient.executable=$(which docker)                         
$ ./gradlew smoke-tests:build
```
