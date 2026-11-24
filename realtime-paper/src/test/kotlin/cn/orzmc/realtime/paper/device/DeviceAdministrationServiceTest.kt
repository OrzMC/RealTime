package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputResolution
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceAdministrationServiceTest {
    @Test
    fun enablesDeviceAfterSuccessfulPersistence() {
        val initial = state(listOf(device(enabled = false)))
        var active = initial
        var persisted: List<RedstoneDevice>? = null
        var refreshCount = 0
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { persisted = it },
                replaceState = { active = it },
                requestRefresh = { refreshCount += 1 },
            )

        val updated = service.setEnabled(initial.devices.single().id, true)

        assertTrue(updated.enabled)
        assertTrue(active.devices.single().enabled)
        assertEquals(active.devices, persisted)
        assertEquals(1, refreshCount)
    }

    @Test
    fun repeatedEnableIsAnIdempotentNoOp() {
        val initial = state(listOf(device(enabled = true)))
        var active = initial
        var persistCount = 0
        var refreshCount = 0
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { persistCount += 1 },
                replaceState = { active = it },
                requestRefresh = { refreshCount += 1 },
            )

        val unchanged = service.setEnabled(initial.devices.single().id, true)

        assertTrue(unchanged.enabled)
        assertEquals(0, persistCount)
        assertEquals(0, refreshCount)
        assertEquals(initial.devices, active.devices)
    }

    @Test
    fun failedPersistenceKeepsTheActiveRuntimeStateUnchanged() {
        val initial = state(listOf(device(enabled = false)))
        var active = initial
        var refreshCount = 0
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { throw IllegalStateException("disk full") },
                replaceState = { active = it },
                requestRefresh = { refreshCount += 1 },
            )

        assertThrows(IllegalStateException::class.java) {
            service.setEnabled(initial.devices.single().id, true)
        }

        assertEquals(initial, active)
        assertEquals(0, refreshCount)
    }

    @Test
    fun missingDeviceDoesNotPersistOrRefresh() {
        val initial = state(listOf(device()))
        var active = initial
        var persistCount = 0
        var refreshCount = 0
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { persistCount += 1 },
                replaceState = { active = it },
                requestRefresh = { refreshCount += 1 },
            )

        assertThrows(DeviceNotFoundException::class.java) {
            service.setEnabled(UUID.randomUUID(), true)
        }

        assertEquals(initial, active)
        assertEquals(0, persistCount)
        assertEquals(0, refreshCount)
    }

    @Test
    fun mutationsWaitForTheSharedRuntimeStateLock() {
        val lock = Any()
        val initial = state(listOf(device(enabled = false)))
        var active = initial
        val service =
            DeviceAdministrationService(
                stateLock = lock,
                stateProvider = { active },
                persistDevices = {},
                replaceState = { active = it },
                requestRefresh = {},
            )
        val reloadEntered = CountDownLatch(1)
        val releaseReload = CountDownLatch(1)
        val mutationStarted = CountDownLatch(1)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val reload =
                CompletableFuture.runAsync({
                    synchronized(lock) {
                        reloadEntered.countDown()
                        check(releaseReload.await(2, TimeUnit.SECONDS))
                    }
                }, executor)
            assertTrue(reloadEntered.await(2, TimeUnit.SECONDS))

            val mutation =
                CompletableFuture.supplyAsync({
                    mutationStarted.countDown()
                    service.setEnabled(initial.devices.single().id, true)
                }, executor)
            assertTrue(mutationStarted.await(2, TimeUnit.SECONDS))
            assertFalse(mutation.isDone)

            releaseReload.countDown()
            reload.get(2, TimeUnit.SECONDS)
            assertTrue(mutation.get(2, TimeUnit.SECONDS).enabled)
        } finally {
            releaseReload.countDown()
            executor.shutdownNow()
        }
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

    private fun device(
        enabled: Boolean = true,
        resolution: OutputResolution = OutputResolution.MINUTE,
    ) = RedstoneDevice(
        id = UUID.randomUUID(),
        name = "clock",
        worldName = "world",
        enabled = enabled,
        outputs =
            (0 until resolution.outputCount).map { bit ->
                DeviceOutput(bitIndex = bit, x = bit * 2, y = 64, z = 0)
            },
        resolution = resolution,
    )

    @Test
    fun keepsSecondResolutionWhenTogglingEnabledState() {
        val initial = state(listOf(device(resolution = OutputResolution.SECOND)))
        var active = initial
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = {},
                replaceState = { active = it },
                requestRefresh = {},
            )

        val updated = service.setEnabled(initial.devices.single().id, false)

        assertFalse(updated.enabled)
        assertEquals(OutputResolution.SECOND, updated.resolution)
        assertEquals(24, updated.outputs.size)
        assertEquals(updated, active.devices.single())
    }
}
