# realtime-core

Platform-independent Kotlin/JVM domain and time logic. This module must not depend on Bukkit, Paper, Folia, PlaceholderAPI, or server runtime classes.

## Time service

- `RealTimeService`: service contract for absolute instants, zoned local time, snapshots, and formatting.
- `ClockRealTimeService`: implementation backed by an injected `java.time.Clock`, explicit `ZoneId`, and explicit `Locale`.
- `RealTimeSnapshot`: immutable instant + `ZonedDateTime` view with consistency validation and derived date/time/offset fields.

Formatting follows `DateTimeFormatter` pattern syntax and uses the service locale. A supplied snapshot retains its own zone. Tests use `Clock.fixed` and cover configured-zone conversion, DST gaps/overlaps, locale formatting, invalid patterns, and snapshot invariants.
