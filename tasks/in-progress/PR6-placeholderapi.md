# PR6 — Optional PlaceholderAPI Integration

- Status: In Progress
- Priority: P1
- Depends on: PR2, PR3

## Scope

- Add an internal PlaceholderExpansion with identifier `realtime`.
- Keep PlaceholderAPI optional through `softdepend` and compileOnly/test-only API dependencies.
- Isolate all PlaceholderAPI class references behind a bridge loaded only when PlaceholderAPI is installed.
- Unregister the expansion when RealTime disables; return `persist() = true` so PlaceholderAPI reloads do not discard the internal expansion.
- Expose configured local time/date, timezone/offset, weekday, epoch timestamps, BCD HHmm, redstone module state, and enabled device count.
- Keep `realtime-core` free of PlaceholderAPI dependencies.

## Acceptance

- RealTime starts and works when PlaceholderAPI is absent (Paper and Folia smoke).
- With PlaceholderAPI 2.12.3 installed, the internal expansion registers on Paper and Folia.
- Console parsing with `--null` resolves `%realtime_time%` and `%realtime_bcd%`.
- Placeholder values use the configured timezone and locale.
- Unknown placeholders return null and parameter names are case-insensitive.
- The PlaceholderAPI API is not bundled into the shaded RealTime JAR.
- Expansion unregisters on plugin disable and reads the current runtime state after `/rtime reload`.
- Unit tests cover placeholder mapping and null-player behavior.