#!/usr/bin/env bash
set -euo pipefail

repo=${1:?repository is required, e.g. owner/name}

tag_name=$(gh api "repos/${repo}/releases/latest" --jq '.tag_name')
echo "${tag_name#v}"
