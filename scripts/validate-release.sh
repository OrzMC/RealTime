#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TAG="${1:-${GITHUB_REF_NAME:-}}"
CHANGELOG_PATH="${CHANGELOG_PATH:-$ROOT_DIR/CHANGELOG.md}"

SEMVER_TAG_REGEX='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?$'

if [[ ! "$TAG" =~ $SEMVER_TAG_REGEX ]]; then
  echo "ERROR: Invalid release tag '$TAG'. Expected vMAJOR.MINOR.PATCH or a SemVer prerelease tag." >&2
  exit 1
fi

if [[ ! -f "$CHANGELOG_PATH" ]]; then
  echo "ERROR: Changelog not found: $CHANGELOG_PATH" >&2
  exit 1
fi

VERSION="${TAG#v}"
NOTES="$(CHANGELOG_PATH="$CHANGELOG_PATH" bash "$SCRIPT_DIR/extract-release-notes.sh" "$VERSION")"
if [[ -z "$(printf '%s' "$NOTES" | tr -d '[:space:]')" ]]; then
  echo "ERROR: Changelog section for $VERSION is empty." >&2
  exit 1
fi

echo "Release metadata valid: tag=$TAG version=$VERSION"
