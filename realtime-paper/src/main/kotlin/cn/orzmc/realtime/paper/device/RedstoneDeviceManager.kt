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
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
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
 * Minute-resolution devices are only rewritten when the minute changes;
 * second-resolution devices are rewritten on every tick of the one-second
 * refresh period, and only bits whose power actually changed reach the world.
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
        scheduler.repeatGlobal(initialDelayTicks = 20, periodTicks = 20) {
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
                    outputPower = state.redstoneConfiguration.outputPower,
                )
            }
    }

    private fun refreshCurrentFrame(force: Boolean) {
        val state = stateProvider()
        val snapshot = state.timeService.snapshot()
        val epochSecond = snapshot.epochSecond
        val epochMinute = epochSecond / SECONDS_PER_MINUTE

        val secondChanged = force || lastSecondEpoch != epochSecond
        val minuteChanged = force || lastMinuteEpoch != epochMinute
        if (!secondChanged && !minuteChanged) return

        lastSecondEpoch = epochSecond
        lastMinuteEpoch = epochMinute
        if (!force && (!state.configuration.generalEnabled || !state.redstoneConfiguration.enabled)) return

        val resolutions =
            buildSet {
                if (secondChanged) add(OutputResolution.SECOND)
                if (minuteChanged) add(OutputResolution.MINUTE)
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
            .forEach { device ->
                val world = server.getWorld(device.worldName)
                if (world == null) {
                    if (warnedWorlds.add(device.worldName)) {
                        logger.warning(
                            "RealTime device '${device.name}' references an unloaded or missing world " +
                                device.worldName + ".",
                        )
                    }
                    return@forEach
                }
                val scheduledBits = frame.bits(device.resolution)
                device.outputs.forEach { output ->
                    val location =
                        Location(
                            world,
                            output.x.toDouble(),
                            output.y.toDouble(),
                            output.z.toDouble(),
                        )
                    scheduler.runRegion(location) { target ->
                        val targetWorld = target.world ?: return@runRegion
                        val chunkX = output.x shr 4
                        val chunkZ = output.z shr 4
                        if (!targetWorld.isChunkLoaded(chunkX, chunkZ)) {
                            unloadedChunkSkips.incrementAndGet()
                            return@runRegion
                        }

                        val currentState = stateProvider()
                        val currentDevice = currentState.devices.firstOrNull { it.id == device.id }
                        val outputStillConfigured =
                            currentDevice?.outputs?.any { currentOutput ->
                                currentOutput.bitIndex == output.bitIndex &&
                                    currentOutput.x == output.x &&
                                    currentOutput.y == output.y &&
                                    currentOutput.z == output.z
                            } == true
                        if (currentDevice == null || !outputStillConfigured) staleOutputUpdates.incrementAndGet()
                        val effectiveBits =
                            if (currentState === state) {
                                scheduledBits
                            } else {
                                BcdTimeEncoder
                                    .encode(currentState.timeService.now().toLocalTime())
                                    .bits(device.resolution)
                            }
                        applyOutput(
                            world = targetWorld,
                            device = currentDevice ?: device,
                            output = output,
                            powered =
                                outputStillConfigured &&
                                    currentState.isPowered(currentDevice, output.bitIndex, effectiveBits),
                            outputPower = currentState.redstoneConfiguration.outputPower,
                        )
                    }
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
        outputPower: Int,
    ) {
        outputChecks.incrementAndGet()
        try {
            val block = world.getBlockAt(output.x, output.y, output.z)
            if (block.type != Material.REDSTONE_WIRE) {
                invalidOutputBlocks.incrementAndGet()
                val warningKey = "${device.id}:${output.bitIndex}"
                if (warnedOutputs.add(warningKey)) {
                    logger.warning(
                        "RealTime device '${device.name}' output ${output.bitIndex} at " +
                            "${world.name}:${output.x},${output.y},${output.z} is not redstone wire.",
                    )
                }
                return
            }

            val wire = block.blockData as? RedstoneWire
            if (wire == null) {
                invalidOutputBlocks.incrementAndGet()
                return
            }
            val targetPower = if (powered) outputPower else 0
            if (wire.power == targetPower) {
                unchangedOutputs.incrementAndGet()
                return
            }

            wire.power = targetPower
            block.setBlockData(wire, true)
            powerChanges.incrementAndGet()
        } catch (exception: Exception) {
            writeFailures.incrementAndGet()
            logger.warning(
                "RealTime device '${device.name}' output ${output.bitIndex} update failed: ${exception.message}",
            )
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
