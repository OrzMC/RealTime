# PR6 — Optional PlaceholderAPI Integration

- Status: Backlog
- Priority: P1
- Depends on: PR2, PR3

Expose date, time, datetime, weekday, timezone, and Unix timestamp placeholders. Keep PlaceholderAPI optional and isolated from realtime-core.

Acceptance: RealTime loads without PlaceholderAPI; output respects configured timezone; integration tests cover absence and presence.
