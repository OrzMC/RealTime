#!/usr/bin/env bash
set -euo pipefail

PLUGIN_DIR="${1:?Usage: download-placeholderapi.sh <plugins-directory>}"
VERSION_ID="${PLACEHOLDERAPI_VERSION_ID:-pIvQcXW8}"
METADATA="$(mktemp)"
trap 'rm -f "$METADATA"' EXIT

mkdir -p "$PLUGIN_DIR"
curl --fail --location --silent --show-error \
  -H 'User-Agent: OrzMC-RealTime-CI/1.0 (https://github.com/OrzMC/RealTime)' \
  "https://api.modrinth.com/v2/version/$VERSION_ID" -o "$METADATA"

DOWNLOAD_URL="$(python3 - "$METADATA" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as metadata_file:
    metadata = json.load(metadata_file)

files = metadata.get("files", [])
selected = next((item for item in files if item.get("primary")), files[0] if files else None)
if selected is None or not selected.get("url"):
    raise SystemExit("PlaceholderAPI version metadata has no downloadable file")
print(selected["url"])
PY
)"

curl --fail --location --silent --show-error \
  -H 'User-Agent: OrzMC-RealTime-CI/1.0 (https://github.com/OrzMC/RealTime)' \
  "$DOWNLOAD_URL" -o "$PLUGIN_DIR/PlaceholderAPI.jar"
jar tf "$PLUGIN_DIR/PlaceholderAPI.jar" | grep -Fxq 'plugin.yml'
echo "Installed PlaceholderAPI version $VERSION_ID for runtime smoke testing."
