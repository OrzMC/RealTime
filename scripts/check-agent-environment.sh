#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
required=(AGENTS.md README.md tasks/roadmap.md docs/architecture/overview.md scripts/verify.sh)
for file in "${required[@]}"; do
  [[ -f "$file" ]] || { echo "ERROR: missing required agent file: $file" >&2; exit 1; }
done
grep -q "realtime-core" AGENTS.md
grep -q "realtime-paper" AGENTS.md
echo "Agent bootstrap files are present."
