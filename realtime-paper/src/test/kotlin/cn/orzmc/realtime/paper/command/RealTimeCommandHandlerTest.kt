package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupJob
import cn.orzmc.realtime.paper.device.DeviceCleanupOutput
import cn.orzmc.realtime.paper.device.DeviceCleanupStatus
import cn.orzmc.realtime.paper.device.DeviceCreateRequest
import cn.orzmc.realtime.paper.device.DeviceIncompleteException
import cn.orzmc.realtime.paper.device.DeviceNameTakenException
import cn.orzmc.realtime.paper.device.DeviceOutputIndexException
import cn.orzmc.realtime.paper.device.DevicePlacement
import cn.orzmc.realtime.paper.device.DeviceRetirementAdministration
import cn.orzmc.realtime.paper.device.RedstoneConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import org.bukkit.block.BlockFace
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

class RealTimeCommandHandlerTest {
    @Test
    fun `no arguments prints the time and every clock`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = true)))
        val target = target(VIEW, EDIT)
        val handler = handler(administration = administration)

        handler.execute(target, emptyArray())

        assertTrue(target.messages.first().contains("2026-11-23 23:59:00"), target.messages.toString())
        assertTrue(target.messages.any { it.contains("lobby") && it.contains("HHmmss") }, target.messages.toString())
    }

    @Test
    fun `a bare name prints one clock with its slots and next steps`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = true)))
        val target = target(VIEW)
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("lobby"))

        assertTrue(target.messages.any { it.contains("lobby") })
        assertTrue(target.messages.any { it.contains("槽 5") }, target.messages.toString())
        assertTrue(target.messages.any { it.contains("/rtime place lobby") }, target.messages.toString())
    }

    @Test
    fun `an unknown name explains that no such clock exists`() {
        val target = target(VIEW)
        val handler = handler(administration = RecordingDeviceAdministration(emptyList()))

        handler.execute(target, arrayOf("nope"))

        assertTrue(target.messages.single().contains("没有这台钟"))
    }

    @Test
    fun `create builds a second clock that is enabled and decorated by default`() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("create", "lobby"))

        val request = checkNotNull(administration.created)
        assertEquals("lobby", request.name)
        assertEquals(OutputResolution.SECOND, request.resolution)
        assertEquals(6, request.outputs.size)
        assertEquals(true, request.enabled)
        assertEquals(6 * 5, request.demoBlocks.size)
        assertEquals(listOf(100, 102, 104, 106, 108, 110), request.outputs.map { it.x })
    }

    @Test
    fun `create manual leaves every slot unplaced and the clock off`() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("create", "machine", "manual"))

        val request = checkNotNull(administration.created)
        assertTrue(request.outputs.all(DeviceOutput::unplaced))
        assertEquals(false, request.enabled)
        assertTrue(request.demoBlocks.isEmpty())
        assertTrue(target.messages.any { it.contains("/rtime place machine") }, target.messages.toString())
    }

    @Test
    fun `create can take an origin and a facing from the console-readable form`() {
        val administration = RecordingDeviceAdministration(emptyList())
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("create", "wall", "at", "10", "64", "-3", "facing", "west", "minute"))

        val request = checkNotNull(administration.created)
        assertEquals(OutputResolution.MINUTE, request.resolution)
        assertEquals(listOf(10, 8, 6, 4), request.outputs.map { it.x })
    }

    @Test
    fun `create needs a player even when an origin is given`() {
        val target = target(EDIT)
        val handler = handler(administration = RecordingDeviceAdministration(emptyList()))

        handler.execute(target, arrayOf("create", "lobby", "at", "0", "64", "0"))

        assertTrue(target.messages.single().contains("要在游戏里执行"), target.messages.toString())
    }

    @Test
    fun `a duplicate clock name is refused with the name`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = true)))
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("create", "Lobby"))

        assertTrue(target.messages.single().contains("Lobby"), target.messages.toString())
        assertNull(administration.created)
    }

    @Test
    fun `place moves the slot and asks for the lectern to be built right away`() {
        val device = clock("lobby", enabled = false)
        val administration = RecordingDeviceAdministration(listOf(device))
        val placement = RecordingPlacement()
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration, placement = placement)

        handler.execute(target, arrayOf("place", "lobby", "2"))

        val moved = checkNotNull(administration.movedOutput)
        assertEquals("lobby", moved.first)
        assertEquals(2, moved.second)
        assertEquals(Triple(100, 64, 5), moved.third)
        val placed = checkNotNull(placement.placed)
        assertEquals(2, placed.slot.bitIndex)
        assertEquals(device.outputs[2], placed.previous)
        assertEquals(BlockFace.SOUTH, placed.facing)
        assertTrue(target.messages.single().contains("讲台已立起"))
    }

    @Test
    fun `place reports a slot outside the clock`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = false)))
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("place", "lobby", "9"))

        assertTrue(target.messages.single().contains("槽位超出范围"), target.messages.toString())
    }

    @Test
    fun `place refuses a clock that is still running`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = true)))
        val target = target(EDIT, placement = placement())
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("place", "lobby", "1"))

        assertTrue(target.messages.single().contains("先关机"), target.messages.toString())
    }

    @Test
    fun `on and off report the state and name`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = false)))
        val target = target(TOGGLE)
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("on", "lobby"))
        handler.execute(target, arrayOf("off", "lobby"))

        assertTrue(target.messages[0].contains("已开机"))
        assertTrue(target.messages[1].contains("已关机"))
        assertEquals(listOf(true, false), administration.toggles)
    }

    @Test
    fun `on with slots still unplaced says how many are missing`() {
        val device =
            RedstoneDevice(
                id = UUID.randomUUID(),
                name = "machine",
                worldName = "world",
                enabled = false,
                outputs = (0 until 6).map { DeviceOutput(it, 0, 64, 0, unplaced = true) },
                resolution = OutputResolution.SECOND,
            )
        val administration = FailingToggleAdministration(listOf(device))
        val target = target(TOGGLE)
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("on", "machine"))

        assertTrue(target.messages.single().contains("6 个槽位"), target.messages.toString())
    }

    @Test
    fun `remove retires the clock and reports the cleanup job`() {
        val device = clock("lobby", enabled = false)
        val administration = RecordingDeviceAdministration(listOf(device))
        val retirement = RecordingRetirement(DeviceCleanupJob.fromDevice(device, null))
        val target = target(RETIRE)
        val handler = handler(administration = administration, retirement = retirement)

        handler.execute(target, arrayOf("remove", "lobby"))

        assertEquals(1, retirement.retireCount)
        assertTrue(
            target.messages
                .single()
                .contains("清理任务已入队")
                .not(),
        )
        assertTrue(target.messages.single().contains("已拆除"), target.messages.toString())
    }

    @Test
    fun `cleanup lists pending jobs and dismisses them by id`() {
        val device = clock("lobby", enabled = false)
        val job = DeviceCleanupJob.fromDevice(device, null)
        val withAttention =
            job.copy(
                outputs =
                    job.outputs.map {
                        DeviceCleanupOutput(it.output, DeviceCleanupStatus.NEEDS_ATTENTION, "terrain changed")
                    },
            )
        val cleanup = RecordingCleanup(listOf(withAttention))
        val target = target(VIEW, RETIRE)
        val handler = handler(administration = RecordingDeviceAdministration(listOf(device)), cleanup = cleanup)

        handler.execute(target, arrayOf("cleanup"))
        handler.execute(target, arrayOf("cleanup", "dismiss", job.id.toString()))

        assertTrue(target.messages.first().contains("清理队列"), target.messages.toString())
        assertEquals(1, cleanup.dismissCount)
        assertTrue(target.messages.last().contains("已丢弃"), target.messages.toString())
    }

    @Test
    fun `status prints the module state and the counters`() {
        val target = target(VIEW)
        val handler = handler()

        handler.execute(target, arrayOf("status"))

        assertTrue(target.messages.any { it.contains("红石输出模块") }, target.messages.toString())
        assertTrue(target.messages.any { it.contains("输出检查") }, target.messages.toString())
    }

    @Test
    fun `help lists the whole surface in one page`() {
        val target = target(VIEW, EDIT, TOGGLE, RETIRE, ADMIN)
        val handler = handler()

        handler.execute(target, arrayOf("help"))

        assertEquals(11, target.messages.size)
        assertTrue(target.messages.any { it.contains("/rtime create") })
        assertTrue(target.messages.any { it.contains("/rtime place") })
    }

    @Test
    fun `verbs removed in this version point at the new spelling`() {
        val target = target(VIEW)
        val handler = handler()

        listOf("info", "list", "demo", "set", "device").forEach { verb ->
            target.messages.clear()
            handler.execute(target, arrayOf(verb))
            assertTrue(target.messages.single().contains("已经移除"), "$verb -> ${target.messages}")
        }
    }

    @Test
    fun `missing permissions are reported instead of running the command`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = false)))
        val target = target(VIEW)
        val handler = handler(administration = administration)

        handler.execute(target, arrayOf("on", "lobby"))

        assertTrue(target.messages.single().contains("没有权限"))
        assertTrue(administration.toggles.isEmpty())
    }

    @Test
    fun `completion offers verbs, clock names and slot numbers`() {
        val administration = RecordingDeviceAdministration(listOf(clock("lobby", enabled = false)))
        val target = target(VIEW, EDIT)
        val handler = handler(administration = administration)

        assertTrue(handler.complete(target, arrayOf("")).contains("create"))
        assertTrue(handler.complete(target, arrayOf("")).contains("lobby"))
        assertEquals(listOf("lobby"), handler.complete(target, arrayOf("off", "")))
        assertTrue(handler.complete(target, arrayOf("place", "lobby", "")).contains("3"))
        assertTrue(handler.complete(target, arrayOf("create", "wall", "")).contains("manual"))
    }

    /** The real texts, so assertions can look at what an operator actually sees. */
    private fun shippedMessages(): Map<String, String> {
        val yaml =
            org.bukkit.configuration.file
                .YamlConfiguration()
        val stream =
            RealTimeCommandHandler::class.java.getResourceAsStream("/messages.yml")
                ?: error("messages.yml is missing from the plugin resources")
        stream.reader(Charsets.UTF_8).use { yaml.load(it) }
        return yaml
            .getKeys(true)
            .filter { yaml.get(it) is String }
            .associateWith { yaml.get(it) as String }
    }

    private fun clock(
        name: String,
        enabled: Boolean,
    ) = RedstoneDevice(
        id = UUID.randomUUID(),
        name = name,
        worldName = "world",
        enabled = enabled,
        outputs = (0 until 6).map { DeviceOutput(it, it * 2, 64, 0) },
        resolution = OutputResolution.SECOND,
    )

    private fun placement() =
        PlayerPlacement(
            worldName = "world",
            x = 100,
            y = 64,
            z = 5,
            axis = LayoutAxis.X_PLUS,
            lecternFacing = BlockFace.SOUTH,
        )

    private fun target(
        vararg permissions: String,
        placement: PlayerPlacement? = null,
    ) = RecordingTarget(permissions.toSet(), placement = placement)

    private fun handler(
        administration: DeviceAdministration? = null,
        placement: DevicePlacement? = null,
        retirement: DeviceRetirementAdministration? = null,
        cleanup: DeviceCleanupAdministration? = null,
    ) = RealTimeCommandHandler(
        pluginVersion = "0.6.0-SNAPSHOT",
        currentState = { state() },
        reload = { Result.success(state()) },
        deviceAdministration = administration,
        deviceDiagnostics = { snapshot },
        devicePlacement = placement,
        cleanupAdministration = cleanup,
        deviceRetirement = retirement,
        skippedLegacyDevices = { emptyList() },
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
            messages = MessageCatalog(shippedMessages()),
            redstoneConfiguration = RedstoneConfiguration(enabled = true),
        )
    }

    private val snapshot =
        cn.orzmc.realtime.paper.device.RedstoneDiagnosticsSnapshot(
            configuredDevices = 1,
            enabledDevices = 1,
            configuredOutputs = 6,
            outputChecks = 10,
            unloadedChunkSkips = 0,
            staleOutputUpdates = 0,
            invalidOutputBlocks = 0,
            powerChanges = 3,
            unchangedOutputs = 7,
            writeFailures = 0,
        )

    private class RecordingDeviceAdministration(
        initial: List<RedstoneDevice>,
    ) : DeviceAdministration {
        val devices = initial.toMutableList()
        var created: DeviceCreateRequest? = null
        var movedOutput: Triple<String, Int, Triple<Int, Int, Int>>? = null
        val toggles = mutableListOf<Boolean>()

        override fun list(): List<RedstoneDevice> = devices.toList()

        override fun find(id: UUID): RedstoneDevice? = devices.firstOrNull { it.id == id }

        override fun create(request: DeviceCreateRequest): RedstoneDevice {
            if (devices.any { it.name.equals(request.name, ignoreCase = true) }) {
                throw DeviceNameTakenException(request.name)
            }
            created = request
            val device =
                RedstoneDevice(
                    id = UUID.randomUUID(),
                    name = request.name,
                    worldName = request.worldName,
                    enabled = request.enabled,
                    outputs = request.outputs,
                    resolution = request.resolution,
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
            val current = devices.first { it.id == id }
            if (current.enabled) {
                throw cn.orzmc.realtime.paper.device
                    .DeviceMustBeDisabledException(id)
            }
            if (bitIndex !in 0 until current.outputCount) {
                throw DeviceOutputIndexException(bitIndex, current.outputCount - 1)
            }
            movedOutput = Triple(current.name, bitIndex, Triple(x, y, z))
            val updated =
                RedstoneDevice(
                    id = current.id,
                    name = current.name,
                    worldName = current.worldName,
                    enabled = current.enabled,
                    outputs = current.outputs.map { if (it.bitIndex == bitIndex) DeviceOutput(it.bitIndex, x, y, z) else it },
                    resolution = current.resolution,
                    demoBlocks = current.demoBlocks,
                )
            devices[devices.indexOfFirst { it.id == id }] = updated
            return updated
        }

        override fun setEnabled(
            id: UUID,
            enabled: Boolean,
        ): RedstoneDevice {
            val current = devices.first { it.id == id }
            toggles += enabled
            val updated =
                RedstoneDevice(
                    id = current.id,
                    name = current.name,
                    worldName = current.worldName,
                    enabled = enabled,
                    outputs = current.outputs,
                    resolution = current.resolution,
                    demoBlocks = current.demoBlocks,
                )
            devices[devices.indexOfFirst { it.id == id }] = updated
            return updated
        }
    }

    private class FailingToggleAdministration(
        initial: List<RedstoneDevice>,
    ) : DeviceAdministration by RecordingDeviceAdministration(initial) {
        override fun setEnabled(
            id: UUID,
            enabled: Boolean,
        ): RedstoneDevice = throw DeviceIncompleteException(id, 6)
    }

    private data class Placed(
        val slot: DeviceOutput,
        val previous: DeviceOutput?,
        val facing: BlockFace?,
    )

    private class RecordingPlacement : DevicePlacement {
        var placed: Placed? = null

        override fun placeSlotNow(
            deviceId: UUID,
            slot: DeviceOutput,
            previous: DeviceOutput?,
            facing: BlockFace?,
        ) {
            placed = Placed(slot, previous, facing)
        }
    }

    private class RecordingRetirement(
        private val job: DeviceCleanupJob,
    ) : DeviceRetirementAdministration {
        var retireCount = 0

        override fun retire(id: UUID): DeviceCleanupJob {
            retireCount += 1
            return job
        }
    }

    private class RecordingCleanup(
        private val jobs: List<DeviceCleanupJob>,
    ) : DeviceCleanupAdministration {
        var dismissCount = 0

        override fun list(): List<DeviceCleanupJob> = jobs

        override fun retry(jobId: UUID): DeviceCleanupJob = jobs.first { it.id == jobId }

        override fun dismiss(jobId: UUID): DeviceCleanupJob {
            dismissCount += 1
            return jobs.first { it.id == jobId }
        }
    }

    private class RecordingTarget(
        private val permissions: Set<String>,
        override val placement: PlayerPlacement? = null,
    ) : CommandTarget {
        override val actorName: String = "tester"

        override val actorId: UUID? = null

        val messages = mutableListOf<String>()

        override fun hasPermission(permission: String): Boolean =
            permission in permissions ||
                RealTimeCommandHandler.ADMIN_PERMISSION in permissions ||
                // `realtime.command` is a child of every device permission in plugin.yml.
                (permission == RealTimeCommandHandler.COMMAND_PERMISSION && permissions.isNotEmpty())

        override fun sendMessage(message: String) {
            messages += message
        }
    }

    private companion object {
        const val VIEW = RealTimeCommandHandler.VIEW_PERMISSION
        const val EDIT = RealTimeCommandHandler.EDIT_PERMISSION
        const val TOGGLE = RealTimeCommandHandler.TOGGLE_PERMISSION
        const val RETIRE = RealTimeCommandHandler.RETIRE_PERMISSION
        const val ADMIN = RealTimeCommandHandler.ADMIN_PERMISSION
    }
}
