#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
"${GRADLE[@]}" --no-daemon clean check spotlessCheck build
bash "$ROOT_DIR/scripts/test-release-scripts.sh"
bash "$ROOT_DIR/scripts/validate-release.sh" "v0.1.0"
bash "$ROOT_DIR/scripts/extract-release-notes.sh" "0.1.0" >/dev/null
