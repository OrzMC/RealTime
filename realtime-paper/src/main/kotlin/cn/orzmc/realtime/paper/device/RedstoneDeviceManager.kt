package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.AnalogScale
import cn.orzmc.realtime.core.device.BcdTimeEncoder
import cn.orzmc.realtime.core.device.BcdTimeFrame
import cn.orzmc.realtime.core.device.DemoBlock
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Lectern
import org.bukkit.block.data.BlockData
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Updates configured device outputs without loading chunks.
 *
 * Global work calculates the current frame. Every block read/write is dispatched
 * to the owning region. Chunk-load events refresh outputs that were previously
 * skipped because their chunks were unloaded.
 *
 * ## Output encodings
 *
 * A [OutputEncoding.BCD] slot materialises one bit: `minecraft:redstone_block`
 * for a logical 1 and air for a logical 0, so vanilla redstone reacts to the change.
 *
 * An [OutputEncoding.ANALOG] slot is a lectern holding the plugin's 15-page book;
 * the page index is the digit, so a comparator behind the lectern reads
 * `digit + 1`. Turning the device off removes the book, which drops the read to
 * level 0 ([AnalogScale.NO_BOOK]).
 *
 * Anything else in a slot is left untouched and reported as an invalid output:
 * the manager never overwrites blocks or books it does not own.
 *
 * The global task runs every tick and only compares the wall-clock second with the
 * last one it acted on, so a change is picked up on the first tick after it
 * happens (<= 50 ms at 20 TPS) instead of at an arbitrary phase of a one-second
 * task. Scheduling only happens when a frame actually changed.
 *
 * Writes are diffed per device: only slots whose desired state changed are
 * dispatched, grouped per chunk so a 24-output device costs two region tasks
 * instead of twenty-four. A full re-check of every slot runs every
 * [FULL_CHECK_INTERVAL_SECONDS] so manual edits and invalid blocks are still
 * noticed without paying that cost every second.
 */
