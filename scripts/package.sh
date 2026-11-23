#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"
"${GRADLE[@]}" --no-daemon :realtime-paper:shadowJar
VERSION="$("${GRADLE[@]}" -q properties | awk -F': ' '/^version:/ {print $2; exit}')"
ARTIFACT="$ROOT_DIR/realtime-paper/build/libs/RealTime-${VERSION}.jar"
if [[ ! -f "$ARTIFACT" ]]; then
  echo "ERROR: Expected shaded artifact not found: $ARTIFACT" >&2
  echo "Inspect realtime-paper/build/libs for the generated artifact." >&2
  exit 1
fi
echo "Package created: $ARTIFACT"
