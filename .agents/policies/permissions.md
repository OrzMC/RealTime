# Agent Permissions Policy

## Allowed by default

- Read repository files and public docs.
- Modify project source, tests, and docs in isolated worktrees.
- Run local build, test, lint, and static analysis.
- Create local branches and commits.

## Requires explicit authorization

- Push remote branches when the active workflow has not granted permission.
- Create/modify GitHub issues and PRs when not part of an approved task.
- Change CI permissions, branch protection, repository settings, or secrets.
- Access external services beyond approved test environments.

## Requires human approval

- Merge PRs or publish releases.
- Delete remote branches, tags, releases, data, or repositories.
- Expose credentials or run destructive operations against production servers/worlds.
