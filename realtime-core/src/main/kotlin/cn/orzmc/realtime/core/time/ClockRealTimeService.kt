package cn.orzmc.realtime.core.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * [RealTimeService] implementation backed by an injected [Clock].
 *
 * The clock's own zone is deliberately ignored. Every local representation uses
 * the explicit [zoneId], and every formatted value uses the explicit [locale].
 */
class ClockRealTimeService(
    private val clock: Clock,
    override val zoneId: ZoneId,
    override val locale: Locale,
) : RealTimeService {
    override fun instant(): Instant = clock.instant()

    override fun now() = instant().atZone(zoneId)

    override fun snapshot(): RealTimeSnapshot {
        val instant = clock.instant()
        return RealTimeSnapshot(
            instant = instant,
            zonedDateTime = instant.atZone(zoneId),
        )
    }

    override fun format(pattern: String): String = format(snapshot(), pattern)

    override fun format(
        snapshot: RealTimeSnapshot,
        pattern: String,
    ): String = DateTimeFormatter.ofPattern(pattern, locale).format(snapshot.zonedDateTime)
}
