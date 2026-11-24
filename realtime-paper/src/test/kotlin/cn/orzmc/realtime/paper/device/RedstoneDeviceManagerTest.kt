package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputResolution
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
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
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

class RedstoneDeviceManagerTest {
    @Test
    fun `refresh task runs every tick so a second change is picked up immediately`() {
        val fixture = fixture()
        fixture.manager.start(fixture.plugin)

        assertEquals(1L, fixture.periodicInitialDelay())
        assertEquals(1L, fixture.periodicPeriod())
    }

    @Test
    fun `first refresh writes every slot and batches one region task per chunk`() {
        val fixture = fixture(resolution = OutputResolution.SECOND)
        fixture.startAndRefresh()

        // 24 outputs at x=0..23 span two chunks, so two region tasks instead of 24.
        assertEquals(2, fixture.pendingRegions())

        fixture.runPendingRegions()

        val expectedPowered =
            fixture
                .expectedBits()
                .withIndex()
                .filter { it.value }
                .map { it.index }
                .toSet()
        for (bit in 0 until 24) {
            assertEquals(
                if (bit in expectedPowered) Material.REDSTONE_BLOCK else Material.AIR,
                fixture.typeAt(bit),
                "slot $bit",
            )
        }
        assertEquals(expectedPowered.size.toLong(), fixture.manager.snapshot().powerChanges)
        assertEquals((24 - expectedPowered.size).toLong(), fixture.manager.snapshot().unchangedOutputs)
        assertTrue(fixture.physicsWrites() >= expectedPowered.size)
    }

    @Test
    fun `next second only schedules the slots whose bit changed`() {
        val fixture = fixture(resolution = OutputResolution.SECOND)
        fixture.startAndRefresh()
        fixture.runPendingRegions()
        val before = fixture.manager.snapshot().powerChanges

        // 23:59:00 -> 23:59:01 only flips the second-ones LSB (bit 20).
        fixture.setInstant(Instant.parse("2026-11-23T15:59:01Z"))
        fixture.tick()

        assertEquals(1, fixture.pendingRegions())
        fixture.runPendingRegions()
        assertEquals(Material.REDSTONE_BLOCK, fixture.typeAt(20))
        assertEquals(before + 1, fixture.manager.snapshot().powerChanges)
    }

    @Test
    fun `unchanged second does not schedule any work`() {
        val fixture = fixture(resolution = OutputResolution.SECOND)
        fixture.startAndRefresh()
        fixture.runPendingRegions()

        fixture.tick()

        assertEquals(0, fixture.pendingRegions())
    }

    @Test
    fun `minute devices ignore second changes until the minute rolls over`() {
        val fixture = fixture(resolution = OutputResolution.MINUTE)
        fixture.startAndRefresh()
        // 16 minute slots all live in one chunk.
        assertEquals(1, fixture.pendingRegions())
        fixture.runPendingRegions()

        fixture.setInstant(Instant.parse("2026-11-23T15:59:01Z"))
        fixture.tick()
        assertEquals(0, fixture.pendingRegions())

        fixture.setInstant(Instant.parse("2026-11-23T16:00:00Z"))
        fixture.tick()
        assertEquals(1, fixture.pendingRegions())
    }

    @Test
    fun `periodic full check notices a slot edited by a player`() {
        val fixture = fixture(resolution = OutputResolution.SECOND)
        fixture.startAndRefresh()
        fixture.runPendingRegions()

        // A player drops a stone into a slot the plugin owns.
        fixture.setType(0, Material.STONE)
        val invalidBefore = fixture.manager.snapshot().invalidOutputBlocks

        fixture.setInstant(Instant.parse("2026-11-23T15:59:31Z"))
        fixture.tick()
        fixture.runPendingRegions()

        assertEquals(Material.STONE, fixture.typeAt(0), "player block must survive")
        assertTrue(fixture.manager.snapshot().invalidOutputBlocks > invalidBefore)
    }

