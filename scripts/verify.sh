#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
"${GRADLE[@]}" --no-daemon clean check spotlessCheck build
bash "$ROOT_DIR/scripts/test-release-scripts.sh"
