#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"

VERSION="$("${GRADLE[@]}" -q properties | awk -F': ' '/^version:/ {print $2; exit}')"
ARTIFACT="$ROOT_DIR/realtime-paper/build/libs/RealTime-${VERSION}.jar"

if [[ ! -f "$ARTIFACT" ]]; then
  echo "ERROR: Shaded plugin JAR not found: $ARTIFACT" >&2
  exit 1
fi

CONTENTS="$(mktemp)"
trap 'rm -f "$CONTENTS"' EXIT
jar tf "$ARTIFACT" > "$CONTENTS"

require_entry() {
  if ! grep -Fxq "$1" "$CONTENTS"; then
    echo "ERROR: Required JAR entry missing: $1" >&2
    exit 1
  fi
}

require_entry "plugin.yml"
require_entry "cn/orzmc/realtime/paper/RealTimePlugin.class"

if ! grep -Eq '^cn/orzmc/realtime/core/.+\.class$' "$CONTENTS"; then
  echo "ERROR: realtime-core classes are missing from the shaded JAR." >&2
  exit 1
fi

if grep -Eq '^(org/bukkit|io/papermc)/' "$CONTENTS"; then
  echo "ERROR: Paper API classes must not be bundled; they are provided by the server." >&2
  exit 1
fi

PLUGIN_DESCRIPTOR="$(unzip -p "$ARTIFACT" plugin.yml)"
grep -Fq "main: cn.orzmc.realtime.paper.RealTimePlugin" <<< "$PLUGIN_DESCRIPTOR"
grep -Fq "api-version: '26.3'" <<< "$PLUGIN_DESCRIPTOR"
grep -Fq "folia-supported: true" <<< "$PLUGIN_DESCRIPTOR"

echo "Shaded JAR inspection passed: $ARTIFACT"
echo "Verified plugin descriptor, entry point, core classes, and server-provided API boundary."