    @Test
    fun `unloaded chunk is skipped without touching blocks`() {
        val fixture = fixture(resolution = OutputResolution.SECOND, chunkLoaded = false)
        fixture.startAndRefresh()

        fixture.runPendingRegions()

        assertEquals(0, fixture.blockAccesses())
        assertEquals(24L, fixture.manager.snapshot().unloadedChunkSkips)
    }

    @Test
    fun `slots skipped by an unloaded chunk are written once the chunk loads`() {
        val fixture = fixture(resolution = OutputResolution.SECOND, chunkLoaded = false)
        fixture.startAndRefresh()
        fixture.runPendingRegions()
        assertEquals(0, fixture.blockAccesses())

        // The chunk loads a second later: the skipped batch must still be pending.
        fixture.setChunkLoaded(true)
        fixture.setInstant(Instant.parse("2026-11-23T15:59:01Z"))
        fixture.tick()
        fixture.runPendingRegions()

        val expected = fixture.expectedBits()
        for (bit in 0 until 24) {
            assertEquals(
                if (expected[bit]) Material.REDSTONE_BLOCK else Material.AIR,
                fixture.typeAt(bit),
                "slot $bit",
            )
        }
    }

    @Test
    fun `chunk load applies the current frame to that chunk only`() {
        val fixture = fixture(resolution = OutputResolution.SECOND)

        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)

