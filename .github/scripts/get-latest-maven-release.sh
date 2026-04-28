#!/usr/bin/env bash
set -euo pipefail

group=${1:?maven group is required}
artifact=${2:?maven artifact is required}

group_path=${group//./\/}
metadata_url="https://repo1.maven.org/maven2/${group_path}/${artifact}/maven-metadata.xml"

release_version=$(
  curl -fsSL "${metadata_url}" \
    | tr -d '\n' \
    | sed -n 's:.*<release>\([^<]*\)</release>.*:\1:p'
)

if [[ -z "${release_version}" ]]; then
  echo "failed to resolve release version from ${metadata_url}" >&2
  exit 1
fi

echo "${release_version}"
