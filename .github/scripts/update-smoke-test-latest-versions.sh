#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "${repo_root}"

version_file="smoke-tests-images/http4s/smoke-test-versions.gradle"

current_otel4s_latest=$(sed -n 's/.*otel4sLatest *: "\(.*\)",/\1/p' "${version_file}" | head -n1)
current_cats_effect_latest=$(sed -n 's/.*catsEffectLatest *: "\(.*\)",/\1/p' "${version_file}" | head -n1)

latest_otel4s_version=${OTEL4S_VERSION_OVERRIDE:-$("${repo_root}/.github/scripts/get-latest-github-release.sh" "typelevel/otel4s")}
latest_cats_effect_version=${CATS_EFFECT_VERSION_OVERRIDE:-$("${repo_root}/.github/scripts/get-latest-github-release.sh" "typelevel/cats-effect")}

updated=false

if [[ "${latest_otel4s_version}" != "${current_otel4s_latest}" ]]; then
  sed -E -i.bak "s/(otel4sLatest *: )\"[^\"]*\"/\1\"${latest_otel4s_version}\"/" "${version_file}"
  rm -f "${version_file}.bak"
  updated=true
fi

if [[ "${latest_cats_effect_version}" != "${current_cats_effect_latest}" ]]; then
  sed -E -i.bak "s/(catsEffectLatest *: )\"[^\"]*\"/\1\"${latest_cats_effect_version}\"/" "${version_file}"
  rm -f "${version_file}.bak"
  updated=true
fi

if [[ "${updated}" == "true" ]]; then
  commit_title="Update smoke-test latest pins: otel4s ${latest_otel4s_version}, Cats Effect ${latest_cats_effect_version}"
else
  commit_title="No smoke-test latest version updates available"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "updated=${updated}"
    echo "otel4s_version=${latest_otel4s_version}"
    echo "cats_effect_version=${latest_cats_effect_version}"
    echo "commit_title=${commit_title}"
  } >> "${GITHUB_OUTPUT}"
fi

echo "${commit_title}"
