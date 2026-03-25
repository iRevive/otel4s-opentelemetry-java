#!/bin/bash -e

version=$1

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' "s/(opentelemetrySdk *: )\"[^\"]*\"/\1\"$version\"/" build.gradle
  sed -E -i '' "s/(- OpenTelemetry SDK: )[^\"]*$/\1$version/" README.md
  sed -E -i '' "s/(\"io\\.opentelemetry\" % \"opentelemetry-exporter-otlp\" +% \")[^\"]+(\" % Runtime)/\1$version\2/" README.md
  sed -E -i '' "s/(\"io\\.opentelemetry\" % \"opentelemetry-sdk-extension-autoconfigure\" +% \")[^\"]+(\" % Runtime)/\1$version\2/" README.md
  sed -E -i '' "s/(\\$ SDK_VERSION=\")[^\"]*(\")/\1$version\2/" README.md
else
  sed -E -i "s/(opentelemetrySdk *: )\"[^\"]*\"/\1\"$version\"/" build.gradle
  sed -E -i "s/(- OpenTelemetry SDK: )[^\"]*$/\1$version/" README.md
  sed -E -i "s/(\"io\\.opentelemetry\" % \"opentelemetry-exporter-otlp\" +% \")[^\"]+(\" % Runtime)/\1$version\2/" README.md
  sed -E -i "s/(\"io\\.opentelemetry\" % \"opentelemetry-sdk-extension-autoconfigure\" +% \")[^\"]+(\" % Runtime)/\1$version\2/" README.md
  sed -E -i "s/(\\$ SDK_VERSION=\")[^\"]*(\")/\1$version\2/" README.md
fi
