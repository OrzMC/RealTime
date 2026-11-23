package cn.orzmc.realtime.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Immutable view of one instant in an explicitly selected time zone.
 *
 * The instant and zoned date-time are captured together by [RealTimeService.snapshot].
 * The constructor enforces that both values describe the same instant.
 */
data class RealTimeSnapshot(
    val instant: Instant,
    val zonedDateTime: ZonedDateTime,
) {
    init {
        require(zonedDateTime.toInstant() == instant) {
            "zonedDateTime must represent the supplied instant"
        }
    }

    val zoneId: ZoneId
        get() = zonedDateTime.zone

    val localDate: LocalDate
        get() = zonedDateTime.toLocalDate()

    val localTime: LocalTime
        get() = zonedDateTime.toLocalTime()

    val offset: ZoneOffset
        get() = zonedDateTime.offset

    val epochSecond: Long
        get() = instant.epochSecond
}
