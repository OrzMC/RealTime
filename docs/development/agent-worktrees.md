# Isolated Agent Worktrees

Concurrent coding agents must not share a working directory. Use one Git worktree per agent/task pair.

## Create

From any directory inside the RealTime checkout:

```bash
bash scripts/new-agent-worktree.sh pi realtime-core
./scripts/new-agent-worktree.sh codex realtime-core
./scripts/new-agent-worktree.sh claude command-config
```

The helper creates sibling directories under `../RealTime-worktrees/` and branches named `agent/<agent-id>/<task-id>`. IDs are restricted to lowercase letters, digits, and hyphens. It refuses to overwrite an existing path or branch.

An optional third argument selects the base ref:

```bash
./scripts/new-agent-worktree.sh pi realtime-core origin/main
```

## Inspect

```bash
bash scripts/list-agent-worktrees.sh
```

## Rules

- Never have two agents edit the same worktree.
- Each agent commits only its own branch.
- Integrate work through PRs; do not cherry-pick unreviewed changes into main.
- Do not automatically remove worktrees containing uncommitted changes.
- Keep credentials and production data outside agent worktrees.
