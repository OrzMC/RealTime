package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class DeviceRetirementServiceTest {
    @Test
    fun `persists the cleanup intent before publishing device retirement`() {
        val device = device(enabled = false)
        val initial = state(listOf(device))
        var active = initial
        var persisted: DeviceStoreSnapshot? = null
        val events = mutableListOf<String>()
        val service =
            DeviceRetirementService(
                stateLock = Any(),
                stateProvider = { active },
                persistStore = {
                    persisted = it
                    events += "persist"
                },
                replaceState = {
                    active = it
                    events += "publish"
                },
                requestCleanup = { events += "refresh" },
            )

        val job = service.retire(device.id, UUID.randomUUID())

        assertTrue(active.devices.isEmpty())
        assertEquals(1, active.cleanupJobs.size)
        assertEquals(job.id, active.cleanupJobs.single().id)
        assertEquals(device.id, persisted?.cleanupJobs?.single()?.deviceId)
        assertTrue(persisted?.devices?.isEmpty() == true)
        assertEquals(listOf("persist", "publish", "refresh"), events)
    }

    @Test
    fun `refuses to retire an enabled device`() {
        val device = device(enabled = true)
        val initial = state(listOf(device))
        var active = initial
        var persistCount = 0
        val service =
            DeviceRetirementService(
                stateLock = Any(),
                stateProvider = { active },
                persistStore = { persistCount += 1 },
                replaceState = { active = it },
                requestCleanup = {},
            )

        assertThrows(DeviceMustBeDisabledException::class.java) {
            service.retire(device.id, null)
        }

        assertEquals(initial.devices.single().id, active.devices.single().id)
        assertEquals(0, persistCount)
    }

    @Test
    fun `failed store persistence leaves the device active`() {
        val device = device(enabled = false)
        val initial = state(listOf(device))
        var active = initial
        var refreshCount = 0
        val service =
            DeviceRetirementService(
                stateLock = Any(),
                stateProvider = { active },
                persistStore = { throw IllegalStateException("disk full") },
                replaceState = { active = it },
                requestCleanup = { refreshCount += 1 },
            )

        assertThrows(IllegalStateException::class.java) {
            service.retire(device.id, null)
        }

        assertFalse(active.devices.isEmpty())
        assertTrue(active.cleanupJobs.isEmpty())
        assertEquals(0, refreshCount)
    }

    private fun state(devices: List<RedstoneDevice>): RuntimeState {
        val configuration =
            RealTimeConfiguration(
                generalEnabled = true,
                zoneId = ZoneId.of("Asia/Shanghai"),
                locale = Locale.forLanguageTag("zh-CN"),
                debugLogging = false,
                commandEnabled = true,
                commandFormat = "uuuu-MM-dd HH:mm:ss XXX",
                messages = emptyMap(),
            )
        return RuntimeState(
            configuration = configuration,
            timeService =
                ClockRealTimeService(
                    clock = Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC),
                    zoneId = configuration.zoneId,
                    locale = configuration.locale,
                ),
            messages = MessageCatalog(emptyMap()),
            devices = devices,
        )
    }

    private fun device(enabled: Boolean) =
        RedstoneDevice(
            id = UUID.randomUUID(),
            name = "clock",
            worldName = "world",
            enabled = enabled,
            outputs =
                (0 until 16).map { bit ->
                    DeviceOutput(bitIndex = bit, x = bit * 2, y = 64, z = 0)
                },
        )
}
