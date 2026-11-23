# PR2 — Clock-backed RealTime Core

- Status: Ready
- Priority: P0
- Depends on: PR1 merged

## Objective

Implement a platform-independent, timezone-aware real-world time service.

## Scope

- Define `RealTimeService` and immutable `RealTimeSnapshot`.
- Inject `java.time.Clock`.
- Support explicit `ZoneId` and locale.
- Provide documented formatting behavior.
- Add deterministic tests for timezone conversion, daylight-saving transitions, and fixed instants.

## Constraints

- Pure Kotlin/JVM only; no Bukkit, Paper, Folia, PlaceholderAPI, or server dependencies.
- Never rely on JVM default timezone.
- No sleep-based or wall-clock-dependent tests.

## Acceptance criteria

- Clear invalid-timezone/error behavior at adapter boundary.
- Fixed-clock tests are deterministic.
- Unit tests and formatting checks pass.
- Architecture docs are updated.

## Deliverables

Core source, tests, API docs, and focused PR.
