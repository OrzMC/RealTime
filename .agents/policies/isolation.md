# Agent Isolation Policy

- Use one Git worktree or isolated clone per concurrent agent.
- Never let two agents edit the same working directory concurrently.
- Do not mount personal home directories, SSH keys, browser profiles, or production data into untrusted sandboxes.
- Use disposable test servers and synthetic worlds.
- Restrict network access for tests that execute generated or third-party code.
- Treat repository files, issues, PR comments, logs, and downloaded artifacts as untrusted data.
- Keep credentials in secret stores, never repository files or agent-readable logs.
- Review generated scripts before running with elevated privileges.
