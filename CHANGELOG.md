# Changelog

All notable changes to RealTime are documented here. Release automation requires a non-empty section matching the exact version tag.

## [Unreleased]

- No unreleased changes.

## [0.1.0]

- Establish the independent Kotlin/JDK 25 Gradle project and Agent Native development workflow.
- Add the Clock-backed timezone-aware core service and deterministic time tests.
- Add the `/rtime` command, modular configuration, validation, and safe reload behavior.
- Add Paper/Folia scheduler adapters with explicit global, region, and entity task ownership and plugin-disable cancellation.
- Add the HHmm/HHmmss BCD encoder, validated redstone device model with `minute` (16 outputs) and `second` (24 outputs) resolution, schema-versioned YAML persistence, and region-safe redstone output.
- Add optional PlaceholderAPI integration with timezone-aware date/time, epoch, BCD, and device placeholders.
- Add device list/info/enable/disable/status commands and aggregate output diagnostics.
- Add schema-v2 deferred cleanup jobs and crash-safe retirement persistence; active-device removal and all cleanup coordinates are atomically persisted.
- Add Paper/Folia region-owned cleanup with startup/world/chunk-load retry, no forced chunk loading, redstone-block removal, and durable `NEEDS_ATTENTION` states for slots that hold anything else.
- Add cleanup queue inspection and admin retry commands, plus admin-only disabled-device retirement through `/rtime device remove <uuid>`.
- Add second-resolution devices: 24 BCD outputs (HHmmss) refreshed every second, schema v3 with `resolution: minute|second`, and automatic migration of earlier device stores.
- Output slots are now real power sources (`REDSTONE_BLOCK` / air) instead of forced `REDSTONE_WIRE` power, so connected redstone actually switches; the pre-release `output-power` key is ignored and `device enable|disable` keeps the device resolution.
- Verify restart recovery, unloaded-chunk deferral, chunk-load retry, non-wire preservation, operator retry, and full retirement on both Paper and Folia.
- Add protected tag-based release automation, reproducible shaded JAR packaging, and SHA-256 checksums.
