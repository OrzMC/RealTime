package cn.orzmc.realtime.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Platform-independent access to real-world time.
 *
 * Implementations must use an injected [java.time.Clock] and an explicit [zoneId].
 * They must not consult the JVM default time zone or locale.
 */
interface RealTimeService {
    /** The time zone used when converting instants returned by this service. */
    val zoneId: ZoneId

    /** The locale used by this service's formatting methods. */
    val locale: Locale

    /** Returns the current absolute instant from the injected clock. */
    fun instant(): Instant

    /** Returns the current instant converted to [zoneId]. */
    fun now(): ZonedDateTime

    /** Captures one instant and its local representation atomically. */
    fun snapshot(): RealTimeSnapshot

    /**
     * Formats a fresh snapshot using [pattern] and this service's [locale].
     *
     * [pattern] follows the pattern syntax of [java.time.format.DateTimeFormatter].
     * For calendar years, prefer `uuuu`; `yyyy` is year-of-era. Invalid patterns
     * propagate as [IllegalArgumentException].
     */
    fun format(pattern: String): String

    /**
     * Formats [snapshot] using [pattern] and this service's [locale].
     *
     * The snapshot's own time zone is preserved; it is not converted to this
     * service's [zoneId].
     */
    fun format(
        snapshot: RealTimeSnapshot,
        pattern: String,
    ): String
}
