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
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
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

class RedstoneDeviceManagerTest {
    @Test
    fun `writes powered output only after loaded chunk check`() {
        val fixture = fixture(chunkLoaded = true)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)

        assertEquals(16, fixture.regionCallbacks.size)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertTrue(fixture.blockAccessed())
        assertEquals(15, fixture.wirePower())
        assertTrue(fixture.physicsApplied())
        assertEquals(1, fixture.manager.snapshot().outputChecks)
        assertEquals(1, fixture.manager.snapshot().powerChanges)
    }

    @Test
    fun `refreshes outputs when their chunk loads`() {
        val fixture = fixture(chunkLoaded = true)

        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)

        assertTrue(fixture.blockAccessed())
        assertTrue(fixture.physicsApplied())
    }

    @Test
    fun `clears disabled device outputs when their chunk loads`() {
        val fixture = fixture(chunkLoaded = true, deviceEnabled = false, initialWirePower = 15)

        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)

        assertTrue(fixture.blockAccessed())
        assertEquals(0, fixture.wirePower())
        assertTrue(fixture.physicsApplied())
    }

    @Test
    fun `clears disabled device outputs during scheduled refresh`() {
        val fixture = fixture(chunkLoaded = true, deviceEnabled = false, initialWirePower = 15)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertTrue(fixture.blockAccessed())
        assertEquals(0, fixture.wirePower())
        assertTrue(fixture.physicsApplied())
    }

    @Test
    fun `counts outputs that already have the expected power`() {
        val fixture = fixture(chunkLoaded = true, initialWirePower = 15)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertEquals(1, fixture.manager.snapshot().unchangedOutputs)
        assertEquals(0, fixture.manager.snapshot().powerChanges)
    }

    @Test
    fun `counts invalid output blocks`() {
        val fixture = fixture(chunkLoaded = true, blockType = Material.STONE)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertEquals(1, fixture.manager.snapshot().invalidOutputBlocks)
    }

    @Test
    fun `counts caught output write failures`() {
        val fixture = fixture(chunkLoaded = true, failWrite = true)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertEquals(1, fixture.manager.snapshot().writeFailures)
    }

    @Test
    fun `stale scheduled outputs are cleared instead of being repowered`() {
        val fixture = fixture(chunkLoaded = true)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.replaceState(fixture.initialState.copy(devices = emptyList()))
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertEquals(0, fixture.wirePower())
        assertEquals(1, fixture.manager.snapshot().staleOutputUpdates)
    }

    @Test
    fun `skips unloaded chunk without accessing its blocks`() {
        val fixture = fixture(chunkLoaded = false)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertFalse(fixture.blockAccessed())
        assertEquals(0, fixture.wirePower())
        assertEquals(1, fixture.manager.snapshot().unloadedChunkSkips)
    }

    @Test
    fun `second resolution devices expose twenty four outputs`() {
        val fixture = fixture(chunkLoaded = true, resolution = OutputResolution.SECOND)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)

        assertEquals(24, fixture.regionCallbacks.size)
        assertEquals(24, fixture.manager.snapshot().configuredOutputs)
    }

    @Test
    fun `second resolution devices refresh every second and power the changed second bits`() {
        val fixture = fixture(chunkLoaded = true, resolution = OutputResolution.SECOND)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        assertEquals(24, fixture.regionCallbacks.size)

        // At 23:59:00 the second ones digit is 0, so bit 20 stays unpowered.
        fixture.regionCallbacks[20].accept(fixture.scheduledTask)
        assertEquals(0, fixture.wirePower())

        fixture.setInstant(Instant.parse("2026-11-23T15:59:01Z"))
        fixture.tick()
        assertEquals(48, fixture.regionCallbacks.size)

        // Second ones digit is now 1, so bit 20 (LSB) must be powered.
        fixture.regionCallbacks[44].accept(fixture.scheduledTask)
        assertEquals(15, fixture.wirePower())
        assertEquals(1, fixture.manager.snapshot().powerChanges)
    }

    @Test
    fun `minute resolution devices are not rewritten while only the second changes`() {
        val fixture = fixture(chunkLoaded = true)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        assertEquals(16, fixture.regionCallbacks.size)

        fixture.tick()
        assertEquals(16, fixture.regionCallbacks.size)

        fixture.setInstant(Instant.parse("2026-11-23T15:59:01Z"))
        fixture.tick()
        assertEquals(16, fixture.regionCallbacks.size)

        fixture.setInstant(Instant.parse("2026-11-23T16:00:00Z"))
        fixture.tick()
        assertEquals(32, fixture.regionCallbacks.size)
    }

    private fun fixture(
        chunkLoaded: Boolean,
        deviceEnabled: Boolean = true,
        initialWirePower: Int = 0,
        blockType: Material = Material.REDSTONE_WIRE,
        failWrite: Boolean = false,
        resolution: OutputResolution = OutputResolution.MINUTE,
    ): Fixture {
        val worldName = "world"
        var wirePower = initialWirePower
        var blockAccessed = false
        var physicsApplied = false
        var currentInstant = Instant.parse("2026-11-23T15:59:00Z")
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = currentInstant
            }

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
            fake<Block> { method, args ->
                when (method.name) {
                    "getType" -> blockType
                    "getBlockData" -> wire
                    "setBlockData" -> {
                        if (failWrite) throw IllegalStateException("simulated block write failure")
                        physicsApplied = args!![1] as Boolean
                        null
                    }
                    else -> null
                }
            }
        val world =
            fake<World> { method, _ ->
                when (method.name) {
                    "getName" -> worldName
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
        val scheduledTask = fakeScheduledTask()
        val globalScheduler =
            fake<GlobalRegionScheduler> { method, args ->
                when (method.name) {
                    "run" -> globalCallback = args!![1] as Consumer<ScheduledTask>
                    "runAtFixedRate" -> periodicCallback = args!![1] as Consumer<ScheduledTask>
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
                timeService =
                    ClockRealTimeService(
                        clock,
                        configuration.zoneId,
                        configuration.locale,
                    ),
                messages = MessageCatalog(emptyMap()),
                redstoneConfiguration = RedstoneConfiguration(enabled = true, outputPower = 15),
                devices =
                    listOf(
                        RedstoneDevice(
                            id = UUID.randomUUID(),
                            name = "test-clock",
                            worldName = worldName,
                            enabled = deviceEnabled,
                            outputs =
                                (0 until resolution.outputCount).map { bit ->
                                    DeviceOutput(bit, bit * 2, 64, 0)
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
            globalCallback = { globalCallback },
            regionCallbacks = regionCallbacks,
            scheduledTask = scheduledTask,
            blockAccessed = { blockAccessed },
            wirePower = { wirePower },
            physicsApplied = { physicsApplied },
            initialState = runtimeState,
            replaceState = { runtimeState = it },
            setInstant = { currentInstant = it },
            tick = { periodicCallback!!.accept(scheduledTask) },
        )
    }

    private data class Fixture(
        val plugin: Plugin,
        val manager: RedstoneDeviceManager,
        val chunkLoadEvent: ChunkLoadEvent,
        val globalCallback: () -> Consumer<ScheduledTask>?,
        val regionCallbacks: List<Consumer<ScheduledTask>>,
        val scheduledTask: ScheduledTask,
        val blockAccessed: () -> Boolean,
        val wirePower: () -> Int,
        val physicsApplied: () -> Boolean,
        val initialState: RuntimeState,
        val replaceState: (RuntimeState) -> Unit,
        val setInstant: (Instant) -> Unit,
        val tick: () -> Unit,
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
