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
                // Index over the whole list: scheduleDemo and the result drain
                // address display entries by their position in the stored job, so
                // filtering first would shift the index of every later entry.
                job.display.forEachIndexed { index, demo ->
                    if (demo.status == DeviceCleanupStatus.PENDING) scheduleDemo(world, job, demo, index)
                }
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
                job.display.forEachIndexed { index, demo ->
                    if (demo.status == DeviceCleanupStatus.PENDING &&
                        demo.block.x shr 4 == chunkX &&
                        demo.block.z shr 4 == chunkZ
                    ) {
                        scheduleDemo(world, job, demo, index)
                    }
                }
            }
    }

    /**
     * Clears one display block of a demo build.
     *
     * Only a block whose type still matches what the demo placed is removed; a
     * player build that replaced it is reported instead of destroyed.
     */
    private fun scheduleDemo(
        world: World,
        job: DeviceCleanupJob,
        demo: DeviceCleanupDemo,
        index: Int,
    ) {
        val key = CleanupKey(job.id, demoKey(index))
        if (!inFlight.add(key)) return

        val block = demo.block
        val location = Location(world, block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
        try {
            scheduler.runRegion(location) { target ->
                var resultQueued = false
                try {
                    val targetWorld = target.world ?: return@runRegion
                    if (!targetWorld.isChunkLoaded(block.x shr 4, block.z shr 4)) return@runRegion
                    val currentJob = stateProvider().cleanupJobs.firstOrNull { it.id == job.id } ?: return@runRegion
                    val currentDemo =
                        currentJob.display.getOrNull(index)?.takeIf { it.status == DeviceCleanupStatus.PENDING }
                            ?: return@runRegion

                    val expectedMaterial = Material.matchMaterial(block.material)
                    val target2 = targetWorld.getBlockAt(block.x, block.y, block.z)
                    val outcome =
                        when {
                            expectedMaterial == null ->
                                DeviceCleanupStatus.NEEDS_ATTENTION to
                                    "Unsupported demo block data ${block.data}"
                            target2.type == Material.AIR -> DeviceCleanupStatus.CLEARED to null
                            target2.type == expectedMaterial -> {
                                target2.setType(Material.AIR, true)
                                DeviceCleanupStatus.CLEARED to null
                            }
                            else ->
                                DeviceCleanupStatus.NEEDS_ATTENTION to
                                    "Expected AIR or $expectedMaterial, found ${target2.type}"
                        }
                    pendingResults.add(
                        CleanupResult(
                            jobId = currentJob.id,
                            bitIndex = demoKey(index),
                            status = outcome.first,
                            detail = outcome.second,
                        ),
                    )
                    resultQueued = true
                    scheduleResultDrain()
                } catch (exception: Exception) {
                    logger.warning(
                        "Deferred demo cleanup failed for device '${job.deviceName}' at " +
                            "${block.x},${block.y},${block.z}: ${exception.message ?: exception::class.simpleName}",
                    )
                } finally {
                    if (!resultQueued) inFlight.remove(key)
                }
            }
        } catch (exception: Exception) {
            inFlight.remove(key)
            logger.warning(
                "Unable to schedule deferred demo cleanup for device '${job.deviceName}': ${exception.message}",
            )
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
                        "Deferred cleanup failed for device '${job.deviceName}' output ${output.bitIndex}:" +
                            " ${exception.message ?: exception::class.simpleName}",
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
     * Analog slots are lecterns the device drove, recognised by the book they
     * hold.
     *
     * A blank lectern still counts as this device's slot: disabling a device
     * removes its book (level 0) but keeps the lectern, and the slot itself is
     * owned by the device. A lectern that holds **someone else's** book is player
     * property: it is left in place and reported instead of being destroyed.
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
        if (lectern == null) {
            return DeviceCleanupStatus.NEEDS_ATTENTION to "Block state is not a lectern, left untouched"
        }
        val inventory = lectern.inventory
        val holdsOwnBook = books.holdsDeviceBook(inventory, deviceId)
        val holdsNothing = books.isSlotEmpty(inventory)
        if (!holdsOwnBook && !holdsNothing) {
            return DeviceCleanupStatus.NEEDS_ATTENTION to
                "Lectern holds a foreign book, left untouched"
        }
        // Empty the lectern first so the book is not dropped as an item entity.
        if (holdsOwnBook) books.clear(inventory)
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
                        val updatedDisplay =
                            job.display.mapIndexed { index, demo ->
                                val result = resultsByKey[CleanupKey(job.id, demoKey(index))]
                                if (result == null || demo.status != DeviceCleanupStatus.PENDING) {
                                    demo
                                } else {
                                    DeviceCleanupDemo(demo.block, result.status, result.detail)
                                }
                            }
                        val cleared =
                            updatedOutputs.all { it.status == DeviceCleanupStatus.CLEARED } &&
                                updatedDisplay.all { it.status == DeviceCleanupStatus.CLEARED }
                        if (cleared) null else job.copy(outputs = updatedOutputs, display = updatedDisplay)
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

    /** Demo blocks use the negative key range so they cannot collide with slots. */
    private fun demoKey(index: Int): Int = -1 - index

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
