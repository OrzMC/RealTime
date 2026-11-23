#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
if [[ -x "$ROOT_DIR/gradlew" ]]; then
  GRADLE=("$ROOT_DIR/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=("$(command -v gradle)")
else
  echo "ERROR: Gradle Wrapper is missing and 'gradle' is not installed." >&2
  exit 127
fi
