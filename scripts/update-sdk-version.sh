#!/bin/bash -e

version=$1

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' "s/(opentelemetrySdk *: )\"[^\"]*\"/\1\"$version\"/" build.gradle
  sed -E -i '' "s/(- OpenTelemetry SDK: )[^\"]*$/\1$version/" README.md
else
  sed -E -i "s/(opentelemetrySdk *: )\"[^\"]*\"/\1\"$version\"/" build.gradle
  sed -E -i "s/(- OpenTelemetry SDK: )[^\"]*$/\1$version/" README.md
fi
