package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.BcdHhmmEncoder
import cn.orzmc.realtime.core.device.DeviceOutput
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
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Updates configured BCD outputs without loading chunks.
 *
 * Global work calculates the current frame. Every block read/write is dispatched
 * to the owning region. Chunk-load events refresh outputs that were previously
 * skipped because their chunks were unloaded.
 */
class RedstoneDeviceManager(
    private val server: Server,
    private val scheduler: PaperFoliaScheduler,
    private val stateProvider: () -> RuntimeState,
    private val logger: Logger,
) : Listener {
    private val warnedOutputs = ConcurrentHashMap.newKeySet<String>()
    private val warnedWorlds = ConcurrentHashMap.newKeySet<String>()
    private var lastMinute: LocalDateTime? = null

    fun start(plugin: Plugin) {
        server.pluginManager.registerEvents(this, plugin)
        scheduler.repeatGlobal(initialDelayTicks = 20, periodTicks = 20) {
            refreshCurrentMinute(force = false)
        }
        scheduler.runGlobal {
            refreshCurrentMinute(force = true)
        }
    }

    fun requestRefresh() {
        scheduler.runGlobal {
            lastMinute = null
            refreshCurrentMinute(force = true)
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val state = stateProvider()
        val world = event.world
        val chunk = event.chunk
        val frame = BcdHhmmEncoder.encode(state.timeService.now().toLocalTime())
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
                    powered =
                        state.configuration.generalEnabled &&
                            state.redstoneConfiguration.enabled &&
                            device.enabled &&
                            frame.bits[output.bitIndex],
                    outputPower = state.redstoneConfiguration.outputPower,
                )
            }
    }

    private fun refreshCurrentMinute(force: Boolean) {
        val state = stateProvider()
        val snapshot = state.timeService.snapshot()
        val localMinute =
            snapshot.zonedDateTime
                .toLocalDateTime()
                .withSecond(0)
                .withNano(0)
        if (!force && localMinute == lastMinute) return
        lastMinute = localMinute

        val frame = BcdHhmmEncoder.encode(snapshot.zonedDateTime.toLocalTime())
        scheduleAllOutputs(state, frame.bits)
    }

    private fun scheduleAllOutputs(
        state: RuntimeState,
        bits: List<Boolean>,
    ) {
        state.devices
            .asSequence()
            .forEach { device ->
                val world = server.getWorld(device.worldName)
                if (world == null) {
                    if (warnedWorlds.add(device.worldName)) {
                        logger.warning(
                            "RealTime device '${device.name}' references an unloaded or missing world " +
                                "${device.worldName}.",
                        )
                    }
                    return@forEach
                }
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
                        if (!targetWorld.isChunkLoaded(chunkX, chunkZ)) return@runRegion

                        val currentState = stateProvider()
                        val currentDevice = currentState.devices.firstOrNull { it.id == device.id }
                        val outputStillConfigured =
                            currentDevice?.outputs?.any { currentOutput ->
                                currentOutput.bitIndex == output.bitIndex &&
                                    currentOutput.x == output.x &&
                                    currentOutput.y == output.y &&
                                    currentOutput.z == output.z
                            } == true
                        val effectiveBits =
                            if (currentState === state) bits else {
                                BcdHhmmEncoder.encode(currentState.timeService.now().toLocalTime()).bits
                            }
                        applyOutput(
                            world = targetWorld,
                            device = currentDevice ?: device,
                            output = output,
                            powered =
                                currentState.configuration.generalEnabled &&
                                    currentState.redstoneConfiguration.enabled &&
                                    currentDevice?.enabled == true &&
                                    outputStillConfigured &&
                                    effectiveBits[output.bitIndex],
                            outputPower = currentState.redstoneConfiguration.outputPower,
                        )
                    }
                }
            }
    }

    private fun applyOutput(
        world: World,
        device: RedstoneDevice,
        output: DeviceOutput,
        powered: Boolean,
        outputPower: Int,
    ) {
        val block = world.getBlockAt(output.x, output.y, output.z)
        if (block.type != Material.REDSTONE_WIRE) {
            val warningKey = "${device.id}:${output.bitIndex}"
            if (warnedOutputs.add(warningKey)) {
                logger.warning(
                    "RealTime device '${device.name}' output ${output.bitIndex} at " +
                        "${world.name}:${output.x},${output.y},${output.z} is not redstone wire.",
                )
            }
            return
        }

        val wire = block.blockData as? RedstoneWire ?: return
        val targetPower = if (powered) outputPower else 0
        if (wire.power == targetPower) return

        wire.power = targetPower
        block.setBlockData(wire, true)
    }
}
