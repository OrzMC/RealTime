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

    @Test
    fun createRejectsWhenTheDeviceLimitIsReached() {
        val limits = RedstoneLimits(maxDevices = 1)
        var active = state(listOf(device()), limits = limits)
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { active = active.copy(devices = it) },
                replaceState = { active = it },
                requestRefresh = {},
            )

        assertThrows(DeviceLimitReachedException::class.java) {
            service.create(
                DeviceCreateRequest(
                    name = "second",
                    worldName = "world",
                    resolution = OutputResolution.MINUTE,
                    outputs = outputsFor(100),
                ),
            )
        }
    }

    @Test
    fun createRejectsPositionsOutsideTheAllowedAreas() {
        val limits =
            RedstoneLimits(
                allowedAreas = listOf(AllowedArea("world", 0, 10, 0, 10)),
            )
        var active = state(emptyList(), limits = limits)
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { active = active.copy(devices = it) },
                replaceState = { active = it },
                requestRefresh = {},
            )

        assertThrows(DeviceAreaNotAllowedException::class.java) {
            service.create(
                DeviceCreateRequest(
                    name = "outside",
                    worldName = "world",
                    resolution = OutputResolution.MINUTE,
                    outputs = outputsFor(100),
                ),
            )
        }
    }

    @Test
    fun createRejectsCoordinatesUsedByAnotherDeviceOrCleanupJob() {
        val existing = device()
        val job = DeviceCleanupJob.fromDevice(device(enabled = false), null)
        var active = state(listOf(existing), cleanupJobs = listOf(job))
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = { active = active.copy(devices = it) },
                replaceState = { active = it },
                requestRefresh = {},
            )

        val overlappingDevice = outputsFor(0)
        assertThrows(DeviceOutputConflictException::class.java) {
            service.create(
                DeviceCreateRequest("duplicate", "world", OutputResolution.MINUTE, overlappingDevice),
            )
        }
    }

    @Test
    fun createPersistsBeforePublishingTheNewDevice() {
        var active = state(emptyList())
        val events = mutableListOf<String>()
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = {
                    events += "persist"
                    active = active.copy(devices = it)
                },
                replaceState = {
                    events += "publish"
                    active = it
                },
                requestRefresh = { events += "refresh" },
            )

        val created =
            service.create(
                DeviceCreateRequest("fresh", "world", OutputResolution.SECOND, outputsFor(200, OutputResolution.SECOND)),
            )

        assertEquals(24, created.outputs.size)
        assertEquals(OutputResolution.SECOND, created.resolution)
        assertEquals(false, created.enabled)
        assertEquals(listOf("persist", "publish", "refresh"), events)
        assertEquals(created, active.devices.single())
    }

    @Test
    fun setOutputRejectsIndexesOutsideTheResolution() {
        var active = state(listOf(device(resolution = OutputResolution.MINUTE)))
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = {},
                replaceState = { active = it },
                requestRefresh = {},
            )

        assertThrows(DeviceOutputIndexException::class.java) {
            service.setOutput(active.devices.single().id, 16, 5, 64, 5)
        }
    }

    @Test
    fun setOutputKeepsTheResolutionAndRejectsOccupiedPositions() {
        val first = device(resolution = OutputResolution.SECOND)
        val second = device(resolution = OutputResolution.SECOND)
        var active = state(listOf(first, second))
        val events = mutableListOf<String>()
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = {
                    events += "persist"
                    active = active.copy(devices = it)
                },
                replaceState = { active = it },
                requestRefresh = { events += "refresh" },
            )

        assertThrows(DeviceOutputConflictException::class.java) {
            service.setOutput(first.id, 0, second.outputs.first().x, 64, 0)
        }

        val moved = service.setOutput(first.id, 0, 40, 64, 40)
        assertEquals(OutputResolution.SECOND, moved.resolution)
        assertEquals(40, moved.outputs.first { it.bitIndex == 0 }.x)
        assertEquals(listOf("persist", "refresh"), events)
    }

    @Test
    fun setOutputRejectsCoordinatesUsedByAnotherBitOfTheSameDevice() {
        val device = device()
        var active = state(listOf(device))
        val service =
            DeviceAdministrationService(
                stateLock = Any(),
                stateProvider = { active },
                persistDevices = {},
                replaceState = { active = it },
                requestRefresh = {},
            )

        val other = device.outputs.first { it.bitIndex == 1 }
        assertThrows(DeviceOutputConflictException::class.java) {
            service.setOutput(device.id, 0, other.x, other.y, other.z)
        }
    }

    private fun outputsFor(
        startX: Int,
        resolution: OutputResolution = OutputResolution.MINUTE,
    ): List<DeviceOutput> =
        (0 until resolution.outputCount).map { bit ->
            DeviceOutput(bitIndex = bit, x = startX + bit, y = 64, z = 0)
        }

    private fun state(
        devices: List<RedstoneDevice>,
        limits: RedstoneLimits = RedstoneLimits(),
        cleanupJobs: List<DeviceCleanupJob> = emptyList(),
    ): RuntimeState {
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
            redstoneConfiguration = RedstoneConfiguration(enabled = true, limits = limits),
            devices = devices,
            cleanupJobs = cleanupJobs,
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
