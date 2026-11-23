# PR3 — Command and Modular Configuration

- Status: Complete
- Priority: P0
- Depends on: PR2

Implement `/rtime now`, `info`, and `reload`; permissions; localized messages; global timezone/locale config; separate module configs; validation, migration, and safe reload semantics.

Acceptance: invalid configuration is reported clearly; reload does not leave stale tasks; command tests cover permissions and invalid arguments.


## Verification

Merged in RealTime PR #3. Paper/Folia runtime smoke verifies `/rtime`, `/rtime info`, and `/rtime reload`; configuration validation, migration backup, command permissions, and safe runtime-state replacement are covered by tests.
