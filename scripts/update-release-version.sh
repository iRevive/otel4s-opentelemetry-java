#!/bin/bash -e

version=$1

if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' "s/(version *)\"[^\"]*\"/\1\"$version\"/" build.gradle

  sed -E -i '' "s/(\"otel4s-opentelemetry-javaagent\" % \")[^\"]+\"/\1${version}\"/" README.md
  sed -E -i '' "s/(\\$ RELEASE_VERSION=\")[^\"]*(\")/\1$version\2/" README.md
  sed -E -i '' "s/(\\$ git tag -a v)[^ ]+( -m \"v)[^\"]+(\")/\1$version\2$version\3/" README.md
  sed -E -i '' "s/(\\$ git push origin v).*/\1$version/" README.md
else
  sed -E -i "s/(version *)\"[^\"]*\"/\1\"$version\"/" build.gradle

  sed -E -i "s/(\"otel4s-opentelemetry-javaagent\" % \")[^\"]+\"/\1${version}\"/" README.md
  sed -E -i "s/(\\$ RELEASE_VERSION=\")[^\"]*(\")/\1$version\2/" README.md
  sed -E -i "s/(\\$ git tag -a v)[^ ]+( -m \"v)[^\"]+(\")/\1$version\2$version\3/" README.md
  sed -E -i "s/(\\$ git push origin v).*/\1$version/" README.md
fi