        val expected = fixture.expectedBits()
        for (bit in 0..15) {
            assertEquals(
                if (expected[bit]) Material.REDSTONE_BLOCK else Material.AIR,
                fixture.typeAt(bit),
                "slot $bit",
            )
        }
        for (bit in 16..23) {
            assertEquals(Material.AIR, fixture.typeAt(bit), "slot $bit must stay untouched")
        }
    }

    @Test
    fun `disabled device outputs are cleared when their chunk loads`() {
        val fixture = fixture(resolution = OutputResolution.SECOND, deviceEnabled = false, initialType = Material.REDSTONE_BLOCK)

        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)

        for (bit in 0..15) {
            assertEquals(Material.AIR, fixture.typeAt(bit), "slot $bit")
        }
    }

    @Test
    fun `stale scheduled outputs are cleared instead of being repowered`() {
        val fixture = fixture(resolution = OutputResolution.SECOND)
        fixture.startAndRefresh()

        fixture.replaceState(fixture.initialState.copy(devices = emptyList()))
        fixture.runPendingRegions()

        for (bit in 0 until 24) {
            assertEquals(Material.AIR, fixture.typeAt(bit), "slot $bit")
        }
        assertTrue(fixture.manager.snapshot().staleOutputUpdates >= 24)
    }

    @Test
    fun `caught write failures are counted`() {
        val fixture = fixture(resolution = OutputResolution.SECOND, failWrite = true)
        fixture.startAndRefresh()
        fixture.runPendingRegions()

        assertTrue(fixture.manager.snapshot().writeFailures > 0)
    }

    private fun fixture(
        chunkLoaded: Boolean = true,
        deviceEnabled: Boolean = true,
        initialType: Material = Material.AIR,
        failWrite: Boolean = false,
        resolution: OutputResolution = OutputResolution.MINUTE,
    ): Fixture {
        val worldName = "world"
        val types = mutableMapOf<Int, Material>()
        var accesses = 0
        var physicsWrites = 0
        var currentInstant = Instant.parse("2026-11-23T15:59:00Z")
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = currentInstant
            }

        fun blockAt(x: Int): Block =
            fake { method, args ->
                when (method.name) {
                    "getType" -> types.getOrDefault(x, initialType)
                    "setType" -> {
                        if (failWrite) throw IllegalStateException("simulated block write failure")
                        types[x] = args!![0] as Material
                        if (args[1] as Boolean) physicsWrites += 1
                        null
                    }
                    else -> null
                }
            }

        var chunkLoadedNow = chunkLoaded
        val world =
            fake<World> { method, args ->
                when (method.name) {
                    "getName" -> worldName
                    "isChunkLoaded" -> chunkLoadedNow
                    "getBlockAt" -> {
                        accesses += 1
                        blockAt(args!![0] as Int)
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
        val chunkLoadEvent = ChunkLoadEvent(chunk, false)
        val pluginManager = fake<PluginManager> { _, _ -> null }
        val server =
            fake<Server> { method, _ ->
                when (method.name) {
                    "getWorld" -> world
                    "getPluginManager" -> pluginManager
                    else -> null
                }
            }
        var globalCallback: Consumer<ScheduledTask>? = null
        var periodicCallback: Consumer<ScheduledTask>? = null
        var periodicInitialDelay = -1L
        var periodicPeriod = -1L
        val scheduledTask = fakeScheduledTask()
        val globalScheduler =
            fake<GlobalRegionScheduler> { method, args ->
                when (method.name) {
                    "run" -> globalCallback = args!![1] as Consumer<ScheduledTask>
                    "runAtFixedRate" -> {
                        periodicCallback = args!![1] as Consumer<ScheduledTask>
                        periodicInitialDelay = args[2] as Long
                        periodicPeriod = args[3] as Long
                    }
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
        val plugin = fake<Plugin> { _, _ -> null }
        val scheduler = PaperFoliaScheduler(plugin, globalScheduler, regionScheduler)
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
        var runtimeState =
            RuntimeState(
                configuration = configuration,
                timeService = ClockRealTimeService(clock, configuration.zoneId, configuration.locale),
                messages = MessageCatalog(emptyMap()),
                redstoneConfiguration = RedstoneConfiguration(enabled = true),
                devices =
                    listOf(
                        RedstoneDevice(
                            id = UUID.randomUUID(),
                            name = "test-clock",
                            worldName = worldName,
                            enabled = deviceEnabled,
                            outputs =
                                (0 until resolution.outputCount).map { bit ->
                                    DeviceOutput(bit, bit, 64, 0)
                                },
                            resolution = resolution,
                        ),
                    ),
            )
        val manager =
            RedstoneDeviceManager(
                server = server,
                scheduler = scheduler,
                stateProvider = { runtimeState },
                logger = Logger.getLogger("test"),
            )

        return Fixture(
            plugin = plugin,
            manager = manager,
            chunkLoadEvent = chunkLoadEvent,
            periodicInitialDelay = { periodicInitialDelay },
            periodicPeriod = { periodicPeriod },
            startAndRefresh = {
                manager.start(plugin)
                globalCallback!!.accept(scheduledTask)
            },
            tick = { periodicCallback!!.accept(scheduledTask) },
            pendingRegions = { regionCallbacks.size },
            runPendingRegions = {
                val pending = regionCallbacks.toList()
                regionCallbacks.clear()
                pending.forEach { it.accept(scheduledTask) }
            },
            typeAt = { types.getOrDefault(it, initialType) },
            setType = { bit, material -> types[bit] = material },
            setChunkLoaded = { chunkLoadedNow = it },
            blockAccesses = { accesses },
            physicsWrites = { physicsWrites },
            expectedBits = {
                val snapshot = runtimeState.timeService.snapshot()
                cn.orzmc.realtime.core.device.BcdTimeEncoder
                    .encode(snapshot.zonedDateTime.toLocalTime())
                    .bits(resolution)
            },
            initialState = runtimeState,
            replaceState = { runtimeState = it },
            setInstant = { currentInstant = it },
        )
    }

    private class Fixture(
        val plugin: Plugin,
        val manager: RedstoneDeviceManager,
        val chunkLoadEvent: ChunkLoadEvent,
        val periodicInitialDelay: () -> Long,
        val periodicPeriod: () -> Long,
        val startAndRefresh: () -> Unit,
        val tick: () -> Unit,
        val pendingRegions: () -> Int,
        val runPendingRegions: () -> Unit,
        val typeAt: (Int) -> Material,
        val setType: (Int, Material) -> Unit,
        val setChunkLoaded: (Boolean) -> Unit,
        val blockAccesses: () -> Int,
        val physicsWrites: () -> Int,
        val expectedBits: () -> List<Boolean>,
        val initialState: RuntimeState,
        val replaceState: (RuntimeState) -> Unit,
        val setInstant: (Instant) -> Unit,
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
