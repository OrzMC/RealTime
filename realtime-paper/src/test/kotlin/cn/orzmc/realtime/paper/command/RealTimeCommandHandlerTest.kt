package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupJob
import cn.orzmc.realtime.paper.device.DeviceDiagnostics
import cn.orzmc.realtime.paper.device.DeviceCleanupOutput
import cn.orzmc.realtime.paper.device.DeviceCleanupStatus
import cn.orzmc.realtime.paper.device.RedstoneDiagnosticsSnapshot
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class RealTimeCommandHandlerTest {
    @Test
    fun noArgumentsDisplaysCurrentConfiguredTime() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, emptyArray())

        assertEquals(1, target.messages.size)
        assertTrue(target.messages.single().contains("2026-11-23 10:00:00 +08:00"))
    }

    @Test
    fun infoDisplaysVersionTimezoneAndModuleState() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("info"))

        assertTrue(target.messages.any { it.contains("version 1.2.3") })
        assertTrue(target.messages.any { it.contains("timezone Asia/Shanghai") })
        assertTrue(target.messages.any { it.contains("locale zh-CN") })
    }

    @Test
    fun commandPermissionIsRequiredForNowAndInfo() {
        val target = RecordingTarget(emptySet())
        val handler = handler()

        handler.execute(target, arrayOf("now"))

        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun reloadRequiresAdminPermission() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        var reloadCalled = false
        val handler =
            handler(reload = {
                reloadCalled = true
                Result.success(state())
            })

        handler.execute(target, arrayOf("reload"))

        assertFalse(reloadCalled)
        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun invalidArgumentsReturnUsage() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("now", "extra"))

        assertEquals(listOf("[RealTime] usage"), target.messages)
    }

    @Test
    fun failedReloadReportsErrorWithoutChangingActiveState() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.ADMIN_PERMISSION))
        val activeState = state()
        val handler =
            RealTimeCommandHandler(
                pluginVersion = "1.2.3",
                currentState = { activeState },
                reload = { Result.failure(IllegalArgumentException("bad timezone")) },
            )

        handler.execute(target, arrayOf("reload"))

        assertTrue(target.messages.single().contains("bad timezone"))
        assertEquals(ZoneId.of("Asia/Shanghai"), activeState.configuration.zoneId)
    }

    @Test
    fun deviceListRequiresCommandPermission() {
        val target = RecordingTarget(emptySet())
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(device())))

        handler.execute(target, arrayOf("device", "list"))

        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun deviceStatusRequiresCommandPermission() {
        val target = RecordingTarget(emptySet())
        val handler =
            handler(
                deviceAdministration = RecordingDeviceAdministration(listOf(device())),
                deviceDiagnostics = DeviceDiagnostics { diagnosticsSnapshot() },
            )

        handler.execute(target, arrayOf("device", "status"))

        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun deviceStatusShowsThreadSafeAggregateCounters() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler =
            handler(
                deviceAdministration = RecordingDeviceAdministration(listOf(device())),
                deviceDiagnostics = DeviceDiagnostics { diagnosticsSnapshot() },
            )

        handler.execute(target, arrayOf("device", "status"))

        assertTrue(target.messages.any { it.contains("devices 2 enabled 1 outputs 32") })
        assertTrue(target.messages.any { it.contains("checks 48 unloaded 3 stale 2") })
        assertTrue(target.messages.any { it.contains("changed 10 unchanged 20 invalid 4 failures 1") })
    }

    @Test
    fun deviceInfoShowsTheOutputCoordinateMapping() {
        val device = device()
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(device)))

        handler.execute(target, arrayOf("device", "info", device.id.toString()))

        assertTrue(target.messages.any { it.contains("clock") })
        assertTrue(target.messages.any { it.contains(device.id.toString()) })
        assertTrue(target.messages.any { it.contains("bit 0 at 0, 64, 0") })
        assertEquals(16, target.messages.count { it.contains("bit ") && it.contains(" at ") })
    }

    @Test
    fun deviceInfoRejectsMalformedAndUnknownIds() {
        val administration = RecordingDeviceAdministration(listOf(device()))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "info", "not-a-uuid"))
        handler.execute(target, arrayOf("device", "info", UUID.randomUUID().toString()))

        assertTrue(target.messages.any { it.contains("invalid not-a-uuid") })
        assertTrue(target.messages.any { it.contains("not found") })
        assertEquals(0, administration.mutationCount)
    }

    @Test
    fun deviceMutationRequiresAdminPermission() {
        val device = device()
        val administration = RecordingDeviceAdministration(listOf(device))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "enable", device.id.toString()))

        assertEquals(listOf("[RealTime] denied"), target.messages)
        assertEquals(0, administration.mutationCount)
    }

    @Test
    fun deviceDisableIsAvailableToAdmins() {
        val device = device()
        val administration = RecordingDeviceAdministration(listOf(device))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.ADMIN_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "disable", device.id.toString()))

        assertEquals(1, administration.mutationCount)
        assertFalse(administration.devices.single().enabled)
        assertTrue(target.messages.single().contains("disabled"))
    }

    @Test
    fun deviceTabCompletionDoesNotExposeMutationIdsToReadOnlyUsers() {
        val device = device()
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(device)))
        val readOnly = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val unauthorized = RecordingTarget(emptySet())

        assertEquals(
            listOf(device.id.toString()),
            handler.complete(readOnly, arrayOf("device", "info", "")),
        )
        assertTrue(handler.complete(readOnly, arrayOf("device", "disable", "")).isEmpty())
        assertEquals(listOf("status"), handler.complete(readOnly, arrayOf("device", "sta")))
        assertTrue(handler.complete(unauthorized, arrayOf("device", "info", "")).isEmpty())
    }

    @Test
    fun cleanupQueueListRequiresCommandPermission() {
        val target = RecordingTarget(emptySet())
        val handler = handler(cleanupAdministration = RecordingCleanupAdministration(listOf(cleanupJob())))

        handler.execute(target, arrayOf("device", "cleanup", "list"))

        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun cleanupRetryRequiresAdminPermission() {
        val job = cleanupJob(attention = true)
        val administration = RecordingCleanupAdministration(listOf(job))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler(cleanupAdministration = administration)

        handler.execute(target, arrayOf("device", "cleanup", "retry", job.id.toString()))

        assertEquals(listOf("[RealTime] denied"), target.messages)
        assertEquals(0, administration.retryCount)
    }

    @Test
    fun cleanupQueueListShowsPendingAndAttentionCounts() {
        val job = cleanupJob(attention = true)
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler(cleanupAdministration = RecordingCleanupAdministration(listOf(job)))

        handler.execute(target, arrayOf("device", "cleanup", "list"))

        assertTrue(target.messages.any { it.contains("cleanup jobs 1") })
        assertTrue(target.messages.any { it.contains("clock") && it.contains("pending 15") && it.contains("attention 1") })
    }

    @Test
    fun cleanupRetryIsAvailableToAdminsAndCallsAdministration() {
        val job = cleanupJob(attention = true)
        val administration = RecordingCleanupAdministration(listOf(job))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.ADMIN_PERMISSION))
        val handler = handler(cleanupAdministration = administration)

        handler.execute(target, arrayOf("device", "cleanup", "retry", job.id.toString()))

        assertEquals(1, administration.retryCount)
        assertTrue(target.messages.single().contains("retry queued"))
    }

    @Test
    fun cleanupTabCompletionHidesRetryFromReadOnlyUsers() {
        val job = cleanupJob(attention = true)
        val handler = handler(cleanupAdministration = RecordingCleanupAdministration(listOf(job)))
        val readOnly = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val admin = RecordingTarget(setOf(RealTimeCommandHandler.ADMIN_PERMISSION))

        assertEquals(listOf("list"), handler.complete(readOnly, arrayOf("device", "cleanup", "")))
        assertEquals(listOf("retry"), handler.complete(admin, arrayOf("device", "cleanup", "")))
        assertEquals(listOf(job.id.toString()), handler.complete(admin, arrayOf("device", "cleanup", "retry", "")))
    }

    private fun handler(
        reload: () -> Result<RuntimeState> = { Result.success(state()) },
        deviceAdministration: DeviceAdministration? = null,
        deviceDiagnostics: DeviceDiagnostics? = null,
        cleanupAdministration: DeviceCleanupAdministration? = null,
    ) = RealTimeCommandHandler(
        pluginVersion = "1.2.3",
        currentState = ::state,
        reload = reload,
        deviceAdministration = deviceAdministration,
        deviceDiagnostics = deviceDiagnostics,
        cleanupAdministration = cleanupAdministration,
    )

    private fun cleanupJob(attention: Boolean = false): DeviceCleanupJob {
        val device = device()
        val original =
            DeviceCleanupJob.fromDevice(device, UUID.randomUUID())
        return if (!attention) {
            original
        } else {
            original.copy(
                outputs =
                    original.outputs.mapIndexed { index, output ->
                        if (index == 0) {
                            DeviceCleanupOutput(output.output, DeviceCleanupStatus.NEEDS_ATTENTION, "found STONE")
                        } else {
                            output
                        }
                    },
            )
        }
    }

    private fun diagnosticsSnapshot() =
        RedstoneDiagnosticsSnapshot(
            configuredDevices = 2,
            enabledDevices = 1,
            configuredOutputs = 32,
            outputChecks = 48,
            unloadedChunkSkips = 3,
            staleOutputUpdates = 2,
            invalidOutputBlocks = 4,
            powerChanges = 10,
            unchangedOutputs = 20,
            writeFailures = 1,
        )

    private fun state(): RuntimeState {
        val configuration =
            RealTimeConfiguration(
                generalEnabled = true,
                zoneId = ZoneId.of("Asia/Shanghai"),
                locale = Locale.forLanguageTag("zh-CN"),
                debugLogging = false,
                commandEnabled = true,
                commandFormat = "uuuu-MM-dd HH:mm:ss XXX",
                messages =
                    mapOf(
                        "prefix" to "[RealTime] ",
                        "command.now" to "{prefix}{datetime}",
                        "command.info.header" to "{prefix}info",
                        "command.info.version" to "{prefix}version {version}",
                        "command.info.timezone" to "{prefix}timezone {timezone}",
                        "command.info.locale" to "{prefix}locale {locale}",
                        "command.info.global-enabled" to "{prefix}global {enabled}",
                        "command.info.command-enabled" to "{prefix}command {enabled}",
                        "command.reload.success" to "{prefix}reloaded",
                        "command.reload.failure" to "{prefix}failed {reason}",
                        "command.no-permission" to "{prefix}denied",
                        "command.disabled" to "{prefix}disabled",
                        "command.usage" to "{prefix}usage",
                        "command.device.list.header" to "{prefix}devices {count}",
                        "command.device.list.empty" to "{prefix}empty",
                        "command.device.list.item" to "{prefix}{name} {id} {world} {enabled}",
                        "command.device.info.header" to "{prefix}{name}",
                        "command.device.info.id" to "{prefix}{id}",
                        "command.device.info.world" to "{prefix}{world}",
                        "command.device.info.enabled" to "{prefix}{enabled}",
                        "command.device.info.outputs" to "{prefix}outputs {count}",
                        "command.device.info.output" to "{prefix}bit {bit} at {x}, {y}, {z}",
                        "command.device.state.enabled" to "enabled",
                        "command.device.state.disabled" to "disabled",
                        "command.device.enable.success" to "{prefix}enabled {name}",
                        "command.device.disable.success" to "{prefix}disabled {name}",
                        "command.device.invalid-id" to "{prefix}invalid {id}",
                        "command.device.not-found" to "{prefix}not found {id}",
                        "command.device.error" to "{prefix}error {reason}",
                        "command.device.unavailable" to "{prefix}unavailable",
                        "command.device.status.header" to "{prefix}device diagnostics",
                        "command.device.cleanup.header" to "{prefix}cleanup jobs {count}",
                        "command.device.cleanup.empty" to "{prefix}cleanup empty",
                        "command.device.cleanup.item" to "{prefix}{name} {id} {world} pending {pending} attention {attention}",
                        "command.device.cleanup.retry.success" to "{prefix}retry queued {id}",
                        "command.device.cleanup.invalid-id" to "{prefix}cleanup invalid {id}",
                        "command.device.cleanup.not-found" to "{prefix}cleanup not found {id}",
                        "command.device.cleanup.error" to "{prefix}cleanup error {reason}",
                        "command.device.status.devices" to "{prefix}devices {configured} enabled {enabled} outputs {outputs}",
                        "command.device.status.checks" to "{prefix}checks {checks} unloaded {unloaded} stale {stale}",
                        "command.device.status.outcomes" to
                            "{prefix}changed {changed} unchanged {unchanged} invalid {invalid} failures {failures}",
                    ),
            )
        return RuntimeState(
            configuration = configuration,
            timeService =
                ClockRealTimeService(
                    clock = Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC),
                    zoneId = configuration.zoneId,
                    locale = configuration.locale,
                ),
            messages = MessageCatalog(configuration.messages),
        )
    }

    private fun device() =
        RedstoneDevice(
            id = UUID.randomUUID(),
            name = "clock",
            worldName = "world",
            enabled = true,
            outputs =
                (0 until 16).map { bit ->
                    DeviceOutput(bitIndex = bit, x = bit * 2, y = 64, z = 0)
                },
        )

    private class RecordingDeviceAdministration(
        initialDevices: List<RedstoneDevice>,
    ) : DeviceAdministration {
        val devices = initialDevices.toMutableList()
        var mutationCount = 0

        override fun list(): List<RedstoneDevice> = devices.toList()

        override fun find(id: UUID): RedstoneDevice? = devices.firstOrNull { it.id == id }

        override fun setEnabled(
            id: UUID,
            enabled: Boolean,
        ): RedstoneDevice {
            mutationCount += 1
            val current = devices.first { it.id == id }
            val updated =
                RedstoneDevice(
                    id = current.id,
                    name = current.name,
                    worldName = current.worldName,
                    enabled = enabled,
                    outputs = current.outputs,
                )
            devices[devices.indexOf(current)] = updated
            return updated
        }
    }

    private class RecordingCleanupAdministration(
        private val jobs: List<DeviceCleanupJob>,
    ) : DeviceCleanupAdministration {
        var retryCount = 0

        override fun list(): List<DeviceCleanupJob> = jobs

        override fun retry(jobId: UUID): DeviceCleanupJob {
            retryCount += 1
            return jobs.first { it.id == jobId }
        }
    }

    private class RecordingTarget(
        private val permissions: Set<String>,
    ) : CommandTarget {
        val messages = mutableListOf<String>()

        override fun hasPermission(permission: String): Boolean = permission in permissions

        override fun sendMessage(message: String) {
            messages += message
        }
    }
}
