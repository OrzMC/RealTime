package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class DeviceCleanupAdministrationServiceTest {
    @Test
    fun `retry persists pending transition before publishing and refreshing`() {
        val job = jobWithAttentionOutput()
        val initial = state(job)
        var active = initial
        var persisted: DeviceStoreSnapshot? = null
        val events = mutableListOf<String>()
        val service =
            DeviceCleanupAdministrationService(
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

        val retried = service.retry(job.id)

        assertEquals(DeviceCleanupStatus.PENDING, retried.outputs.first().status)
        assertEquals(DeviceCleanupStatus.CLEARED, retried.outputs[1].status)
        assertEquals(DeviceCleanupStatus.PENDING, persisted?.cleanupJobs?.single()?.outputs?.first()?.status)
        assertEquals(listOf("persist", "publish", "refresh"), events)
        assertEquals(DeviceCleanupStatus.PENDING, active.cleanupJobs.single().outputs.first().status)
    }

    @Test
    fun `failed retry persistence leaves attention state unchanged`() {
        val job = jobWithAttentionOutput()
        val initial = state(job)
        var active = initial
        var refreshCount = 0
        val service =
            DeviceCleanupAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistStore = { throw IllegalStateException("disk full") },
                replaceState = { active = it },
                requestCleanup = { refreshCount += 1 },
            )

        assertThrows(IllegalStateException::class.java) { service.retry(job.id) }

        assertEquals(initial, active)
        assertEquals(0, refreshCount)
    }

    @Test
    fun `retry rejects jobs without attention outputs`() {
        val job = DeviceCleanupJob.fromDevice(device(), UUID.randomUUID())
        val service =
            DeviceCleanupAdministrationService(
                stateLock = Any(),
                stateProvider = { state(job) },
                persistStore = {},
                replaceState = {},
                requestCleanup = {},
            )

        assertThrows(DeviceCleanupJobHasNoAttentionOutputsException::class.java) {
            service.retry(job.id)
        }
    }

    @Test
    fun `list returns persisted cleanup jobs without mutation`() {
        val job = jobWithAttentionOutput()
        val initial = state(job)
        var persistCount = 0
        val service =
            DeviceCleanupAdministrationService(
                stateLock = Any(),
                stateProvider = { initial },
                persistStore = { persistCount += 1 },
                replaceState = {},
                requestCleanup = {},
            )

        assertEquals(listOf(job), service.list())
        assertEquals(0, persistCount)
        assertTrue(initial.cleanupJobs.single().outputs.any { it.status == DeviceCleanupStatus.NEEDS_ATTENTION })
    }

    private fun jobWithAttentionOutput(): DeviceCleanupJob {
        val device = device()
        val original = DeviceCleanupJob.fromDevice(device, UUID.randomUUID())
        return original.copy(
            outputs =
                original.outputs.mapIndexed { index, output ->
                    when (index) {
                        0 -> DeviceCleanupOutput(output.output, DeviceCleanupStatus.NEEDS_ATTENTION, "Expected REDSTONE_WIRE, found STONE")
                        1 -> DeviceCleanupOutput(output.output, DeviceCleanupStatus.CLEARED)
                        else -> output
                    }
                },
        )
    }

    private fun device() =
        RedstoneDevice(
            id = UUID.randomUUID(),
            name = "clock",
            worldName = "world",
            enabled = false,
            outputs =
                (0 until 16).map { bit ->
                    DeviceOutput(bitIndex = bit, x = bit * 2, y = 64, z = 0)
                },
        )

    private fun state(job: DeviceCleanupJob): RuntimeState {
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
            cleanupJobs = listOf(job),
        )
    }
}
