#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "${repo_root}"

current_agent_version=$(sed -n 's/.*opentelemetryJavaagent *: "\(.*\)",/\1/p' build.gradle | head -n1)
current_sdk_version=$(sed -n 's/.*opentelemetrySdk *: "\(.*\)",/\1/p' build.gradle | head -n1)
current_release_version=$(sed -n 's/^version "\(.*\)"/\1/p' build.gradle | head -n1)

latest_agent_version=${AGENT_VERSION_OVERRIDE:-$("${repo_root}/.github/scripts/get-latest-github-release.sh" "open-telemetry/opentelemetry-java-instrumentation")}
latest_sdk_version=${SDK_VERSION_OVERRIDE:-$("${repo_root}/.github/scripts/get-latest-maven-release.sh" "io.opentelemetry" "opentelemetry-bom")}
latest_release_version=${RELEASE_VERSION_OVERRIDE:-${latest_agent_version}}

updated=false

if [[ "${latest_sdk_version}" != "${current_sdk_version}" ]]; then
  "${repo_root}/scripts/update-sdk-version.sh" "${latest_sdk_version}"
  updated=true
fi

if [[ "${latest_agent_version}" != "${current_agent_version}" ]]; then
  "${repo_root}/scripts/update-agent-version.sh" "${latest_agent_version}"
  updated=true
fi

if [[ "${latest_release_version}" != "${current_release_version}" ]]; then
  "${repo_root}/scripts/update-release-version.sh" "${latest_release_version}"
  updated=true
fi

if [[ "${updated}" == "true" ]]; then
  commit_title="Update OTel SDK to ${latest_sdk_version}, OTel Agent to ${latest_agent_version}"
else
  commit_title="No upstream OpenTelemetry dependency updates available"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "updated=${updated}"
    echo "sdk_version=${latest_sdk_version}"
    echo "agent_version=${latest_agent_version}"
    echo "release_version=${latest_release_version}"
    echo "commit_title=${commit_title}"
  } >> "${GITHUB_OUTPUT}"
fi

echo "${commit_title}"
