package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.BcdTimeEncoder
import cn.orzmc.realtime.core.device.BcdTimeFrame
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Updates configured BCD outputs without loading chunks.
 *
 * Global work calculates the current frame. Every block read/write is dispatched
 * to the owning region. Chunk-load events refresh outputs that were previously
 * skipped because their chunks were unloaded.
 *
 * A logical 1 is materialised as a `minecraft:redstone_block` in the output slot
 * and a logical 0 as air, so vanilla redstone reacts to the change and downstream
 * circuits keep working. Anything else in a slot is left untouched and reported
 * as an invalid output.
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
    private var lastMinuteEpoch: Long? = null
    private var lastSecondEpoch: Long? = null
    private var lastFullCheckEpoch: Long? = null
    private val lastWritten = ConcurrentHashMap<UUID, WrittenFrame>()

    /** Device state captured when a write batch was dispatched. */
    private data class WrittenFrame(
        val device: RedstoneDevice,
        val bits: List<Boolean>,
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
                    powered = state.isPowered(device, output.bitIndex, frame.bits(device.resolution)),
                )
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
        val desired = frame.bits(device.resolution)
        val previous = lastWritten[device.id]
        val changedIndexes =
            when {
                previous == null || previous.device !== device || previous.bits.size != desired.size ->
                    device.outputs.indices.toList()
                else -> desired.indices.filter { index -> desired[index] != previous.bits[index] }
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
                    val effectiveBits =
                        if (currentState === state) {
                            desired
                        } else {
                            BcdTimeEncoder
                                .encode(currentState.timeService.now().toLocalTime())
                                .bits(device.resolution)
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
                            powered =
                                outputStillConfigured &&
                                    currentState.isPowered(currentDevice, output.bitIndex, effectiveBits),
                        )
                    }
                    recordWritten(device, effectiveBits, indexes)
                }
            }
    }

    /**
     * Marks the slots this batch actually applied so the next diff only schedules
     * real changes. Slots skipped by an unloaded chunk stay unrecorded on purpose.
     */
    private fun recordWritten(
        device: RedstoneDevice,
        bits: List<Boolean>,
        indexes: List<Int>,
    ) {
        lastWritten.compute(device.id) { _, previous ->
            if (previous == null || previous.device !== device || previous.bits.size != bits.size) {
                WrittenFrame(device, bits)
            } else {
                val updated = previous.bits.toMutableList()
                indexes.forEach { index -> updated[index] = bits[index] }
                WrittenFrame(device, updated)
            }
        }
    }

    private fun RuntimeState.isPowered(
        device: RedstoneDevice?,
        bitIndex: Int,
        bits: List<Boolean>,
    ): Boolean {
        if (device == null) return false
        if (!configuration.generalEnabled || !redstoneConfiguration.enabled || !device.enabled) return false
        return bitIndex in bits.indices && bits[bitIndex]
    }

    private fun applyOutput(
        world: World,
        device: RedstoneDevice,
        output: DeviceOutput,
        powered: Boolean,
    ) {
        outputChecks.incrementAndGet()
        try {
            val block = world.getBlockAt(output.x, output.y, output.z)
            val expectedType = if (powered) POWERED_TYPE else Material.AIR
            val currentType = block.type
            if (currentType == expectedType) {
                unchangedOutputs.incrementAndGet()
                return
            }
            if (currentType != Material.AIR && currentType != POWERED_TYPE) {
                invalidOutputBlocks.incrementAndGet()
                val warningKey = "${device.id}:${output.bitIndex}"
                if (warnedOutputs.add(warningKey)) {
                    logger.warning(
                        "RealTime device '${device.name}' output ${output.bitIndex} at " +
                            "${world.name}:${output.x},${output.y},${output.z} is $currentType, " +
                            "expected AIR or $POWERED_TYPE.",
                    )
                }
                return
            }

            // Physics is required here: the slot becomes a real power source so
            // vanilla redstone notifies and powers the surrounding circuit.
            block.setType(expectedType, true)
            powerChanges.incrementAndGet()
        } catch (exception: Exception) {
            writeFailures.incrementAndGet()
            logger.warning(
                "RealTime device '${device.name}' output ${output.bitIndex} update failed: ${exception.message}",
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
