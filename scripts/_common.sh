#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
if [[ -x "$ROOT_DIR/gradlew" ]]; then
  GRADLE=("$ROOT_DIR/gradlew")
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=("$(command -v gradle)")
else
  echo "ERROR: Gradle Wrapper is not present and 'gradle' is not installed." >&2
  echo "Install Gradle 9.2.1 or generate the official wrapper; see docs/development/setup.md." >&2
  exit 127
fi
