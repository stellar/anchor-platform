#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_FILE="$ROOT_DIR/build.gradle.kts"
README_FILE="$ROOT_DIR/README.md"
VERSION_INFO_FILE="$ROOT_DIR/service-runner/src/main/resources/version-info.properties"

usage() {
  echo "Usage: $0 [major|minor|patch|<version>]"
  echo "       VERSION=4.6.0 $0"
  echo "  Examples:"
  echo "    $0 4.6.0       # set exact version"
  echo "    $0 patch       # 4.5.0 → 4.5.1"
  echo "    $0 minor       # 4.5.0 → 4.6.0"
  echo "    $0 major       # 4.5.0 → 5.0.0"
  echo "    $0             # defaults to minor bump"
  exit 1
}

bump_version() {
  local current="$1" bump="$2"
  local major minor patch
  IFS='.' read -r major minor patch <<< "$current"
  case "$bump" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "${major}.$((minor + 1)).0" ;;
    patch) echo "${major}.${minor}.$((patch + 1))" ;;
  esac
}

CURRENT_VERSION=$(grep -E '^version=[0-9]+\.[0-9]+\.[0-9]+$' "$VERSION_INFO_FILE" | cut -d'=' -f2)

if [[ -z "$CURRENT_VERSION" ]]; then
  echo "Error: could not find version in $VERSION_INFO_FILE"
  exit 1
fi

# Resolve NEW_VERSION from argument, env var, or default patch bump
ARG="${1:-}"
if [[ -n "$ARG" ]] && [[ "$ARG" =~ ^(major|minor|patch)$ ]]; then
  NEW_VERSION=$(bump_version "$CURRENT_VERSION" "$ARG")
elif [[ -n "$ARG" ]]; then
  NEW_VERSION="$ARG"
elif [[ -n "${VERSION:-}" ]]; then
  NEW_VERSION="$VERSION"
else
  NEW_VERSION=$(bump_version "$CURRENT_VERSION" minor)
fi

# Validate semver format
if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Error: version must be in semver format (e.g. 4.6.0)"
  exit 1
fi

export CURRENT_VERSION

if [[ "$CURRENT_VERSION" == "$NEW_VERSION" ]]; then
  echo "Error: new version ($NEW_VERSION) is the same as the current version"
  exit 1
fi

echo "Bumping version: $CURRENT_VERSION → $NEW_VERSION"

# build.gradle.kts
sed -i.bak "s/version = \"$CURRENT_VERSION\"/version = \"$NEW_VERSION\"/" "$BUILD_FILE"
rm -f "$BUILD_FILE.bak"

# README.md (badge URL contains version twice)
sed -i.bak \
  -e "s|/v${CURRENT_VERSION}/|/v${NEW_VERSION}/|g" \
  -e "s|name=${CURRENT_VERSION}|name=${NEW_VERSION}|g" \
  "$README_FILE"
rm -f "$README_FILE.bak"

# version-info.properties
sed -i.bak "s/^version=${CURRENT_VERSION}$/version=${NEW_VERSION}/" "$VERSION_INFO_FILE"
rm -f "$VERSION_INFO_FILE.bak"

# Verify all three updated
errors=0
grep -q "version = \"$NEW_VERSION\"" "$BUILD_FILE"          || { echo "Error: build.gradle.kts not updated"; errors=$((errors+1)); }
grep -q "v${NEW_VERSION}" "$README_FILE"                    || { echo "Error: README.md not updated"; errors=$((errors+1)); }
grep -q "^version=${NEW_VERSION}$" "$VERSION_INFO_FILE"     || { echo "Error: version-info.properties not updated"; errors=$((errors+1)); }

if [[ $errors -gt 0 ]]; then
  echo "Version bump failed — check the files above manually"
  exit 1
fi

echo "Updated:"
echo "  $BUILD_FILE"
echo "  $README_FILE"
echo "  $VERSION_INFO_FILE"

echo "Done. $CURRENT_VERSION → $NEW_VERSION"
