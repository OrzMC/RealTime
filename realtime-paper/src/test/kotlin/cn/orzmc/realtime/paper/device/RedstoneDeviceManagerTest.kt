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
    }

    @Test
    fun `refreshes outputs when their chunk loads`() {
        val fixture = fixture(chunkLoaded = true)

        fixture.manager.onChunkLoad(fixture.chunkLoadEvent)

        assertTrue(fixture.blockAccessed())
        assertEquals(15, fixture.wirePower())
    }

    @Test
    fun `skips unloaded chunk without accessing its blocks`() {
        val fixture = fixture(chunkLoaded = false)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback()!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertFalse(fixture.blockAccessed())
        assertEquals(0, fixture.wirePower())
    }

    private fun fixture(chunkLoaded: Boolean): Fixture {
        val worldName = "world"
        var wirePower = 0
        var blockAccessed = false
        var physicsApplied = false

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
                    "getType" -> Material.REDSTONE_WIRE
                    "getBlockData" -> wire
                    "setBlockData" -> {
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
                    "getZ" -> 4
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
        val scheduledTask = fakeScheduledTask()
        val globalScheduler =
            fake<GlobalRegionScheduler> { method, args ->
                if (method.name == "run") {
                    globalCallback = args!![1] as Consumer<ScheduledTask>
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
        val runtimeState =
            RuntimeState(
                configuration = configuration,
                timeService =
                    ClockRealTimeService(
                        Clock.fixed(Instant.parse("2026-11-23T15:59:00Z"), ZoneOffset.UTC),
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
                            enabled = true,
                            outputs =
                                (0 until 16).map { bit ->
                                    DeviceOutput(bit, bit * 2, 64, 0)
                                },
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
