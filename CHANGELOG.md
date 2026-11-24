# Changelog

All notable changes to RealTime are documented here. Release automation requires a non-empty section matching the exact version tag.

## [Unreleased]

- No unreleased changes.

## [0.1.0]

- Establish the independent Kotlin/JDK 25 Gradle project and Agent Native development workflow.
- Add the Clock-backed timezone-aware core service and deterministic time tests.
- Add the `/rtime` command, modular configuration, validation, and safe reload behavior.
- Add Paper/Folia scheduler adapters with explicit global, region, and entity task ownership and plugin-disable cancellation.
- Add the 16-bit HHmm BCD encoder, validated redstone device model, schema-versioned YAML persistence, and region-safe redstone output.
- Add optional PlaceholderAPI integration with timezone-aware date/time, epoch, BCD, and device placeholders.
- Add device list/info/enable/disable/status commands and aggregate output diagnostics.
- Add schema-v2 deferred cleanup jobs and crash-safe retirement persistence; active-device removal and all 16 cleanup coordinates are atomically persisted.
- Add Paper/Folia region-owned cleanup with startup/world/chunk-load retry, no forced chunk loading, REDSTONE_WIRE-only power-zero cleanup, and durable `NEEDS_ATTENTION` states for non-wire targets.
- Add cleanup queue inspection and admin retry commands, plus admin-only disabled-device retirement through `/rtime device remove <uuid>`.
- Verify restart recovery, unloaded-chunk deferral, chunk-load retry, non-wire preservation, operator retry, and full retirement on both Paper and Folia.
- Add protected tag-based release automation, reproducible shaded JAR packaging, and SHA-256 checksums.
