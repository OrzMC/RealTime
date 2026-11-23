#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <agent-id> <task-id> [base-ref]" >&2
  exit 2
fi

AGENT_ID="$1"
TASK_ID="$2"
BASE_REF="${3:-main}"
for value in "$AGENT_ID" "$TASK_ID"; do
  [[ "$value" =~ ^[a-z0-9][a-z0-9-]*$ ]] || {
    echo "ERROR: agent-id and task-id must match [a-z0-9][a-z0-9-]*." >&2
    exit 2
  }
done

ROOT_DIR="$(git rev-parse --show-toplevel)"
PARENT_DIR="$(dirname "$ROOT_DIR")"
WORKTREE_DIR="$PARENT_DIR/RealTime-worktrees/$AGENT_ID-$TASK_ID"
BRANCH="agent/$AGENT_ID/$TASK_ID"

if [[ -e "$WORKTREE_DIR" ]]; then
  echo "ERROR: worktree path already exists: $WORKTREE_DIR" >&2
  exit 1
fi
if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/heads/$BRANCH"; then
  echo "ERROR: branch already exists: $BRANCH" >&2
  exit 1
fi

mkdir -p "$(dirname "$WORKTREE_DIR")"
git -C "$ROOT_DIR" worktree add -b "$BRANCH" "$WORKTREE_DIR" "$BASE_REF"
echo "Created isolated worktree: $WORKTREE_DIR"
echo "Branch: $BRANCH"
