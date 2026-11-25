#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/_common.sh"

# CRLF guard: .gitattributes forces LF in the working tree, so any tracked text
# file with a CR means something wrote it with Windows line endings and the next
# diff would be whole-file noise.
crlf_files="$(git grep -Il -e $'\r' -- . ':!*.bat' ':!*.cmd' ':!*.ps1' 2>/dev/null || true)"
if [[ -n "$crlf_files" ]]; then
  echo "ERROR: CRLF line endings found in tracked text files:" >&2
  echo "$crlf_files" >&2
  echo "Fix with: git add --renormalize . && git checkout -- ." >&2
  exit 1
fi

"${GRADLE[@]}" --no-daemon clean check spotlessCheck build
bash "$ROOT_DIR/scripts/test-release-scripts.sh"
bash "$ROOT_DIR/scripts/validate-release.sh" "v0.1.0"
bash "$ROOT_DIR/scripts/extract-release-notes.sh" "0.1.0" >/dev/null
bash "$ROOT_DIR/scripts/distribution-check.sh" >/dev/null
