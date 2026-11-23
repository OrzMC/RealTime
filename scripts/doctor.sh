#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
command -v java >/dev/null 2>&1 || { echo "ERROR: Java is not installed." >&2; exit 1; }
java -version
JAVA_VERSION="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/ {print $2; exit}')"
[[ "$JAVA_VERSION" == "25" ]] || { echo "ERROR: JDK 25 required; detected ${JAVA_VERSION:-unknown}." >&2; exit 1; }
if [[ -x "$ROOT_DIR/gradlew" ]]; then "$ROOT_DIR/gradlew" --version
elif command -v gradle >/dev/null 2>&1; then gradle --version
else echo "ERROR: Gradle Wrapper and system Gradle are both missing." >&2; exit 1; fi
echo "Environment check passed."
