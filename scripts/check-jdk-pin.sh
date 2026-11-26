#!/usr/bin/env bash
#
# Compare the Temurin JDK pinned in a Dockerfile with the latest Temurin 17 release.
#
# Usage: check-jdk-pin.sh <Dockerfile> [api-json-file]
#
# The latest release is read from the Adoptium API, or from api-json-file when given (used by the
# tests). Exit codes:
#   0  the pin equals or is ahead of the latest release
#   1  the pin is behind the latest release
#   2  JDK_VER and TEMURIN_RELEASE in the Dockerfile do not describe the same build
#   3  the latest release could not be determined

set -u

ADOPTIUM_URL="https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&os=linux&image_type=jdk"

dockerfile="${1:-}"
api_file="${2:-}"

if [ -z "$dockerfile" ] || [ ! -r "$dockerfile" ]; then
  echo "cannot read the Dockerfile: '$dockerfile'" >&2
  exit 3
fi

jdk_ver=$(sed -n 's/^ARG JDK_VER=\(.*\)$/\1/p' "$dockerfile" | head -n 1)
release=$(sed -n 's/^ARG TEMURIN_RELEASE=\(.*\)$/\1/p' "$dockerfile" | head -n 1)

if [ -z "$jdk_ver" ] || [ -z "$release" ]; then
  echo "JDK_VER or TEMURIN_RELEASE is missing in $dockerfile" >&2
  exit 3
fi

# 17.0.21.1_2 (download file name) and jdk-17.0.21.1+2 (release tag) must be the same build.
pinned="${jdk_ver/_/+}"
if [ "$pinned" != "${release#jdk-}" ]; then
  echo "mismatch: JDK_VER=$jdk_ver and TEMURIN_RELEASE=$release describe different builds" >&2
  exit 2
fi

if [ -n "$api_file" ]; then
  if [ ! -r "$api_file" ]; then
    echo "cannot determine the latest version: cannot read $api_file" >&2
    exit 3
  fi
  body=$(cat "$api_file")
else
  if ! body=$(curl -fsS --max-time 30 "$ADOPTIUM_URL"); then
    echo "cannot determine the latest version: the Adoptium API request failed" >&2
    exit 3
  fi
fi

latest=$(printf '%s' "$body" | grep -o '"release_name"[[:space:]]*:[[:space:]]*"jdk-[^"]*"' | head -n 1 |
  sed 's/.*"jdk-\([^"]*\)"$/\1/')

if [ -z "$latest" ]; then
  echo "cannot determine the latest version: no release_name in the Adoptium response" >&2
  exit 3
fi

echo "pinned: $pinned"
echo "latest: $latest"

# Version tuple: the dotted part padded to four components, then the build number.
# 17.0.21+9 -> 17 0 21 0 9; 17.0.21.1+2 -> 17 0 21 1 2. A string compare orders these wrongly.
tuple() {
  local version="${1%%+*}" build="${1#*+}"
  [ "$build" = "$1" ] && build=0
  local -a parts
  IFS=. read -r -a parts <<<"$version"
  while [ "${#parts[@]}" -lt 4 ]; do parts+=(0); done
  echo "${parts[@]} $build"
}

read -r -a pinned_t <<<"$(tuple "$pinned")"
read -r -a latest_t <<<"$(tuple "$latest")"

for i in "${!latest_t[@]}"; do
  p="${pinned_t[$i]:-0}"
  l="${latest_t[$i]}"
  if [ "$l" -gt "$p" ]; then
    echo "the pinned JDK is behind the latest Temurin 17 release"
    exit 1
  elif [ "$l" -lt "$p" ]; then
    break
  fi
done

echo "the pinned JDK is up to date"
exit 0
