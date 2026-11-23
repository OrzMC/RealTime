# Testing Strategy

1. Core unit tests: clock, timezone conversion, formatting, snapshots, and BCD.
2. Configuration tests: defaults, parsing, invalid values, and migration.
3. Command tests: permissions, arguments, output, and reload.
4. Platform tests: lifecycle and Paper/Folia scheduler behavior.
5. Integration tests: optional PlaceholderAPI and redstone devices.
6. Packaging tests: plugin metadata and shaded runtime dependencies.

Time-dependent tests inject fixed `java.time.Clock`; avoid sleeps and assertions against the host clock. A successful compile is not proof of Paper/Folia compatibility. Runtime evidence must record exact server builds and exercised features.

PR2 adds deterministic core tests for configured-zone conversion, DST spring/fall transitions, locale-aware formatting, invalid patterns, and snapshot invariants. Keep tests based on `Clock.fixed`; do not use sleeps or the host clock.
