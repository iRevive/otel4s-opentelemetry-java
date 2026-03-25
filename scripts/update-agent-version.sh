#!/bin/bash -e

version=$1

if [[ $version == *-SNAPSHOT ]]; then
  alpha_version=${version//-SNAPSHOT/-alpha-SNAPSHOT}
else
  alpha_version=${version}-alpha
fi

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' "s/(opentelemetryJavaagent *: )\"[^\"]*\"/\1\"$version\"/" build.gradle
  sed -E -i '' "s/(opentelemetryJavaagentAlpha *: )\"[^\"]*\"/\1\"$alpha_version\"/" build.gradle

  sed -E -i '' "s/(classpath \"io.opentelemetry.instrumentation:gradle-plugins:)[^\"]*\"/\1$alpha_version\"/" build.gradle

  sed -E -i '' "s/(- OpenTelemetry Java Agent: )[^\"]*$/\1$version/" README.md
  sed -E -i '' "s/(javaAgents \\+= \"io\\.github\\.irevive\" % \"otel4s-opentelemetry-javaagent\" % \")[^\"]+(\".*)/\1$version\2/" README.md
  sed -E -i '' "s/(\\$ AGENT_VERSION=\")[^\"]*(\")/\1$version\2/" README.md
else
  sed -E -i "s/(opentelemetryJavaagent *: )\"[^\"]*\"/\1\"$version\"/" build.gradle
  sed -E -i "s/(opentelemetryJavaagentAlpha *: )\"[^\"]*\"/\1\"$alpha_version\"/" build.gradle

  sed -E -i "s/(classpath \"io.opentelemetry.instrumentation:gradle-plugins:)[^\"]*\"/\1$alpha_version\"/" build.gradle

  sed -E -i "s/(- OpenTelemetry Java Agent: )[^\"]*$/\1$version/" README.md
  sed -E -i "s/(javaAgents \\+= \"io\\.github\\.irevive\" % \"otel4s-opentelemetry-javaagent\" % \")[^\"]+(\".*)/\1$version\2/" README.md
  sed -E -i "s/(\\$ AGENT_VERSION=\")[^\"]*(\")/\1$version\2/" README.md
fi
