#!/bin/bash -e

version=$1

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' "s/(version *)\"[^\"]*\"/\1\"$version\"/" build.gradle

  sed -E -i '' "s/(\"otel4s-opentelemetry-javaagent\" % \")[^\"]+\"/\1${version}\"/" README.md
else
  sed -E -i "s/(version *)\"[^\"]*\"/\1\"$version\"/" build.gradle

  sed -E -i "s/(\"otel4s-opentelemetry-javaagent\" % \")[^\"]+\"/\1${version}\"/" README.md
fi
