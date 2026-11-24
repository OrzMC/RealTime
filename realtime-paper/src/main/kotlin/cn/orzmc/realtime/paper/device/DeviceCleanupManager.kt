package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.Lectern
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Executes durable cleanup jobs without loading chunks or crossing Folia
 * region ownership. Region callbacks only inspect/write their own blocks and
 * enqueue detached outcomes; persistence is performed by a global callback.
 */
class DeviceCleanupManager(
    private val server: Server,
    private val scheduler: PaperFoliaScheduler,
    private val stateLock: Any,
    private val stateProvider: () -> RuntimeState,
    private val persistStore: (DeviceStoreSnapshot) -> Unit,
    private val replaceState: (RuntimeState) -> Unit,
    private val logger: Logger,
    private val books: AnalogBookFactory = DeviceBooks,
) : Listener {
    private val inFlight = ConcurrentHashMap.newKeySet<CleanupKey>()
    private val pendingResults = ConcurrentLinkedQueue<CleanupResult>()
    private val drainScheduled = AtomicBoolean(false)

    fun start(plugin: Plugin) {
        server.pluginManager.registerEvents(this, plugin)
        scheduler.repeatGlobal(initialDelayTicks = RETRY_INITIAL_TICKS, periodTicks = RETRY_PERIOD_TICKS) {
            processAllLoadedWorlds()
        }
        scheduler.runGlobal {
            processAllLoadedWorlds()
        }
    }

    fun requestRefresh() {
        scheduler.runGlobal {
            processAllLoadedWorlds()
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val world = event.world
        val chunkX = event.chunk.x
        val chunkZ = event.chunk.z
        scheduler.runGlobal {
            if (attachWorldIdentity(world)) processChunk(world, chunkX, chunkZ)
        }
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        val world = event.world
        scheduler.runGlobal {
            if (attachWorldIdentity(world)) processWorld(world)
        }
    }

    private fun processAllLoadedWorlds() {
        if (pendingResults.isNotEmpty()) scheduleResultDrain()
        server.worlds.forEach { world ->
            if (attachWorldIdentity(world)) processWorld(world)
        }
    }

    private fun processWorld(world: World) {
        val worldId = world.uid
        stateProvider()
            .cleanupJobs
            .asSequence()
            .filter { it.worldId == worldId }
            .forEach { job ->
                job.outputs
                    .asSequence()
                    .filter { it.status == DeviceCleanupStatus.PENDING }
                    .forEach { output -> scheduleOutput(world, job, output) }
            }
    }

    private fun processChunk(
        world: World,
        chunkX: Int,
        chunkZ: Int,
    ) {
        val worldId = world.uid
        stateProvider()
            .cleanupJobs
            .asSequence()
            .filter { it.worldId == worldId }
            .forEach { job ->
                job.outputs
                    .asSequence()
                    .filter { it.status == DeviceCleanupStatus.PENDING }
                    .filter { it.output.x shr 4 == chunkX && it.output.z shr 4 == chunkZ }
                    .forEach { output -> scheduleOutput(world, job, output) }
            }
    }

    private fun attachWorldIdentity(world: World): Boolean =
        synchronized(stateLock) {
            val current = stateProvider()
            val updatedJobs =
                current.cleanupJobs.map { job ->
                    if (job.worldId == null && job.worldName == world.name) {
                        job.copy(worldId = world.uid)
                    } else {
                        job
                    }
                }
            if (updatedJobs == current.cleanupJobs) return@synchronized true
            val updated = current.copy(cleanupJobs = updatedJobs)
            try {
                persistStore(DeviceStoreSnapshot(updated.devices, updated.cleanupJobs))
                replaceState(updated)
                true
            } catch (exception: Exception) {
                logger.warning(
                    "Unable to persist world identity for deferred cleanup in '${world.name}': ${exception.message}",
                )
                false
            }
        }

    private fun scheduleOutput(
        world: World,
        job: DeviceCleanupJob,
        cleanupOutput: DeviceCleanupOutput,
    ) {
        val key = CleanupKey(job.id, cleanupOutput.output.bitIndex)
        if (!inFlight.add(key)) return

        val output = cleanupOutput.output
        val location =
            Location(
                world,
                output.x.toDouble(),
                output.y.toDouble(),
                output.z.toDouble(),
            )
        try {
            scheduler.runRegion(location) { target ->
                var resultQueued = false
                try {
                    val targetWorld = target.world ?: return@runRegion
                    val chunkX = output.x shr 4
                    val chunkZ = output.z shr 4
                    if (!targetWorld.isChunkLoaded(chunkX, chunkZ)) return@runRegion

                    val currentJob =
                        stateProvider()
                            .cleanupJobs
                            .firstOrNull { it.id == job.id }
                            ?: return@runRegion
                    val currentOutput =
                        currentJob.outputs
                            .firstOrNull { it.output.bitIndex == output.bitIndex }
                            ?.takeIf { it.status == DeviceCleanupStatus.PENDING }
                            ?: return@runRegion

                    val block = targetWorld.getBlockAt(output.x, output.y, output.z)
                    val outcome =
                        when (currentJob.encoding) {
                            OutputEncoding.BCD -> clearBinaryOutput(block)
                            OutputEncoding.ANALOG -> clearAnalogOutput(block, currentJob.deviceId)
                        }
                    pendingResults.add(
                        CleanupResult(
                            jobId = currentJob.id,
                            bitIndex = currentOutput.output.bitIndex,
                            status = outcome.first,
                            detail = outcome.second,
                        ),
                    )
                    resultQueued = true
                    scheduleResultDrain()
                } catch (exception: Exception) {
                    logger.warning(
                        "Deferred cleanup failed for device '${job.deviceName}' output ${output.bitIndex}: ${exception.message}",
                    )
                } finally {
                    if (!resultQueued) inFlight.remove(key)
                }
            }
        } catch (exception: Exception) {
            inFlight.remove(key)
            logger.warning(
                "Unable to schedule deferred cleanup for device '${job.deviceName}' output ${output.bitIndex}: ${exception.message}",
            )
        }
    }

    /**
     * BCD slots hold power sources, so cleanup only removes a redstone block.
     *
     * @return the cleanup status and an optional human-readable detail
     */
    private fun clearBinaryOutput(block: Block): Pair<DeviceCleanupStatus, String?> =
        when (block.type) {
            Material.AIR -> DeviceCleanupStatus.CLEARED to null
            Material.REDSTONE_BLOCK -> {
                block.setType(Material.AIR, true)
                DeviceCleanupStatus.CLEARED to null
            }
            else ->
                DeviceCleanupStatus.NEEDS_ATTENTION to
                    "Expected AIR or REDSTONE_BLOCK, found ${block.type}"
        }

    /**
     * Analog slots are lecterns installed by the plugin, recognised by the book
     * they hold. A lectern that no longer carries this device's book is left
     * alone and reported instead of being destroyed.
     *
     * @return the cleanup status and an optional human-readable detail
     */
    private fun clearAnalogOutput(
        block: Block,
        deviceId: UUID,
    ): Pair<DeviceCleanupStatus, String?> {
        if (block.type == Material.AIR) return DeviceCleanupStatus.CLEARED to null
        if (block.type != Material.LECTERN) {
            return DeviceCleanupStatus.NEEDS_ATTENTION to
                "Expected AIR or LECTERN, found ${block.type}"
        }
        val lectern = block.getState(false) as? Lectern
        if (lectern == null || !books.holdsDeviceBook(lectern.inventory, deviceId)) {
            return DeviceCleanupStatus.NEEDS_ATTENTION to
                "Lectern does not hold the RealTime book for this device, left untouched"
        }
        block.setType(Material.AIR, true)
        return DeviceCleanupStatus.CLEARED to null
    }

    private fun scheduleResultDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            scheduler.runGlobal {
                drainResults()
            }
        } catch (exception: Exception) {
            drainScheduled.set(false)
            logger.warning("Unable to schedule deferred cleanup result persistence: ${exception.message}")
        }
    }

    private fun drainResults() {
        val batch = mutableListOf<CleanupResult>()
        while (true) {
            val result = pendingResults.poll() ?: break
            batch += result
        }

        try {
            if (batch.isEmpty()) return
            synchronized(stateLock) {
                val current = stateProvider()
                val resultsByKey = batch.associateBy { CleanupKey(it.jobId, it.bitIndex) }
                val updatedJobs =
                    current.cleanupJobs.mapNotNull { job ->
                        val updatedOutputs =
                            job.outputs.map { output ->
                                val result = resultsByKey[CleanupKey(job.id, output.output.bitIndex)]
                                if (result == null || output.status != DeviceCleanupStatus.PENDING) {
                                    output
                                } else {
                                    DeviceCleanupOutput(output.output, result.status, result.detail)
                                }
                            }
                        if (updatedOutputs.all { it.status == DeviceCleanupStatus.CLEARED }) {
                            null
                        } else {
                            job.copy(outputs = updatedOutputs)
                        }
                    }
                if (updatedJobs != current.cleanupJobs) {
                    val updated = current.copy(cleanupJobs = updatedJobs)
                    try {
                        persistStore(DeviceStoreSnapshot(updated.devices, updated.cleanupJobs))
                        replaceState(updated)
                    } catch (exception: Exception) {
                        // The blocks may already be clear. Leave the durable job pending;
                        // the next scan retries idempotently and observes power 0.
                        logger.warning("Unable to persist deferred cleanup progress: ${exception.message}")
                    }
                }
            }
        } finally {
            batch.forEach { inFlight.remove(CleanupKey(it.jobId, it.bitIndex)) }
            drainScheduled.set(false)
            if (pendingResults.isNotEmpty()) scheduleResultDrain()
        }
    }

    private data class CleanupKey(
        val jobId: UUID,
        val bitIndex: Int,
    )

    private data class CleanupResult(
        val jobId: UUID,
        val bitIndex: Int,
        val status: DeviceCleanupStatus,
        val detail: String?,
    )

    private companion object {
        const val RETRY_INITIAL_TICKS = 20L
        const val RETRY_PERIOD_TICKS = 100L
    }
}
