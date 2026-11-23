package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger

class DeviceCleanupManagerTest {
    @Test
    fun `clears a loaded redstone output and persists its result on the global scheduler`() {
        val fixture = fixture(chunkLoaded = true)
        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)

        assertTrue(fixture.globalCallbacks.isNotEmpty())
        fixture.globalCallbacks.first().accept(fixture.scheduledTask)
        assertTrue(fixture.regionCallbacks.isNotEmpty())
        fixture.regionCallbacks.first().accept(fixture.scheduledTask)

        assertTrue(fixture.blockAccessed())
        assertEquals(0, fixture.wirePower())
        assertTrue(fixture.globalCallbacks.isNotEmpty())
        fixture.globalCallbacks.last().accept(fixture.scheduledTask)

        val persistedJob = fixture.persistedSnapshot()?.cleanupJobs?.single()
        assertEquals(DeviceCleanupStatus.CLEARED, persistedJob?.outputs?.first()?.status)
        assertTrue(
            fixture
                .currentState()
                .cleanupJobs
                .single()
                .outputs
                .first()
                .status == DeviceCleanupStatus.CLEARED,
        )
    }

    @Test
    fun `does not inspect an unloaded cleanup chunk or mark it complete`() {
        val fixture = fixture(chunkLoaded = false)
        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)
        fixture.globalCallbacks.first().accept(fixture.scheduledTask)
        fixture.regionCallbacks.first().accept(fixture.scheduledTask)

        assertFalse(fixture.blockAccessed())
        assertTrue(fixture.persistedSnapshot() == null)
        assertEquals(
            DeviceCleanupStatus.PENDING,
            fixture
                .currentState()
                .cleanupJobs
                .single()
                .outputs
                .first()
                .status,
        )
        assertEquals(16, fixture.manager.snapshot().pendingOutputs)
    }

    @Test
    fun `resolves an unresolved world name when that world loads`() {
        val fixture = fixture(chunkLoaded = true, resolveWorldIdentity = false)
        fixture.manager.onWorldLoad(fixture.worldLoadEvent)
        fixture.globalCallbacks.first().accept(fixture.scheduledTask)

        assertEquals(fixture.worldId, fixture.currentState().cleanupJobs.single().worldId)
        assertEquals(fixture.worldId, fixture.persistedSnapshot()?.cleanupJobs?.single()?.worldId)
        assertEquals(16, fixture.regionCallbacks.size)
    }

    @Test
    fun `keeps unrelated blocks reserved for operator attention`() {
        val fixture = fixture(chunkLoaded = true, blockMaterial = Material.STONE)
        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)
        fixture.globalCallbacks.first().accept(fixture.scheduledTask)
        fixture.regionCallbacks.first().accept(fixture.scheduledTask)
        fixture.globalCallbacks.last().accept(fixture.scheduledTask)

        val output = fixture.currentState().cleanupJobs.single().outputs.first()
        assertEquals(DeviceCleanupStatus.NEEDS_ATTENTION, output.status)
        assertTrue(output.detail!!.contains("STONE"))
        assertEquals(1, fixture.manager.snapshot().needsAttentionOutputs)
        assertEquals(15, fixture.manager.snapshot().pendingOutputs)
    }

    @Test
    fun `retries idempotently after cleanup progress persistence fails`() {
        val fixture = fixture(chunkLoaded = true, persistFailures = 1)
        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)
        fixture.globalCallbacks.first().accept(fixture.scheduledTask)
        fixture.regionCallbacks.first().accept(fixture.scheduledTask)
        fixture.globalCallbacks.last().accept(fixture.scheduledTask)

        assertEquals(0, fixture.wirePower())
        assertEquals(DeviceCleanupStatus.PENDING, fixture.currentState().cleanupJobs.single().outputs.first().status)

        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)
        fixture.globalCallbacks.last().accept(fixture.scheduledTask)
        fixture.regionCallbacks.last().accept(fixture.scheduledTask)
        fixture.globalCallbacks.last().accept(fixture.scheduledTask)

        assertEquals(DeviceCleanupStatus.CLEARED, fixture.currentState().cleanupJobs.single().outputs.first().status)
    }

    private fun fixture(
        chunkLoaded: Boolean,
        blockMaterial: Material = Material.REDSTONE_WIRE,
        persistFailures: Int = 0,
        resolveWorldIdentity: Boolean = true,
    ): Fixture {
        val worldId = UUID.randomUUID()
        var wirePower = 15
        var blockAccessed = false
        var remainingPersistFailures = persistFailures
        val wire =
            fake<RedstoneWire> { method, args ->
                when (method.name) {
                    "getPower" -> wirePower
                    "setPower" -> {
                        wirePower = args!![0] as Int
                        null
                    }
                    else -> null
                }
            }
        val block =
            fake<Block> { method, _ ->
                when (method.name) {
                    "getType" -> blockMaterial
                    "getBlockData" -> wire
                    "setBlockData" -> null
                    else -> null
                }
            }
        val world =
            fake<World> { method, _ ->
                when (method.name) {
                    "getName" -> "world"
                    "getUID" -> worldId
                    "isChunkLoaded" -> chunkLoaded
                    "getBlockAt" -> {
                        blockAccessed = true
                        block
                    }
                    else -> null
                }
            }
        val chunk =
            fake<Chunk> { method, _ ->
                when (method.name) {
                    "getX" -> 0
                    "getZ" -> 0
                    "getWorld" -> world
                    else -> null
                }
            }
        val event = ChunkLoadEvent(chunk, false)
        val worldLoadEvent = WorldLoadEvent(world)
        val plugin = fake<Plugin> { _, _ -> null }
        val scheduledTask = fakeScheduledTask()
        val globalCallbacks = mutableListOf<Consumer<ScheduledTask>>()
        val globalScheduler =
            fake<GlobalRegionScheduler> { method, args ->
                if (method.name == "run") {
                    globalCallbacks += args!![1] as Consumer<ScheduledTask>
                }
                scheduledTask
            }
        val regionCallbacks = mutableListOf<Consumer<ScheduledTask>>()
        val regionScheduler =
            fake<RegionScheduler> { method, args ->
                if (method.name == "run") {
                    regionCallbacks += args!![2] as Consumer<ScheduledTask>
                }
                scheduledTask
            }
        val scheduler = PaperFoliaScheduler(plugin, globalScheduler, regionScheduler)
        val device =
            RedstoneDevice(
                id = UUID.randomUUID(),
                name = "retired-clock",
                worldName = "world",
                enabled = false,
                outputs =
                    (0 until 16).map { bit ->
                        DeviceOutput(bitIndex = bit, x = bit * 2, y = 64, z = 0)
                    },
            )
        val job = DeviceCleanupJob.fromDevice(device, if (resolveWorldIdentity) worldId else null)
        val initialState = state(job)
        var activeState = initialState
        var persisted: DeviceStoreSnapshot? = null
        val manager =
            DeviceCleanupManager(
                server = fake<Server> { _, _ -> null },
                scheduler = scheduler,
                stateLock = Any(),
                stateProvider = { activeState },
                persistStore = {
                    if (remainingPersistFailures > 0) {
                        remainingPersistFailures -= 1
                        error("simulated persistence failure")
                    }
                    persisted = it
                },
                replaceState = { activeState = it },
                logger = Logger.getLogger("cleanup-test"),
            )

        return Fixture(
            manager = manager,
            chunkLoadEvent = event,
            worldLoadEvent = worldLoadEvent,
            worldId = worldId,
            regionCallbacks = regionCallbacks,
            globalCallbacks = globalCallbacks,
            scheduledTask = scheduledTask,
            blockAccessed = { blockAccessed },
            wirePower = { wirePower },
            persistedSnapshot = { persisted },
            currentState = { activeState },
        )
    }

    private fun state(job: DeviceCleanupJob): RuntimeState {
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
                    Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC),
                    configuration.zoneId,
                    configuration.locale,
                ),
            messages = MessageCatalog(emptyMap()),
            cleanupJobs = listOf(job),
        )
    }

    private data class Fixture(
        val manager: DeviceCleanupManager,
        val chunkLoadEvent: ChunkLoadEvent,
        val worldLoadEvent: WorldLoadEvent,
        val worldId: UUID,
        val regionCallbacks: List<Consumer<ScheduledTask>>,
        val globalCallbacks: List<Consumer<ScheduledTask>>,
        val scheduledTask: ScheduledTask,
        val blockAccessed: () -> Boolean,
        val wirePower: () -> Int,
        val persistedSnapshot: () -> DeviceStoreSnapshot?,
        val currentState: () -> RuntimeState,
    )

    private fun fakeScheduledTask(): ScheduledTask =
        fake { method, _ ->
            when (method.name) {
                "cancel" -> ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                "isCancelled" -> false
                "isRepeatingTask" -> true
                else -> null
            }
        }

    private inline fun <reified T> fake(noinline handler: (Method, Array<out Any?>?) -> Any?): T {
        val type = T::class.java
        val instance =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                handler(method, args)
            }
        return type.cast(instance)
    }
}
