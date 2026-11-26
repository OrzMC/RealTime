package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.DemoBlock
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupDemo
import cn.orzmc.realtime.paper.device.DeviceCleanupJob
import cn.orzmc.realtime.paper.device.DeviceCleanupOutput
import cn.orzmc.realtime.paper.device.DeviceCleanupStatus
import cn.orzmc.realtime.paper.device.DeviceCreateRequest
import cn.orzmc.realtime.paper.device.DeviceDiagnostics
import cn.orzmc.realtime.paper.device.DeviceMustBeDisabledException
import cn.orzmc.realtime.paper.device.DeviceRetirementAdministration
import cn.orzmc.realtime.paper.device.RedstoneConfiguration
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
        assertTrue(target.messages.any { it.contains("redstone true") }, target.messages.toString())
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
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
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
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(device)))

        handler.execute(target, arrayOf("device", "info", device.id.toString()))

        assertTrue(target.messages.any { it.contains("clock") })
        assertTrue(target.messages.any { it.contains(device.id.toString()) })
        assertTrue(target.messages.any { it.contains("bit 0 at 0, 64, 0") })
        assertTrue(target.messages.any { it.contains("HHmm") })
        assertEquals(16, target.messages.count { it.contains("bit ") && it.contains(" at ") })
    }

    @Test
    fun deviceInfoShowsAllTwentyFourOutputsForSecondResolution() {
        val device = device(OutputResolution.SECOND)
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(device)))

        handler.execute(target, arrayOf("device", "info", device.id.toString()))

        assertTrue(target.messages.any { it.contains("HHmmss") })
        assertEquals(24, target.messages.count { it.contains("bit ") && it.contains(" at ") })
    }

    @Test
    fun deviceInfoRejectsMalformedAndUnknownIds() {
        val administration = RecordingDeviceAdministration(listOf(device()))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "info", "no-such-device"))
        handler.execute(target, arrayOf("device", "info", UUID.randomUUID().toString()))

        // Names are accepted, so an unknown token is simply "not found" rather than a syntax error.
        assertEquals(2, target.messages.count { it.contains("not found") }, target.messages.toString())
        assertEquals(0, administration.mutationCount)
    }

    @Test
    fun deviceMutationRequiresAdminPermission() {
        val device = device()
        val administration = RecordingDeviceAdministration(listOf(device))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "enable", device.id.toString()))

        assertEquals(listOf("[RealTime] denied"), target.messages)
        assertEquals(0, administration.mutationCount)
    }

    @Test
    fun deviceDisableIsAvailableToAdmins() {
        val device = device()
        val administration = RecordingDeviceAdministration(listOf(device))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.TOGGLE_PERMISSION))
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
        val readOnly = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val unauthorized = RecordingTarget(emptySet())

        assertEquals(
            listOf(device.name, device.id.toString()),
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
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(cleanupAdministration = administration)

        handler.execute(target, arrayOf("device", "cleanup", "retry", job.id.toString()))

        assertEquals(listOf("[RealTime] denied"), target.messages)
        assertEquals(0, administration.retryCount)
    }

    @Test
    fun cleanupQueueListShowsPendingAndAttentionCounts() {
        val job = cleanupJob(attention = true)
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(cleanupAdministration = RecordingCleanupAdministration(listOf(job)))

        handler.execute(target, arrayOf("device", "cleanup", "list"))

        assertTrue(target.messages.any { it.contains("cleanup jobs 1") })
        assertTrue(target.messages.any { it.contains("clock") && it.contains("pending 15") && it.contains("attention 1") })
    }

    @Test
    fun cleanupRetryIsAvailableToAdminsAndCallsAdministration() {
        val job = cleanupJob(attention = true)
        val administration = RecordingCleanupAdministration(listOf(job))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.RETIRE_PERMISSION))
        val handler = handler(cleanupAdministration = administration)

        handler.execute(target, arrayOf("device", "cleanup", "retry", job.id.toString()))

        assertEquals(1, administration.retryCount)
        assertTrue(target.messages.single().contains("retry queued"))
    }

    @Test
    fun cleanupListCountsDemoDisplayBlocks() {
        val job =
            cleanupJob().let { base ->
                base.copy(
                    display =
                        listOf(
                            DeviceCleanupDemo(DemoBlock(9999, 65, 0, "minecraft:redstone_lamp")),
                            DeviceCleanupDemo(
                                DemoBlock(9999, 65, 2, "minecraft:redstone_lamp"),
                                DeviceCleanupStatus.NEEDS_ATTENTION,
                                "Expected AIR or minecraft:redstone_lamp, found DARK_OAK_LOG",
                            ),
                        ),
                )
            }
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(cleanupAdministration = RecordingCleanupAdministration(listOf(job)))

        handler.execute(target, arrayOf("device", "cleanup", "list"))

        // 16 slots (all PENDING) + 1 pending display block, 1 display block needs attention.
        assertTrue(target.messages.any { it.contains("pending 17") && it.contains("attention 1") }, target.messages.toString())
    }

    @Test
    fun cleanupDismissDropsTheJobAndReportsPendingJobs() {
        val job = cleanupJob(attention = true)
        val administration = RecordingCleanupAdministration(listOf(job))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.RETIRE_PERMISSION))
        val handler = handler(cleanupAdministration = administration)

        handler.execute(target, arrayOf("device", "cleanup", "dismiss", job.id.toString()))

        assertEquals(1, administration.dismissCount)
        assertTrue(target.messages.single().contains("丢弃") || target.messages.single().contains("dismiss"))
    }

    @Test
    fun cleanupDismissRequiresRetirePermission() {
        val job = cleanupJob(attention = true)
        val administration = RecordingCleanupAdministration(listOf(job))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(cleanupAdministration = administration)

        handler.execute(target, arrayOf("device", "cleanup", "dismiss", job.id.toString()))

        assertEquals(0, administration.dismissCount)
        assertEquals(listOf("[RealTime] denied"), target.messages)
    }

    @Test
    fun cleanupTabCompletionHidesRetryFromReadOnlyUsers() {
        val job = cleanupJob(attention = true)
        val handler = handler(cleanupAdministration = RecordingCleanupAdministration(listOf(job)))
        val readOnly = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val admin = RecordingTarget(setOf(RealTimeCommandHandler.RETIRE_PERMISSION))

        assertEquals(listOf("list"), handler.complete(readOnly, arrayOf("device", "cleanup", "")))
        assertEquals(listOf("list", "retry", "dismiss"), handler.complete(admin, arrayOf("device", "cleanup", "")))
        assertEquals(listOf(job.id.toString()), handler.complete(admin, arrayOf("device", "cleanup", "retry", "")))
    }

    @Test
    fun removeDeviceRequiresAdminPermission() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val retirement = RecordingRetirementAdministration(cleanupJob())
        val handler =
            handler(
                deviceAdministration = RecordingDeviceAdministration(listOf(device(id = retirement.deviceId))),
                deviceRetirement = retirement,
            )

        handler.execute(target, arrayOf("device", "remove", retirement.deviceId.toString()))

        assertEquals(listOf("[RealTime] denied"), target.messages)
        assertEquals(0, retirement.retireCount)
    }

    @Test
    fun removeDeviceRetiresDisabledDeviceAndReportsCleanupJob() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.RETIRE_PERMISSION))
        val retirement = RecordingRetirementAdministration(cleanupJob())
        val handler =
            handler(
                deviceAdministration = RecordingDeviceAdministration(listOf(device(id = retirement.deviceId))),
                deviceRetirement = retirement,
            )

        handler.execute(target, arrayOf("device", "remove", retirement.deviceId.toString()))

        assertEquals(1, retirement.retireCount)
        assertTrue(target.messages.single().contains("retired"), target.messages.toString())
        assertTrue(target.messages.single().contains(retirement.job.id.toString()), target.messages.toString())
    }

    @Test
    fun removeDeviceIdCompletionIsAdminOnly() {
        val configuredDevice = device()
        val job = DeviceCleanupJob.fromDevice(configuredDevice, UUID.randomUUID())
        val retirement = RecordingRetirementAdministration(job)
        val handler =
            handler(
                deviceAdministration = RecordingDeviceAdministration(listOf(configuredDevice)),
                deviceRetirement = retirement,
            )
        val readOnly = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val admin = RecordingTarget(setOf(RealTimeCommandHandler.RETIRE_PERMISSION))

        assertFalse(handler.complete(readOnly, arrayOf("device", "remove", "")).contains(retirement.deviceId.toString()))
        assertEquals(
            listOf(configuredDevice.name, retirement.deviceId.toString()),
            handler.complete(admin, arrayOf("device", "remove", "")),
        )
    }

    @Test
    fun removeDeviceReportsDisabledRequirement() {
        val job = cleanupJob()
        val retirement =
            RecordingRetirementAdministration(
                job = job,
                failure = DeviceMustBeDisabledException(job.deviceId),
            )
        val target = RecordingTarget(setOf(RealTimeCommandHandler.RETIRE_PERMISSION))
        val handler =
            handler(
                deviceAdministration = RecordingDeviceAdministration(listOf(device(id = job.deviceId))),
                deviceRetirement = retirement,
            )

        handler.execute(target, arrayOf("device", "remove", job.deviceId.toString()))

        assertTrue(target.messages.single().contains("must disable"), target.messages.toString())
    }

    @Test
    fun deviceCreateRequiresEditPermission() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "second", "world", "0", "64", "0", "x+"))

        assertEquals(setOf("[RealTime] denied"), target.messages.toSet())
        assertEquals(null, administration.created)
    }

    @Test
    fun deviceCreateLaysOutSlotsAlongTheAxis() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "second", "world", "100", "64", "7", "x+", "2"))

        val request = checkNotNull(administration.created)
        assertEquals("clock", request.name)
        assertEquals("world", request.worldName)
        assertEquals(OutputResolution.SECOND, request.resolution)
        assertEquals(24, request.outputs.size)
        assertEquals(100, request.outputs.first().x)
        assertEquals(7, request.outputs.first().z)
        // Stride 2 plus a one-block gap after every four bits: 23 * 2 + 5.
        assertEquals(151, request.outputs.last().x)
        assertTrue(target.messages.any { it.contains("created") || it.contains("已创建") })
    }

    @Test
    fun deviceCreateAcceptsAnalogEncodingWithOneSlotPerDigit() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(
            target,
            arrayOf("device", "create", "clock", "second", "world", "100", "64", "7", "x+", "analog"),
        )

        val request = checkNotNull(administration.created)
        assertEquals(OutputEncoding.ANALOG, request.encoding)
        assertEquals(6, request.outputs.size)
        // Analog lecterns keep one empty block between them by default.
        assertEquals(listOf(100, 102, 104, 106, 108, 110), request.outputs.map { it.x })
    }

    @Test
    fun deviceCreateAcceptsStrideAndEncodingInEitherOrder() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(
            target,
            arrayOf("device", "create", "clock", "minute", "world", "0", "64", "0", "z-", "3", "bcd"),
        )

        val request = checkNotNull(administration.created)
        assertEquals(OutputEncoding.BCD, request.encoding)
        assertEquals(16, request.outputs.size)
        assertEquals(0, request.outputs.first().z)
        // 15 * 3 plus three digit gaps keeps the BCD grouping even with an explicit stride.
        assertEquals(-48, request.outputs.last().z)
    }

    @Test
    fun deviceCreateDefaultsToBcdWithOneBlockGapsAndDigitGroups() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "minute", "world", "0", "64", "0", "x+"))

        val request = checkNotNull(administration.created)
        assertEquals(OutputEncoding.BCD, request.encoding)
        assertEquals(16, request.outputs.size)
        assertEquals(
            listOf(0, 2, 4, 6, 9, 11, 13, 15, 18, 20, 22, 24, 27, 29, 31, 33),
            request.outputs.map { it.x },
        )
    }

    @Test
    fun deviceCreateRejectsUnknownEncoding() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "minute", "world", "0", "64", "0", "x+", "hex"))

        assertEquals(null, administration.created)
        assertTrue(target.messages.any { it.contains("hex") }, target.messages.toString())
    }

    @Test
    fun deviceCreateRejectsUnknownResolution() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "hourly", "world", "0", "64", "0", "x+"))

        assertEquals(null, administration.created)
        assertTrue(target.messages.any { it.contains("hourly") }, target.messages.toString())
    }

    @Test
    fun deviceCreateReportsLimitAreaAndConflictErrors() {
        val limitTarget = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        handler(
            deviceAdministration =
                object : DeviceAdministration by RecordingDeviceAdministration(emptyList()) {
                    override fun create(request: DeviceCreateRequest): RedstoneDevice =
                        throw cn.orzmc.realtime.paper.device
                            .DeviceLimitReachedException(5)
                },
        ).execute(limitTarget, arrayOf("device", "create", "c", "minute", "world", "0", "64", "0", "x+"))
        assertTrue(limitTarget.messages.any { it.contains("5") }, limitTarget.messages.toString())

        val areaTarget = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        handler(
            deviceAdministration =
                object : DeviceAdministration by RecordingDeviceAdministration(emptyList()) {
                    override fun create(request: DeviceCreateRequest): RedstoneDevice =
                        throw cn.orzmc.realtime.paper.device
                            .DeviceAreaNotAllowedException("world", 3, 4)
                },
        ).execute(areaTarget, arrayOf("device", "create", "c", "minute", "world", "0", "64", "0", "x+"))
        assertTrue(areaTarget.messages.any { it.contains("3") && it.contains("4") })

        val conflictTarget = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        handler(
            deviceAdministration =
                object : DeviceAdministration by RecordingDeviceAdministration(emptyList()) {
                    override fun create(request: DeviceCreateRequest): RedstoneDevice =
                        throw cn.orzmc.realtime.paper.device
                            .DeviceOutputConflictException(1, 2, 3)
                },
        ).execute(conflictTarget, arrayOf("device", "create", "c", "minute", "world", "0", "64", "0", "x+"))
        assertTrue(conflictTarget.messages.any { it.contains("1") && it.contains("3") })
    }

    @Test
    fun deviceSetMovesOneOutputAndRequiresEditPermission() {
        val device = device()
        val administration = RecordingDeviceAdministration(listOf(device))
        val readOnly = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val editor = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(readOnly, arrayOf("device", "set", device.id.toString(), "0", "1", "2", "3"))
        assertEquals(null, administration.movedOutput)

        handler.execute(editor, arrayOf("device", "set", device.id.toString(), "0", "1", "2", "3"))
        assertEquals(Triple(device.id, 0, Triple(1, 2, 3)), administration.movedOutput)
    }

    @Test
    fun deviceDemoCreatesAnEnabledTeachingBuild() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "demo", "analog", "world", "100", "70", "0", "x+"))

        val request = checkNotNull(administration.created)
        assertEquals(OutputEncoding.ANALOG, request.encoding)
        assertEquals(true, request.enabled)
        assertEquals(6, request.outputs.size)
        // lectern slots plus a comparator/dust/lamp row per digit with two supports
        assertEquals(request.outputs.size * 5, request.demoBlocks.size)
        assertTrue(request.demoBlocks.any { it.data.startsWith("minecraft:comparator") })
        assertTrue(target.messages.any { it.contains("教学示例") || it.contains("demo") }, target.messages.toString())
    }

    @Test
    fun deviceDemoWarnsWhenTheRedstoneModuleIsDisabled() {
        // The module is off on a fresh install, so nothing is ever placed; the
        // command must not look like a success with no explanation.
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = administration, redstoneEnabled = false)

        handler.execute(target, arrayOf("device", "demo", "analog"))

        assertTrue(target.messages.any { it.contains("warn redstone disabled") }, target.messages.toString())
    }

    @Test
    fun deviceDemoIsQuietWhenTheRedstoneModuleIsEnabled() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(emptyList()))

        handler.execute(target, arrayOf("device", "demo", "analog"))

        assertFalse(target.messages.any { it.contains("warn redstone disabled") }, target.messages.toString())
    }

    @Test
    fun deviceCreateWarnsWhenTheRedstoneModuleIsDisabled() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(emptyList()), redstoneEnabled = false)

        handler.execute(target, arrayOf("device", "create", "clock", "minute", "world", "0", "64", "0", "x+"))

        assertTrue(target.messages.any { it.contains("warn redstone disabled") }, target.messages.toString())
    }

    @Test
    fun deviceCreateAtThePlayerBuildsADisplayedClock() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "bcd"))

        val request = checkNotNull(administration.created)
        assertEquals("world", request.worldName)
        assertEquals(OutputResolution.MINUTE, request.resolution)
        assertEquals(16, request.outputs.size)
        assertEquals(100, request.outputs.first().x)
        assertEquals(70, request.outputs.first().y)
        assertTrue(request.enabled)
        // Same build as demo: one lamp above every slot.
        assertEquals(request.outputs.size, request.demoBlocks.size)
        assertTrue(request.demoBlocks.all { it.data == "minecraft:redstone_lamp" })
    }

    @Test
    fun deviceCreateManualStaysDisabledAndLeavesTheWorldAlone() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "analog", "manual"))

        val request = checkNotNull(administration.created)
        assertFalse(request.enabled)
        assertTrue(request.demoBlocks.isEmpty())
        assertTrue(target.messages.any { it.contains("manual clock") || it.contains("clock") }, target.messages.toString())
    }

    @Test
    fun deviceCreateNodisplayKeepsTheWorldUntouched() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "bcd", "second", "nodisplay"))

        val request = checkNotNull(administration.created)
        assertEquals(OutputResolution.SECOND, request.resolution)
        assertTrue(request.demoBlocks.isEmpty())
        assertTrue(request.enabled)
    }

    @Test
    fun deviceCreateWithoutAPlayerAsksForCoordinates() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "create", "clock", "bcd"))

        assertEquals(null, administration.created)
        assertEquals(listOf("[RealTime] needs a player"), target.messages)
    }

    @Test
    fun devicePlaceUsesThePlayerAimWhenNoCoordinatesAreGiven() {
        val configured = device(enabled = false)
        val administration = RecordingDeviceAdministration(listOf(configured))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "place", configured.name, "3"))

        assertEquals(Triple(configured.id, 3, Triple(100, 70, 0)), administration.movedOutput)
    }

    @Test
    fun devicePlaceRejectsCoordinatesInAnotherWorld() {
        val configured = device(enabled = false)
        val administration = RecordingDeviceAdministration(listOf(configured))
        val target =
            RecordingTarget(
                setOf(RealTimeCommandHandler.EDIT_PERMISSION),
                placement = PlayerPlacement("nether", 1, 2, 3, LayoutAxis.X_PLUS),
            )
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "place", configured.name, "0"))

        assertEquals(null, administration.movedOutput)
        assertTrue(target.messages.single().contains("world mismatch"), target.messages.toString())
    }

    @Test
    fun deviceLookupAcceptsTheDeviceName() {
        val configured = device()
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(configured)))

        handler.execute(target, arrayOf("device", "info", configured.name))

        assertTrue(target.messages.any { it.contains("clock") }, target.messages.toString())
    }

    @Test
    fun helpListsTheCoreCommands() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("help"))

        // The default help only lists the five everyday commands.
        assertTrue(target.messages.any { it.contains("help create") }, target.messages.toString())
        assertTrue(target.messages.any { it.contains("help info") }, target.messages.toString())
        assertTrue(target.messages.any { it.contains("help toggle") }, target.messages.toString())
        assertFalse(target.messages.any { it.contains("help place") }, target.messages.toString())
    }

    @Test
    fun helpAllAppendsTheAdvancedCommands() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("help", "all"))

        assertTrue(target.messages.any { it.contains("help place") }, target.messages.toString())
        assertTrue(target.messages.any { it.contains("help status") }, target.messages.toString())
    }

    @Test
    fun flatCreateBuildsAClockWithoutTheDeviceVerb() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), placement = placement())
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("create", "clock", "analog"))

        val request = checkNotNull(administration.created)
        assertEquals(OutputEncoding.ANALOG, request.encoding)
        assertTrue(request.enabled)
    }

    @Test
    fun flatInfoListsDevicesAndAcceptsAName() {
        val configured = device()
        val target = RecordingTarget(setOf(RealTimeCommandHandler.VIEW_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(configured)))

        handler.execute(target, arrayOf("info", configured.name))

        assertTrue(target.messages.any { it.contains("clock") }, target.messages.toString())
    }

    @Test
    fun flatOnAndOffToggleByDeviceName() {
        val configured = device()
        val administration = RecordingDeviceAdministration(listOf(configured))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.TOGGLE_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("off", configured.name))
        handler.execute(target, arrayOf("on", configured.name))

        assertEquals(2, administration.mutationCount, target.messages.toString())
    }

    @Test
    fun flatPlaceMovesASlot() {
        val configured = device(enabled = false)
        val administration = RecordingDeviceAdministration(listOf(configured))
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("place", configured.name, "2", "5", "64", "1"))

        assertEquals(Triple(configured.id, 2, Triple(5, 64, 1)), administration.movedOutput)
    }

    @Test
    fun flatVerbsCompleteTheirArguments() {
        val configured = device(enabled = false)
        val target = RecordingTarget(setOf(RealTimeCommandHandler.ADMIN_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(configured)))

        val verbs = handler.complete(target, arrayOf(""))
        assertTrue(verbs.containsAll(listOf("create", "place", "on", "off", "info", "help")), verbs.toString())
        assertEquals(
            listOf(configured.name, configured.id.toString()),
            handler.complete(target, arrayOf("off", "")),
        )
    }

    @Test
    fun helpTopicCreateExplainsTheModifiers() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.COMMAND_PERMISSION))
        val handler = handler()

        handler.execute(target, arrayOf("help", "create"))

        assertTrue(target.messages.any { it.contains("help topic create") }, target.messages.toString())
    }

    @Test
    fun createCompletionOffersEncodingsAndResolutions() {
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(emptyList()))

        val suggested = handler.complete(target, arrayOf("device", "create", "clock", ""))

        assertTrue(suggested.containsAll(listOf("bcd", "analog", "minute", "second")), suggested.toString())
    }

    @Test
    fun placeCompletionOffersSlotIndexes() {
        val configured = device(enabled = false)
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(configured)))

        val suggested = handler.complete(target, arrayOf("device", "place", configured.name, ""))

        assertEquals((0 until configured.outputCount).map { it.toString() }, suggested)
    }

    @Test
    fun deviceDemoRejectsUnknownEncoding() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = administration)

        handler.execute(target, arrayOf("device", "demo", "hex", "world", "0", "70", "0", "x+"))

        assertEquals(null, administration.created)
        assertTrue(target.messages.any { it.contains("hex") }, target.messages.toString())
    }

    @Test
    fun deviceSetAsksForADisabledDevice() {
        val device = device()
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler =
            handler(
                deviceAdministration =
                    object : DeviceAdministration by RecordingDeviceAdministration(listOf(device)) {
                        override fun setOutput(
                            id: UUID,
                            bitIndex: Int,
                            x: Int,
                            y: Int,
                            z: Int,
                        ): RedstoneDevice = throw DeviceMustBeDisabledException(id)
                    },
            )

        handler.execute(target, arrayOf("device", "set", device.id.toString(), "0", "1", "2", "3"))

        assertTrue(target.messages.any { it.contains("禁用") }, target.messages.toString())
    }

    @Test
    fun deviceSetCompletionSkipsEnabledDevices() {
        val disabled = device(enabled = false)
        val enabled = device(enabled = true)
        val editor = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler = handler(deviceAdministration = RecordingDeviceAdministration(listOf(disabled, enabled)))

        val suggested = handler.complete(editor, arrayOf("device", "set", ""))

        assertEquals(listOf(disabled.name, disabled.id.toString()), suggested)
    }

    @Test
    fun deviceSetReportsOutOfRangeBitIndex() {
        val device = device()
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION))
        val handler =
            handler(
                deviceAdministration =
                    object : DeviceAdministration by RecordingDeviceAdministration(listOf(device)) {
                        override fun setOutput(
                            id: UUID,
                            bitIndex: Int,
                            x: Int,
                            y: Int,
                            z: Int,
                        ): RedstoneDevice =
                            throw cn.orzmc.realtime.paper.device
                                .DeviceOutputIndexException(bitIndex, 15)
                    },
            )

        handler.execute(target, arrayOf("device", "set", device.id.toString(), "16", "1", "2", "3"))

        assertTrue(target.messages.any { it.contains("16") && it.contains("15") })
    }

    @Test
    fun deviceCreateIsRateLimitedPerPlayer() {
        val administration = RecordingDeviceAdministration(emptyList())
        val player = UUID.randomUUID()
        var now = 1_000L
        val target = RecordingTarget(setOf(RealTimeCommandHandler.EDIT_PERMISSION), actorId = player)
        val handler = handler(deviceAdministration = administration, nowMillis = { now })

        handler.execute(target, arrayOf("device", "create", "a", "minute", "world", "0", "64", "0", "x+"))
        handler.execute(target, arrayOf("device", "create", "b", "minute", "world", "0", "64", "20", "x+"))

        assertEquals(1, target.messages.count { it.contains("created") }, target.messages.toString())
        assertTrue(target.messages.any { it.contains("秒后再试") })

        now += 4_000
        handler.execute(target, arrayOf("device", "create", "c", "minute", "world", "0", "64", "40", "x+"))
        assertEquals(2, target.messages.count { it.contains("created") })
    }

    private fun handler(
        reload: () -> Result<RuntimeState> = { Result.success(state()) },
        deviceAdministration: DeviceAdministration? = RecordingDeviceAdministration(emptyList()),
        deviceDiagnostics: DeviceDiagnostics? = null,
        cleanupAdministration: DeviceCleanupAdministration? = null,
        deviceRetirement: DeviceRetirementAdministration? = null,
        nowMillis: () -> Long = System::currentTimeMillis,
        redstoneEnabled: Boolean = true,
    ) = RealTimeCommandHandler(
        pluginVersion = "1.2.3",
        currentState = { state(redstoneEnabled) },
        reload = reload,
        deviceAdministration = deviceAdministration,
        deviceDiagnostics = deviceDiagnostics,
        cleanupAdministration = cleanupAdministration,
        deviceRetirement = deviceRetirement,
        nowMillis = nowMillis,
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

    private fun state(redstoneEnabled: Boolean = true): RuntimeState {
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
                        "command.info.redstone-enabled" to "{prefix}redstone {enabled}",
                        "command.help.header" to "{prefix}help",
                        "command.help.time" to "{prefix}help time",
                        "command.help.create" to "{prefix}help create",
                        "command.help.info" to "{prefix}help info",
                        "command.help.toggle" to "{prefix}help toggle",
                        "command.help.remove" to "{prefix}help remove",
                        "command.help.footer" to "{prefix}help footer",
                        "command.help.advanced.header" to "{prefix}help advanced",
                        "command.help.place" to "{prefix}help place",
                        "command.help.status" to "{prefix}help status",
                        "command.help.cleanup" to "{prefix}help cleanup",
                        "command.help.reload" to "{prefix}help reload",
                        "command.help.topic.create" to "{prefix}help topic create",
                        "command.help.topic.place" to "{prefix}help topic place",
                        "command.help.topic.cleanup" to "{prefix}help topic cleanup",
                        "command.device.create.built" to "{prefix}created {name} {id} {count} {display} {state}",
                        "command.device.create.manual-hint" to "{prefix}manual {name} {max}",
                        "command.device.create.invalid-modifier" to "{prefix}bad modifier {value}",
                        "command.device.placement-required" to "{prefix}needs a player",
                        "command.device.ambiguous" to "{prefix}ambiguous {name} {count}",
                        "command.device.place.world-mismatch" to "{prefix}world mismatch {world} {current}",
                        "command.device.warn.redstone-disabled" to "{prefix}warn redstone disabled",
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
                        "command.device.info.resolution" to "{prefix}resolution {resolution}",
                        "command.device.info.encoding" to "{prefix}encoding {encoding}",
                        "command.device.info.outputs" to "{prefix}outputs {count}",
                        "command.device.info.output" to "{prefix}bit {bit} at {x}, {y}, {z}",
                        "command.device.state.enabled" to "enabled",
                        "command.device.state.disabled" to "disabled",
                        "command.device.create.success" to "{prefix}已创建 {name} {id} {count}",
                        "command.device.create.invalid-resolution" to "{prefix}分辨率 {value}",
                        "command.device.create.invalid-encoding" to "{prefix}编码 {value}",
                        "command.device.demo.success" to "{prefix}教学示例 {name} {count} {display}",
                        "command.device.demo.hint" to "{prefix}提示：先清场",
                        "command.device.create.limit" to "{prefix}上限 {limit}",
                        "command.device.create.area" to "{prefix}区域 {world} {x} {z}",
                        "command.device.create.conflict" to "{prefix}冲突 {x} {y} {z}",
                        "command.device.set.success" to "{prefix}已移动 {bit} 到 {x} {y} {z}",
                        "command.device.set.must-disable" to "{prefix}请先禁用设备再改槽位 {id}",
                        "command.device.set.index" to "{prefix}范围 {max} 给了 {bit}",
                        "command.device.edit.cooldown" to "{prefix}秒后再试 {seconds}",
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
                        "command.device.cleanup.dismiss.success" to "{prefix}dismiss {name} {id}",
                        "command.device.cleanup.dismiss.has-pending" to "{prefix}pending {id}",
                        "command.device.cleanup.invalid-id" to "{prefix}cleanup invalid {id}",
                        "command.device.cleanup.not-found" to "{prefix}cleanup not found {id}",
                        "command.device.cleanup.error" to "{prefix}cleanup error {reason}",
                        "command.device.remove.success" to "{prefix}retired {name} {job}",
                        "command.device.remove.must-disable" to "{prefix}must disable {id}",
                        "command.device.remove.not-found" to "{prefix}not found {id}",
                        "command.device.remove.error" to "{prefix}remove error {reason}",
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
            redstoneConfiguration = RedstoneConfiguration(enabled = redstoneEnabled),
        )
    }

    private fun placement() = PlayerPlacement("world", 100, 70, 0, LayoutAxis.X_PLUS)

    private fun device(
        resolution: OutputResolution = OutputResolution.MINUTE,
        enabled: Boolean = true,
        id: UUID = UUID.randomUUID(),
    ) = RedstoneDevice(
        id = id,
        name = "clock",
        worldName = "world",
        enabled = enabled,
        outputs =
            (0 until resolution.outputCount).map { bit ->
                DeviceOutput(bitIndex = bit, x = bit * 2, y = 64, z = 0)
            },
        resolution = resolution,
    )

    private class RecordingDeviceAdministration(
        initialDevices: List<RedstoneDevice>,
    ) : DeviceAdministration {
        var created: DeviceCreateRequest? = null
        var movedOutput: Triple<UUID, Int, Triple<Int, Int, Int>>? = null

        override fun create(request: DeviceCreateRequest): RedstoneDevice {
            created = request
            val device =
                RedstoneDevice(
                    id = UUID.randomUUID(),
                    name = request.name,
                    worldName = request.worldName,
                    enabled = false,
                    outputs = request.outputs,
                    resolution = request.resolution,
                    encoding = request.encoding,
                    demoBlocks = request.demoBlocks,
                )
            devices += device
            return device
        }

        override fun setOutput(
            id: UUID,
            bitIndex: Int,
            x: Int,
            y: Int,
            z: Int,
        ): RedstoneDevice {
            movedOutput = Triple(id, bitIndex, Triple(x, y, z))
            val current = devices.first { it.id == id }
            val updated =
                RedstoneDevice(
                    id = current.id,
                    name = current.name,
                    worldName = current.worldName,
                    enabled = current.enabled,
                    outputs = current.outputs.map { if (it.bitIndex == bitIndex) it.copy(x = x, y = y, z = z) else it },
                    resolution = current.resolution,
                )
            devices.replaceAll { if (it.id == id) updated else it }
            return updated
        }

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

    private class RecordingRetirementAdministration(
        val job: DeviceCleanupJob,
        private val failure: RuntimeException? = null,
    ) : DeviceRetirementAdministration {
        val deviceId: UUID = job.deviceId
        var retireCount = 0

        override fun retire(id: UUID): DeviceCleanupJob {
            retireCount += 1
            require(id == deviceId)
            failure?.let { throw it }
            return job
        }
    }

    private class RecordingCleanupAdministration(
        private val jobs: List<DeviceCleanupJob>,
    ) : DeviceCleanupAdministration {
        var retryCount = 0
        var dismissCount = 0

        override fun list(): List<DeviceCleanupJob> = jobs

        override fun retry(jobId: UUID): DeviceCleanupJob {
            retryCount += 1
            return jobs.first { it.id == jobId }
        }

        override fun dismiss(jobId: UUID): DeviceCleanupJob {
            dismissCount += 1
            return jobs.first { it.id == jobId }
        }
    }

    private class RecordingTarget(
        private val permissions: Set<String>,
        override val actorId: UUID? = null,
        override val placement: PlayerPlacement? = null,
    ) : CommandTarget {
        override val actorName: String = if (actorId == null) "console" else "tester"

        val messages = mutableListOf<String>()

        override fun hasPermission(permission: String): Boolean =
            permission in permissions ||
                RealTimeCommandHandler.ADMIN_PERMISSION in permissions ||
                (
                    permission == RealTimeCommandHandler.VIEW_PERMISSION &&
                        permissions.any { it.startsWith("realtime.device.") }
                )

        override fun sendMessage(message: String) {
            messages += message
        }
    }
}
