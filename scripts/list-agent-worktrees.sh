#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(git rev-parse --show-toplevel)"
git -C "$ROOT_DIR" worktree list --porcelain
