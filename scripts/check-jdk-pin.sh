#!/usr/bin/env bash
# Fails when the JDK pinned in the Dockerfile is not the latest GA Temurin 17 release.
#
# Usage: scripts/check-jdk-pin.sh <pinned-release> [latest-release]
#   pinned-release  the Dockerfile's TEMURIN_RELEASE, e.g. jdk-17.0.21.1+2
#   latest-release  skip the Adoptium lookup and compare against this value
#
# Exit codes: 0 pin is the latest, 1 pin is behind, 2 the latest release could not be read.
set -u

pinned="${1:?usage: check-jdk-pin.sh <pinned-release> [latest-release]}"
url="${ADOPTIUM_RELEASE_NAMES_URL:-https://api.adoptium.net/v3/info/release_names?architecture=x64&image_type=jdk&os=linux&release_type=ga&version=%5B17%2C18%29&page_size=1&sort_order=DESC&vendor=eclipse}"

if [ "$#" -ge 2 ]; then
  latest="$2"
else
  latest="$(curl -fsS --max-time 30 "$url" 2>/dev/null | jq -r '.releases[0] // empty' 2>/dev/null)"
fi

if [ -z "$latest" ]; then
  echo "::error::check-jdk-pin: could not read the latest release from Adoptium"
  echo "check-jdk-pin: could not read the latest release (pinned: $pinned)"
  exit 2
fi

if [ "$pinned" = "$latest" ]; then
  echo "check-jdk-pin: Dockerfile pins $pinned, the latest GA Temurin 17 release"
  exit 0
fi

echo "::error::check-jdk-pin: Dockerfile pins $pinned but the latest GA Temurin 17 release is $latest"
echo "check-jdk-pin: Dockerfile pins $pinned, latest is $latest; update TEMURIN_RELEASE in the Dockerfile"
exit 1
