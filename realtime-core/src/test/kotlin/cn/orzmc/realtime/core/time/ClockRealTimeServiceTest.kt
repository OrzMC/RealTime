package cn.orzmc.realtime.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class ClockRealTimeServiceTest {
    @Test
    fun `uses configured zone instead of clock zone or JVM default`() {
        val instant = Instant.parse("2026-11-23T02:00:00Z")
        val clock = Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))
        val service = ClockRealTimeService(clock, ZoneId.of("Asia/Shanghai"), Locale.CHINA)

        val now = service.now()

        assertEquals(instant, service.instant())
        assertEquals(LocalDate.parse("2026-11-23"), now.toLocalDate())
        assertEquals(LocalTime.parse("10:00:00"), now.toLocalTime())
        assertEquals(ZoneOffset.ofHours(8), now.offset)
        assertEquals(ZoneId.of("Asia/Shanghai"), now.zone)
    }

    @Test
    fun `snapshot captures a consistent instant and local representation`() {
        val instant = Instant.parse("2024-03-10T07:00:00Z")
        val service = service(instant, ZoneId.of("America/New_York"))

        val snapshot = service.snapshot()

        assertEquals(instant, snapshot.instant)
        assertEquals(instant, snapshot.zonedDateTime.toInstant())
        assertEquals(LocalDate.parse("2024-03-10"), snapshot.localDate)
        assertEquals(LocalTime.parse("03:00:00"), snapshot.localTime)
        assertEquals(ZoneOffset.ofHours(-4), snapshot.offset)
        assertEquals(instant.epochSecond, snapshot.epochSecond)
    }

    @Test
    fun `spring daylight saving transition skips the missing local hour`() {
        val zone = ZoneId.of("America/New_York")
        val before = service(Instant.parse("2024-03-10T06:59:59Z"), zone).now()
        val after = service(Instant.parse("2024-03-10T07:00:00Z"), zone).now()

        assertEquals(LocalTime.parse("01:59:59"), before.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-5), before.offset)
        assertEquals(LocalTime.parse("03:00:00"), after.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), after.offset)
    }

    @Test
    fun `autumn daylight saving overlap retains the correct offset for each instant`() {
        val zone = ZoneId.of("America/New_York")
        val firstOccurrence = service(Instant.parse("2024-11-03T05:30:00Z"), zone).now()
        val secondOccurrence = service(Instant.parse("2024-11-03T06:30:00Z"), zone).now()

        assertEquals(LocalTime.parse("01:30:00"), firstOccurrence.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-4), firstOccurrence.offset)
        assertEquals(LocalTime.parse("01:30:00"), secondOccurrence.toLocalTime())
        assertEquals(ZoneOffset.ofHours(-5), secondOccurrence.offset)
        assertNotEquals(firstOccurrence.offset, secondOccurrence.offset)
    }

    @Test
    fun `formatting uses the configured locale`() {
        val instant = Instant.parse("2024-03-10T07:00:00Z")
        val zone = ZoneId.of("America/New_York")
        val english = service(instant, zone, Locale.ENGLISH)
        val french = service(instant, zone, Locale.FRENCH)

        assertEquals("March", english.format("MMMM"))
        assertEquals("mars", french.format("MMMM"))
    }

    @Test
    fun `formatting a supplied snapshot preserves its own zone`() {
        val instant = Instant.parse("2026-11-23T02:00:00Z")
        val service = service(instant, ZoneId.of("Asia/Shanghai"))
        val utcSnapshot = RealTimeSnapshot(instant, instant.atZone(ZoneOffset.UTC))

        assertEquals("02:00 Z", service.format(utcSnapshot, "HH:mm XXX"))
    }

    @Test
    fun `invalid formatter pattern is reported as an argument error`() {
        val service = service(Instant.parse("2026-11-23T02:00:00Z"), ZoneId.of("UTC"))

        assertThrows(IllegalArgumentException::class.java) {
            service.format("yyyy-MM-dd[")
        }
    }

    @Test
    fun `invalid zone identifiers are rejected by the Java time boundary`() {
        assertThrows(DateTimeException::class.java) {
            ZoneId.of("Not/A_Real_Zone")
        }
    }

    @Test
    fun `snapshot rejects mismatched instant and zoned date time`() {
        val instant = Instant.parse("2026-11-23T02:00:00Z")
        val differentInstant = Instant.parse("2026-11-23T03:00:00Z")

        assertThrows(IllegalArgumentException::class.java) {
            RealTimeSnapshot(instant, differentInstant.atZone(ZoneOffset.UTC))
        }
    }

    private fun service(
        instant: Instant,
        zoneId: ZoneId,
        locale: Locale = Locale.ROOT,
    ) = ClockRealTimeService(
        clock = Clock.fixed(instant, ZoneOffset.UTC),
        zoneId = zoneId,
        locale = locale,
    )
}