class RedstoneDeviceManager(
    private val server: Server,
    private val scheduler: PaperFoliaScheduler,
    private val stateProvider: () -> RuntimeState,
    private val logger: Logger,
    private val books: AnalogBookFactory = DeviceBooks,
    private val blockDataFactory: (String) -> BlockData = { data -> Bukkit.createBlockData(data) },
) : Listener,
    DeviceDiagnostics {
    private val warnedOutputs = ConcurrentHashMap.newKeySet<String>()
    private val warnedWorlds = ConcurrentHashMap.newKeySet<String>()
    private val outputChecks = AtomicLong()
    private val unloadedChunkSkips = AtomicLong()
    private val staleOutputUpdates = AtomicLong()
    private val invalidOutputBlocks = AtomicLong()
    private val powerChanges = AtomicLong()
    private val unchangedOutputs = AtomicLong()
    private val writeFailures = AtomicLong()
    private val lecternPlacements = AtomicLong()
    private val bookWrites = AtomicLong()
    private val demoBlocksPlaced = AtomicLong()
    private val demoBlocksCleared = AtomicLong()
    private var lastMinuteEpoch: Long? = null
    private var lastSecondEpoch: Long? = null
    private var lastFullCheckEpoch: Long? = null
    private val lastWritten = ConcurrentHashMap<UUID, WrittenFrame>()
    private val demoState = ConcurrentHashMap<UUID, Boolean>()

    /** Device state captured when a write batch was dispatched. */
    private data class WrittenFrame(
        val device: RedstoneDevice,
        val values: List<Int>,
    )

    override fun snapshot(): RedstoneDiagnosticsSnapshot {
        val devices = stateProvider().devices
        return RedstoneDiagnosticsSnapshot(
            configuredDevices = devices.size,
            enabledDevices = devices.count { it.enabled },
            configuredOutputs = devices.sumOf { it.outputs.size },
            outputChecks = outputChecks.get(),
            unloadedChunkSkips = unloadedChunkSkips.get(),
            staleOutputUpdates = staleOutputUpdates.get(),
            invalidOutputBlocks = invalidOutputBlocks.get(),
            powerChanges = powerChanges.get(),
            unchangedOutputs = unchangedOutputs.get(),
            writeFailures = writeFailures.get(),
            lecternPlacements = lecternPlacements.get(),
            bookWrites = bookWrites.get(),
            demoBlocksPlaced = demoBlocksPlaced.get(),
            demoBlocksCleared = demoBlocksCleared.get(),
        )
    }

    fun start(plugin: Plugin) {
        server.pluginManager.registerEvents(this, plugin)
        scheduler.repeatGlobal(initialDelayTicks = REFRESH_PERIOD_TICKS, periodTicks = REFRESH_PERIOD_TICKS) {
            refreshCurrentFrame(force = false)
        }
        scheduler.runGlobal {
            refreshCurrentFrame(force = true)
        }
    }

    fun requestRefresh() {
        scheduler.runGlobal {
            lastMinuteEpoch = null
            lastSecondEpoch = null
            lastFullCheckEpoch = null
            lastWritten.clear()
            demoState.clear()
            refreshCurrentFrame(force = true)
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val state = stateProvider()
        val world = event.world
        val chunk = event.chunk
        val frame = BcdTimeEncoder.encode(state.timeService.now().toLocalTime())
        state.devices
            .asSequence()
            .filter { it.worldName == world.name }
            .flatMap { device -> device.outputs.asSequence().map { device to it } }
            .filter { (_, output) ->
                output.x shr 4 == chunk.x && output.z shr 4 == chunk.z
            }.forEach { (device, output) ->
                applyOutput(
                    world = world,
                    device = device,
                    output = output,
                    value = state.valuesFor(device, frame)[output.bitIndex],
                )
            }
        state.devices
            .asSequence()
            .filter { it.worldName == world.name && it.demoBlocks.isNotEmpty() }
            .forEach { device ->
                device.demoBlocks
                    .filter { it.x shr 4 == chunk.x && it.z shr 4 == chunk.z }
                    .forEach { block -> applyDemoBlock(world, device, block, present = device.enabled) }
            }
    }

    private fun refreshCurrentFrame(force: Boolean) {
        val state = stateProvider()
        val snapshot = state.timeService.snapshot()
        val epochSecond = snapshot.epochSecond
        val epochMinute = epochSecond / SECONDS_PER_MINUTE

        val previousCheck = lastFullCheckEpoch
        val fullCheckDue = force || previousCheck == null || epochSecond - previousCheck >= FULL_CHECK_INTERVAL_SECONDS
        val secondChanged = force || lastSecondEpoch != epochSecond
        val minuteChanged = force || lastMinuteEpoch != epochMinute
        if (!secondChanged && !minuteChanged && !fullCheckDue) return

        lastSecondEpoch = epochSecond
        lastMinuteEpoch = epochMinute
        if (fullCheckDue) lastFullCheckEpoch = epochSecond
        if (!force && (!state.configuration.generalEnabled || !state.redstoneConfiguration.enabled)) return

        if (force || fullCheckDue) lastWritten.clear()
        val resolutions =
            buildSet {
                if (secondChanged || fullCheckDue) add(OutputResolution.SECOND)
                if (minuteChanged || fullCheckDue) add(OutputResolution.MINUTE)
            }
        val frame = BcdTimeEncoder.encode(snapshot.zonedDateTime.toLocalTime())
        scheduleOutputs(state, frame, resolutions, includeDisabled = force)
        if (force || fullCheckDue) scheduleDemoBlocks(state, includeDisabled = force)
    }

    /**
     * Places or removes the display blocks of a demo build.
     *
     * Demo blocks are static, so this only runs on a forced refresh (enable,
     * disable, reload, start) and on the 30 s full re-check; the per-device
     * [demoState] cache keeps a settled demo at one region task per change.
     */
    private fun scheduleDemoBlocks(
        state: RuntimeState,
        includeDisabled: Boolean,
    ) {
        state.devices
            .asSequence()
            .filter { it.demoBlocks.isNotEmpty() }
            .filter { includeDisabled || it.enabled }
            .forEach { device ->
                val present = state.isDemoPresent(device)
                if (demoState[device.id] == present) return@forEach
                val world = server.getWorld(device.worldName) ?: return@forEach
                device.demoBlocks
                    .groupBy { (it.x shr 4) to (it.z shr 4) }
                    .forEach { (chunk, blocks) ->
                        val anchor = blocks.first()
                        val location =
                            Location(world, anchor.x.toDouble(), anchor.y.toDouble(), anchor.z.toDouble())
                        scheduler.runRegion(location) { target ->
                            val targetWorld = target.world ?: return@runRegion
                            if (!targetWorld.isChunkLoaded(chunk.first, chunk.second)) return@runRegion
                            val current = stateProvider()
                            val currentDevice = current.devices.firstOrNull { it.id == device.id } ?: return@runRegion
                            val currentPresent = current.isDemoPresent(currentDevice)
                            blocks.forEach { block ->
                                applyDemoBlock(targetWorld, currentDevice, block, currentPresent)
                            }
                            demoState[currentDevice.id] = currentPresent
                        }
                    }
            }
    }

    private fun RuntimeState.isDemoPresent(device: RedstoneDevice): Boolean =
        device.enabled && configuration.generalEnabled && redstoneConfiguration.enabled

    private fun applyDemoBlock(
        world: World,
        device: RedstoneDevice,
        block: DemoBlock,
        present: Boolean,
    ) {
        val expected =
            try {
                blockDataFactory(block.data)
            } catch (exception: IllegalArgumentException) {
                logger.warning("RealTime device '${device.name}' demo block ${block.data} is invalid: ${exception.message}")
                return
            }
        val target = world.getBlockAt(block.x, block.y, block.z)
        val current = target.type
        try {
            if (present) {
                when {
                    current == expected.material -> Unit
                    current == Material.AIR -> {
                        target.setBlockData(expected, true)
                        demoBlocksPlaced.incrementAndGet()
                    }
                    else -> invalidDemoBlock(device, block, world, "is $current, expected AIR or ${expected.material}")
                }
            } else {
                when {
                    current == Material.AIR -> Unit
                    current == expected.material -> {
                        target.setType(Material.AIR, true)
                        demoBlocksCleared.incrementAndGet()
                    }
                    else -> invalidDemoBlock(device, block, world, "is $current, expected AIR or ${expected.material}")
                }
            }
        } catch (exception: Exception) {
            writeFailures.incrementAndGet()
            logger.warning(
                "RealTime device '${device.name}' demo block at " +
                    "${world.name}:${block.x},${block.y},${block.z} failed: ${exception.message}",
            )
        }
    }

    private fun invalidDemoBlock(
        device: RedstoneDevice,
        block: DemoBlock,
        world: World,
        detail: String,
    ) {
        invalidOutputBlocks.incrementAndGet()
        if (warnedOutputs.add("demo:${device.id}:${block.x},${block.y},${block.z}")) {
            logger.warning(
                "RealTime device '${device.name}' demo block at " +
                    "${world.name}:${block.x},${block.y},${block.z} $detail.",
            )
        }
    }

    private fun scheduleOutputs(
        state: RuntimeState,
        frame: BcdTimeFrame,
        resolutions: Set<OutputResolution>,
        includeDisabled: Boolean,
    ) {
        state.devices
            .asSequence()
            .filter { includeDisabled || it.enabled }
            .filter { it.resolution in resolutions }
            .forEach { device -> scheduleDeviceOutputs(state, frame, device) }
    }

    private fun scheduleDeviceOutputs(
        state: RuntimeState,
        frame: BcdTimeFrame,
        device: RedstoneDevice,
    ) {
        val desired = state.valuesFor(device, frame)
        val previous = lastWritten[device.id]
        val changedIndexes =
            when {
                previous == null || previous.device !== device || previous.values.size != desired.size ->
                    device.outputs.indices.toList()
                else -> desired.indices.filter { index -> desired[index] != previous.values[index] }
            }
        if (changedIndexes.isEmpty()) return

        val world =
            server.getWorld(device.worldName)
                ?: run {
                    if (warnedWorlds.add(device.worldName)) {
                        logger.warning(
                            "RealTime device '${device.name}' references an unloaded or missing world " +
                                device.worldName + ".",
                        )
                    }
                    return
                }

        changedIndexes
            .groupBy { index ->
                val output = device.outputs[index]
                (output.x shr 4) to (output.z shr 4)
            }.forEach { (chunk, indexes) ->
                val anchor = device.outputs[indexes.first()]
                val location =
                    Location(
                        world,
                        anchor.x.toDouble(),
                        anchor.y.toDouble(),
                        anchor.z.toDouble(),
                    )
                scheduler.runRegion(location) { target ->
                    val targetWorld = target.world ?: return@runRegion
                    if (!targetWorld.isChunkLoaded(chunk.first, chunk.second)) {
                        // Do not record these slots as written: keep them queued for
                        // the next change, the chunk-load refresh or the full re-check.
                        unloadedChunkSkips.addAndGet(indexes.size.toLong())
                        return@runRegion
                    }

                    val currentState = stateProvider()
                    val currentDevice = currentState.devices.firstOrNull { it.id == device.id }
                    val effective =
                        if (currentState === state) {
                            desired
                        } else {
                            currentState.valuesFor(
                                currentDevice ?: device,
                                BcdTimeEncoder.encode(currentState.timeService.now().toLocalTime()),
                            )
                        }
                    indexes.forEach { index ->
                        val output = device.outputs[index]
                        val outputStillConfigured =
                            currentDevice?.outputs?.any { currentOutput ->
                                currentOutput.bitIndex == output.bitIndex &&
                                    currentOutput.x == output.x &&
                                    currentOutput.y == output.y &&
                                    currentOutput.z == output.z
                            } == true
                        if (currentDevice == null || !outputStillConfigured) staleOutputUpdates.incrementAndGet()
                        applyOutput(
                            world = targetWorld,
                            device = currentDevice ?: device,
                            output = output,
                            value =
                                if (outputStillConfigured) {
                                    effective.getOrElse(output.bitIndex) { (currentDevice ?: device).encoding.offValue }
                                } else {
                                    (currentDevice ?: device).encoding.offValue
                                },
                        )
                    }
                    recordWritten(device, effective, indexes)
                }
            }
    }

    /**
     * Marks the slots this batch actually applied so the next diff only schedules
     * real changes. Slots skipped by an unloaded chunk stay unrecorded on purpose.
     */
    private fun recordWritten(
        device: RedstoneDevice,
        values: List<Int>,
        indexes: List<Int>,
    ) {
        lastWritten.compute(device.id) { _, previous ->
            if (previous == null || previous.device !== device || previous.values.size != values.size) {
                WrittenFrame(device, values)
            } else {
                val updated = previous.values.toMutableList()
                indexes.forEach { index -> updated[index] = values[index] }
                WrittenFrame(device, updated)
            }
        }
    }

    /** Desired slot values for [device], honouring the enable switches. */
    private fun RuntimeState.valuesFor(
        device: RedstoneDevice,
        frame: BcdTimeFrame,
    ): List<Int> {
        val off = !configuration.generalEnabled || !redstoneConfiguration.enabled || !device.enabled
        return if (off) {
            device.encoding.offValues(device.resolution)
        } else {
            frame.values(device.encoding, device.resolution)
        }
    }

    private fun applyOutput(
        world: World,
        device: RedstoneDevice,
        output: DeviceOutput,
        value: Int,
    ) {
        outputChecks.incrementAndGet()
        try {
            when (device.encoding) {
                OutputEncoding.BCD -> applyBinaryOutput(world, device, output, powered = value != 0)
                OutputEncoding.ANALOG -> applyAnalogOutput(world, device, output, value)
            }
        } catch (exception: Exception) {
            writeFailures.incrementAndGet()
            logger.warning(
                "RealTime device '${device.name}' output ${output.bitIndex} update failed: ${exception.message}",
            )
        }
    }

    private fun applyBinaryOutput(
        world: World,
        device: RedstoneDevice,
        output: DeviceOutput,
        powered: Boolean,
    ) {
        val block = world.getBlockAt(output.x, output.y, output.z)
        val expectedType = if (powered) POWERED_TYPE else Material.AIR
        val currentType = block.type
        if (currentType == expectedType) {
            unchangedOutputs.incrementAndGet()
            return
        }
        if (currentType != Material.AIR && currentType != POWERED_TYPE) {
            invalidOutputBlock(
                device,
                output,
                world,
                "is $currentType, expected AIR or $POWERED_TYPE",
            )
            return
        }

        // Physics is required here: the slot becomes a real power source so
        // vanilla redstone notifies and powers the surrounding circuit.
        block.setType(expectedType, true)
        powerChanges.incrementAndGet()
    }

    /**
     * Drives one analog slot.
     *
     * [value] is the digit (`0`–`9`) or [AnalogScale.NO_BOOK] when the device is
     * disabled. The slot must be air (the lectern is placed for the operator) or
     * a lectern holding this device's book; a lectern with a foreign book keeps
     * its contents and is reported instead of being overwritten.
     */
    private fun applyAnalogOutput(
        world: World,
        device: RedstoneDevice,
        output: DeviceOutput,
        value: Int,
    ) {
        val block = world.getBlockAt(output.x, output.y, output.z)
        val currentType = block.type
        val blank = value == AnalogScale.NO_BOOK
        if (currentType == Material.AIR && blank) {
            unchangedOutputs.incrementAndGet()
            return
        }
        when (currentType) {
            Material.AIR -> {
                block.setType(Material.LECTERN, true)
                lecternPlacements.incrementAndGet()
            }
            Material.LECTERN -> Unit
            else -> {
                invalidOutputBlock(
                    device,
                    output,
                    world,
                    "is $currentType, expected AIR or ${Material.LECTERN}",
                )
                return
            }
        }

        val lectern =
            block.getState(false) as? Lectern
                ?: run {
                    invalidOutputBlock(device, output, world, "does not expose a lectern")
                    return
                }
        val inventory = lectern.inventory
        if (!books.isSlotEmpty(inventory) && !books.holdsDeviceBook(inventory, device.id)) {
            invalidOutputBlock(device, output, world, "holds a foreign book, expected the RealTime book")
            return
        }

        if (blank) {
            // A disabled device leaves its slot empty, exactly like a BCD slot:
            // level 0 then comes from air rather than from a blank lectern, and a
            // moved slot cannot leave an orphaned lectern behind.
            // Clear the book first so it is not dropped as an item entity.
            if (!books.isSlotEmpty(inventory)) {
                books.clear(inventory)
                bookWrites.incrementAndGet()
            }
            block.setType(Material.AIR, true)
            powerChanges.incrementAndGet()
            return
        }

        if (!AnalogScale.isDigit(value)) {
            invalidOutputBlock(device, output, world, "carries $value, expected a digit 0..${AnalogScale.MAX_DIGIT}")
            return
        }

        val page = AnalogScale.pageForDigit(value)
        val bookChanged = !books.holdsDeviceBook(inventory, device.id)
        if (bookChanged) {
            books.install(inventory, device.id)
            bookWrites.incrementAndGet()
        }
        // Writing the book resets the page, so a fresh page index is always required.
        if (bookChanged || lectern.page != page) {
            lectern.setPage(page)
            powerChanges.incrementAndGet()
        } else {
            unchangedOutputs.incrementAndGet()
        }
    }

    private fun invalidOutputBlock(
        device: RedstoneDevice,
        output: DeviceOutput,
        world: World,
        detail: String,
    ) {
        invalidOutputBlocks.incrementAndGet()
        if (warnedOutputs.add("${device.id}:${output.bitIndex}")) {
            logger.warning(
                "RealTime device '${device.name}' output ${output.bitIndex} at " +
                    "${world.name}:${output.x},${output.y},${output.z} $detail.",
            )
        }
    }

    private companion object {
        const val REFRESH_PERIOD_TICKS = 1L
        const val SECONDS_PER_MINUTE = 60L
        const val FULL_CHECK_INTERVAL_SECONDS = 30L
        val POWERED_TYPE: Material = Material.REDSTONE_BLOCK
    }
}
