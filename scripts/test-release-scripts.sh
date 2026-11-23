#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
CHANGELOG_PATH="$TEMP_DIR/CHANGELOG.md"

cat > "$CHANGELOG_PATH" <<'CHANGELOG'
# Changelog

## [Unreleased]

- Future work.

## [1.2.3]

- Stable release note.

## [1.3.0-rc.1]

- Prerelease note.
CHANGELOG

export CHANGELOG_PATH

bash "$SCRIPT_DIR/validate-release.sh" "v1.2.3"
bash "$SCRIPT_DIR/validate-release.sh" "v1.3.0-rc.1"

NOTES="$(bash "$SCRIPT_DIR/extract-release-notes.sh" "1.2.3")"
grep -Fq -- "- Stable release note." <<< "$NOTES"

NOTES="$(bash "$SCRIPT_DIR/extract-release-notes.sh" "1.3.0-rc.1")"
grep -Fq -- "- Prerelease note." <<< "$NOTES"

expect_failure() {
  if "$@" >/dev/null 2>&1; then
    echo "ERROR: Expected command to fail: $*" >&2
    exit 1
  fi
}

expect_failure bash "$SCRIPT_DIR/validate-release.sh" "v01.2.3"
expect_failure bash "$SCRIPT_DIR/validate-release.sh" "1.2.3"
expect_failure bash "$SCRIPT_DIR/validate-release.sh" "v9.9.9"

cat > "$TEMP_DIR/empty.md" <<'CHANGELOG'
# Changelog

## [2.0.0]

## [Unreleased]
CHANGELOG
expect_failure env CHANGELOG_PATH="$TEMP_DIR/empty.md" bash "$SCRIPT_DIR/validate-release.sh" "v2.0.0"

echo "Release script tests passed."
