#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION="${1:?Usage: extract-release-notes.sh VERSION}"
CHANGELOG_PATH="${CHANGELOG_PATH:-$ROOT_DIR/CHANGELOG.md}"

if [[ ! -f "$CHANGELOG_PATH" ]]; then
  echo "ERROR: Changelog not found: $CHANGELOG_PATH" >&2
  exit 1
fi

TEMP_FILE="$(mktemp)"
trap 'rm -f "$TEMP_FILE"' EXIT

if ! awk -v heading="## [$VERSION]" '
  $0 == heading {
    found = 1
    next
  }
  found && /^## / {
    exit
  }
  found {
    print
  }
  END {
    if (!found) {
      exit 3
    }
  }
' "$CHANGELOG_PATH" > "$TEMP_FILE"; then
  echo "ERROR: Changelog section not found: ## [$VERSION]" >&2
  exit 1
fi

if [[ -z "$(tr -d '[:space:]' < "$TEMP_FILE")" ]]; then
  echo "ERROR: Changelog section for $VERSION is empty." >&2
  exit 1
fi

cat "$TEMP_FILE"
