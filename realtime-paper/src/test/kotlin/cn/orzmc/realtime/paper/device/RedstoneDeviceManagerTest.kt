package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.time.ClockRealTimeService
import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.config.RealTimeConfiguration
import cn.orzmc.realtime.paper.message.MessageCatalog
import cn.orzmc.realtime.paper.scheduler.PaperFoliaScheduler
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.RedstoneWire
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
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger

class RedstoneDeviceManagerTest {
    @Test
    fun `writes powered output only after loaded chunk check`() {
        val fixture = fixture(chunkLoaded = true)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallbackValue!!.accept(fixture.scheduledTask)

        assertEquals(16, fixture.regionCallbacks.size)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertTrue(fixture.blockAccessedValue)
        assertEquals(15, fixture.wirePowerValue)
        assertTrue(fixture.physicsAppliedValue)
    }

    @Test
    fun `skips unloaded chunk without accessing its blocks`() {
        val fixture = fixture(chunkLoaded = false)
        fixture.manager.start(fixture.plugin)
        fixture.globalCallback!!.accept(fixture.scheduledTask)
        fixture.regionCallbacks[1].accept(fixture.scheduledTask)

        assertFalse(fixture.blockAccessedValue)
        assertEquals(0, fixture.wirePowerValue)
    }

    private fun fixture(chunkLoaded: Boolean): Fixture {
        val worldName = "world"
        var wirePower = 0
        var blockAccessed = false
        var physicsApplied = false
        val wire = fake<RedstoneWire> { method, args ->
            when (method.name) {
                "getPower" -> wirePower
                "setPower" -> {
                    wirePower = args!![0] as Int
                    null
                }
                else -> null
            }
        }
        val block = fake<Block> { method, args ->
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
        val world = fake<World> { method, _ ->
            when (method.name) {
                "getName" -> "world"
                "isChunkLoaded" -> chunkLoaded
                "getBlockAt" -> {
                    blockAccessed = true
                    block
                }
                else -> null
            }
        }
        val pluginManager = fake<PluginManager> { _, _ -> null }
        val server = fake<Server> { method, _ ->
            when (method.name) {
                "getWorld" -> world
                "getPluginManager" -> pluginManager
                else -> null
            }
        }
        var globalCallback: Consumer<ScheduledTask>? = null
        val scheduledTask = fakeScheduledTask()
        val globalScheduler = fake<GlobalRegionScheduler> { method, args ->
            if (method.name == "run") {
                globalCallback = args!![1] as Consumer<ScheduledTask>
            }
            scheduledTask
        }
        val regionCallbacks = mutableListOf<Consumer<ScheduledTask>>()
        val regionScheduler = fake<RegionScheduler> { method, args ->
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
                locale = java.util.Locale.forLanguageTag("zh-CN"),
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
                                    cn.orzmc.realtime.core.device.DeviceOutput(bit, bit * 2, 64, 0)
                                },
                        ),
                    ),
            )
        val manager = RedstoneDeviceManager(server, scheduler, { runtimeState }, Logger.getLogger("test"))
        return Fixture(
            plugin = plugin,
            manager = manager,
            globalCallback = globalCallback,
            regionCallbacks = regionCallbacks,
            scheduledTask = scheduledTask,
            blockAccessed = blockAccessed,
            wirePower = wirePower,
            physicsApplied = physicsApplied,
        ).also { fixture ->
            fixture.blockAccessedProvider = { blockAccessed }
            fixture.wirePowerProvider = { wirePower }
            fixture.physicsAppliedProvider = { physicsApplied }
            fixture.globalCallbackProvider = { globalCallback }
        }
    }

    private class Fixture(
        val plugin: Plugin,
        val manager: RedstoneDeviceManager,
        var globalCallback: Consumer<ScheduledTask>?,
        val regionCallbacks: List<Consumer<ScheduledTask>>,
        val scheduledTask: ScheduledTask,
        var blockAccessed: Boolean,
        var wirePower: Int,
        var physicsApplied: Boolean,
    ) {
        var blockAccessedProvider: () -> Boolean = { blockAccessed }
        var wirePowerProvider: () -> Int = { wirePower }
        var physicsAppliedProvider: () -> Boolean = { physicsApplied }
        var globalCallbackProvider: () -> Consumer<ScheduledTask>? = { globalCallback }
        val blockAccessedValue get() = blockAccessedProvider()
        val wirePowerValue get() = wirePowerProvider()
        val physicsAppliedValue get() = physicsAppliedProvider()
        val globalCallbackValue get() = globalCallbackProvider()
    }

    private fun fakeScheduledTask(): ScheduledTask = fake { method, _ ->
        when (method.name) {
            "cancel" -> ScheduledTask.CancelledState.CANCELLED_BY_CALLER
            "isCancelled" -> false
            "isRepeatingTask" -> true
            else -> null
        }
    }

    private inline fun <reified T> fake(noinline handler: (Method, Array<out Any?>?) -> Any?): T {
        val type = T::class.java
        val instance = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            handler(method, args)
        }
        return type.cast(instance)
    }
}