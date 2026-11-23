package cn.orzmc.realtime.paper.placeholder

import cn.orzmc.realtime.core.device.BcdHhmmEncoder
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.device.RedstoneConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class RealTimePlaceholderExpansionTest {
    private val expansion = RealTimePlaceholderExpansion("1.2.3") { state() }

    @Test
    fun `exposes configured local date and time`() {
        assertEquals("2026-11-23 23:59:00 +08:00", expansion.onRequest(null, "time"))
        assertEquals("2026-11-23", expansion.onRequest(null, "date"))
        assertEquals("23:59:00", expansion.onRequest(null, "time_24h"))
        assertEquals("11:59:00 PM", expansion.onRequest(null, "time_12h"))
        assertEquals("23", expansion.onRequest(null, "hour"))
        assertEquals("59", expansion.onRequest(null, "minute"))
        assertEquals("00", expansion.onRequest(null, "second"))
        assertEquals("Monday", expansion.onRequest(null, "weekday"))
    }

    @Test
    fun `exposes timezone epoch and BCD values`() {
        val state = state()
        val expectedBits = BcdHhmmEncoder.encode(23, 59).bits.joinToString("") { if (it) "1" else "0" }

        assertEquals("Asia/Shanghai", expansion.onRequest(null, "timezone"))
        assertEquals("+08:00", expansion.onRequest(null, "offset"))
        assertEquals("2026-11-23T23:59:00+08:00", expansion.onRequest(null, "iso"))
        assertEquals(
            Instant.parse("2026-11-23T15:59:00Z").epochSecond.toString(),
            expansion.onRequest(null, "timestamp"),
        )
        assertEquals("2359", expansion.onRequest(null, "bcd"))
        assertEquals(expectedBits, expansion.onRequest(null, "bcd_bits"))
        assertEquals("true", expansion.onRequest(null, "redstone_enabled"))
        assertEquals("1", expansion.onRequest(null, "device_count"))
        assertEquals(state.timeService.zoneId.id, expansion.onRequest(null, "timezone"))
    }

    @Test
    fun `uses case insensitive params and returns null for unknown placeholders`() {
        assertEquals("23:59:00", expansion.onRequest(null, "TIME_24H"))
        assertNull(expansion.onRequest(null, "not_a_placeholder"))
    }

    @Test
    fun `internal expansion persists and exposes stable metadata`() {
        assertEquals("realtime", expansion.identifier)
        assertEquals("OrzMC", expansion.author)
        assertEquals("1.2.3", expansion.version)
        assertTrue(expansion.persist())
    }

    private fun state(): RuntimeState {
        val configuration =
            RealTimeConfiguration(
                generalEnabled = true,
                zoneId = ZoneId.of("Asia/Shanghai"),
                locale = Locale.US,
                debugLogging = false,
                commandEnabled = true,
                commandFormat = "uuuu-MM-dd HH:mm:ss XXX",
                messages = emptyMap(),
            )
        return RuntimeState(
            configuration = configuration,
            timeService =
                ClockRealTimeService(
                    clock = Clock.fixed(Instant.parse("2026-11-23T15:59:00Z"), ZoneOffset.UTC),
                    zoneId = configuration.zoneId,
                    locale = configuration.locale,
                ),
            messages = MessageCatalog(emptyMap()),
            redstoneConfiguration = RedstoneConfiguration(enabled = true, outputPower = 15),
            devices = listOf(testDevice()),
        )
    }

    private fun testDevice() =
        RedstoneDevice(
            id = UUID.randomUUID(),
            name = "test-clock",
            worldName = "world",
            enabled = true,
            outputs =
                (0 until 16).map { bit ->
                    DeviceOutput(bit, bit * 2, 64, 0)
                },
        )
}
