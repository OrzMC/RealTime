# PlaceholderAPI Integration

## Installation and compatibility

PlaceholderAPI is optional. Install PlaceholderAPI 2.12.3 on the server; the RealTime plugin declares it as a soft dependency and continues to work when it is absent. The 2.12.3 Modrinth release lists Minecraft 26.3 and both Paper/Folia platforms: https://modrinth.com/plugin/placeholderapi/version/pIvQcXW8.

RealTime uses an internal expansion with identifier `realtime`. No separate RealTime expansion JAR is required. The PlaceholderAPI API is compile-only/test-only and is not bundled into the shaded RealTime JAR.

## Available placeholders

| Placeholder | Description |
|---|---|
| `%realtime_time%` | Current local date/time using the configured command format |
| `%realtime_datetime%` | Alias of `time` |
| `%realtime_date%` | Local date (`uuuu-MM-dd`) |
| `%realtime_time_24h%` | 24-hour time (`HH:mm:ss`) |
| `%realtime_time_12h%` | 12-hour time (`hh:mm:ss a`) |
| `%realtime_hour%` | Hour (`00`–`23`) |
| `%realtime_minute%` | Minute (`00`–`59`) |
| `%realtime_second%` | Second (`00`–`59`) |
| `%realtime_day%` | Day of month |
| `%realtime_month%` | Month number |
| `%realtime_year%` | Calendar year |
| `%realtime_weekday%` | Localized full weekday |
| `%realtime_weekday_short%` | Localized abbreviated weekday |
| `%realtime_timezone%` | Configured zone ID, e.g. `Asia/Shanghai` |
| `%realtime_offset%` | Current UTC offset |
| `%realtime_iso%` | ISO-8601 offset date-time |
| `%realtime_timestamp%` | Unix timestamp in seconds |
| `%realtime_timestamp_millis%` | Unix timestamp in milliseconds |
| `%realtime_bcd%` | Six HHmmss decimal digits |
| `%realtime_bcd_bits%` | Twenty-four BCD bits, digit groups HHmmss, LSB-first |
| `%realtime_redstone_enabled%` | Whether the redstone module is enabled |
| `%realtime_device_count%` | Number of enabled redstone devices |

Placeholder names are case-insensitive. These placeholders are server-time values and do not use the requesting player's locale, world, or personal data.

## Validation

From the server console, parse placeholders with a null player:

```text
/papi parse --null %realtime_time%
/papi parse --null %realtime_bcd%
/papi parse --null %realtime_timezone%
```

PlaceholderAPI documents `--null` as the console-safe way to parse player-independent placeholders: https://wiki.placeholderapi.com/users/commands/ .

## Lifecycle and reload

- RealTime registers the internal expansion only when PlaceholderAPI is enabled.
- The expansion returns `persist() = true` so a PlaceholderAPI reload does not discard the internal expansion.
- RealTime explicitly unregisters the expansion when its own plugin is disabled.
- The expansion reads the current immutable runtime state on each request, so `/rtime reload` updates placeholder values without re-registering it.
- The bridge isolates PlaceholderAPI class references; the main plugin can load without PlaceholderAPI installed.

Developer reference: [Creating a PlaceholderExpansion](https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/).