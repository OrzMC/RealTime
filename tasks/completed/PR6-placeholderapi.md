# PR6 — Optional PlaceholderAPI Integration

- Status: Complete
- Priority: P1
- Depends on: PR2, PR3
- Merged: PR #6 — https://github.com/OrzMC/RealTime/pull/6
- Merge commit: `ba3d52a206825db75f555ea41a960c12fe57787d`

## Delivered

- Internal PlaceholderExpansion with identifier `realtime`.
- Optional PlaceholderAPI dependency through `softdepend` and compileOnly/test-only API dependencies.
- Isolated PlaceholderAPI class references behind a bridge loaded only when PlaceholderAPI is installed.
- Expansion unregisters when RealTime disables and returns `persist() = true`.
- Exposes configured local time/date, timezone/offset, weekday, epoch timestamps, BCD HHmm, redstone module state, and enabled device count.
- Keeps `realtime-core` free of PlaceholderAPI dependencies.

## Verified acceptance

- RealTime starts and works when PlaceholderAPI is absent on Paper and Folia.
- PlaceholderAPI 2.12.3 registers on Paper and Folia.
- Console parsing with `--null` resolves `%realtime_time%` and `%realtime_bcd%`.
- Values use the configured timezone and locale.
- Unknown placeholders return null and parameter names are case-insensitive.
- PlaceholderAPI API is not bundled into the shaded JAR.
- Unit tests cover placeholder mapping and null-player behavior.
- Paper/Folia runtime smoke and Gradle CI passed in PR #6.
