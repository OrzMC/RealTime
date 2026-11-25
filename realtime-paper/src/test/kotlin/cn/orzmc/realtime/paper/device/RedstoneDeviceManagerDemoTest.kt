package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DemoBlock
import cn.orzmc.realtime.core.device.DeviceOutput
import cn.orzmc.realtime.core.device.OutputEncoding
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
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
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

/**
 * `device demo` builds: the display blocks are placed while the device is
 * enabled, removed when it is disabled, and never overwrite a player block.
 */
class RedstoneDeviceManagerDemoTest {
    @Test
    fun `places the display blocks of a demo build`() {
        val fixture = fixture()

        fixture.startAndRefresh()
        fixture.runPendingRegions()

        assertEquals(Material.REDSTONE_LAMP, fixture.typeAt(0, 71, 0))
        assertEquals(Material.REDSTONE_WIRE, fixture.typeAt(1, 71, 0))
        assertEquals(2L, fixture.manager.snapshot().demoBlocksPlaced)
    }

    @Test
    fun `removes the display blocks when the device is disabled`() {
        val fixture = fixture()
        fixture.startAndRefresh()
        fixture.runPendingRegions()

        fixture.replaceState(
            fixture.initialState.copy(
                devices = listOf(copyWithEnabled(fixture.initialState.devices.single(), false)),
            ),
        )
        fixture.requestRefresh()
        fixture.runPendingRegions()

        assertEquals(Material.AIR, fixture.typeAt(0, 71, 0))
        assertEquals(Material.AIR, fixture.typeAt(1, 71, 0))
        assertEquals(2L, fixture.manager.snapshot().demoBlocksCleared)
    }

    @Test
    fun `never overwrites a player block where a display block belongs`() {
        val fixture = fixture(initialType = Material.STONE)

        fixture.startAndRefresh()
        fixture.runPendingRegions()

        assertEquals(Material.STONE, fixture.typeAt(0, 71, 0))
        assertTrue(fixture.manager.snapshot().invalidOutputBlocks > 0)
        assertEquals(0L, fixture.manager.snapshot().demoBlocksPlaced)
    }

    @Test
    fun `a settled demo is not rewritten on every refresh`() {
        val fixture = fixture()
        fixture.startAndRefresh()
        fixture.runPendingRegions()
        val placed = fixture.manager.snapshot().demoBlocksPlaced

        // A forced refresh (reload / toggle) re-checks, but the block is already right.
        fixture.requestRefresh()
        fixture.runPendingRegions()

        assertEquals(placed, fixture.manager.snapshot().demoBlocksPlaced)
    }

    private fun copyWithEnabled(
        device: RedstoneDevice,
        enabled: Boolean,
    ): RedstoneDevice =
        RedstoneDevice(
            device.id,
            device.name,
            device.worldName,
            enabled,
            device.outputs,
            device.resolution,
            device.encoding,
            device.demoBlocks,
        )

    private fun fixture(initialType: Material = Material.AIR): Fixture {
        val types = mutableMapOf<Triple<Int, Int, Int>, Material>()
        var currentInstant = Instant.parse("2026-11-23T15:59:00Z")
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = currentInstant
            }
        val worldName = "world"

        fun blockAt(
            x: Int,
            y: Int,
            z: Int,
        ): Block =
            fake { method, args ->
                val key = Triple(x, y, z)
                when (method.name) {
                    "getType" -> types.getOrDefault(key, initialType)
                    "setType" -> {
                        types[key] = args!![0] as Material
                        null
                    }
                    "setBlockData" -> {
                        types[key] = (args!![0] as BlockData).material
                        null
                    }
                    "getState" -> null
                    else -> null
                }
            }

        val world =
            fake<World> { method, args ->
                when (method.name) {
                    "getName" -> worldName
                    "isChunkLoaded" -> true
                    "getBlockAt" -> blockAt(args!![0] as Int, args[1] as Int, args[2] as Int)
                    else -> null
                }
            }
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
        val slots = (0 until 4).map { index -> DeviceOutput(index, index, 70, -2) }
        val demoBlocks =
            listOf(
                DemoBlock(0, 71, 0, "minecraft:redstone_lamp"),
                DemoBlock(1, 71, 0, "minecraft:redstone_wire"),
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
                            name = "demo-analog",
                            worldName = worldName,
                            enabled = true,
                            outputs = slots,
                            resolution = OutputResolution.MINUTE,
                            encoding = OutputEncoding.ANALOG,
                            demoBlocks = demoBlocks,
                        ),
                    ),
            )
        val manager =
            RedstoneDeviceManager(
                server = server,
                scheduler = scheduler,
                stateProvider = { runtimeState },
                logger = Logger.getLogger("test"),
                books = FakeBooks(),
                blockDataFactory = { data ->
                    val material = Material.matchMaterial(data.substringBefore('[')) ?: Material.AIR
                    fake<BlockData> { method, _ -> if (method.name == "getMaterial") material else null }
                },
            )

        return Fixture(
            manager = manager,
            startAndRefresh = {
                manager.start(plugin)
                globalCallback!!.accept(scheduledTask)
            },
            requestRefresh = {
                manager.requestRefresh()
                globalCallback!!.accept(scheduledTask)
            },
            runPendingRegions = {
                val pending = regionCallbacks.toList()
                regionCallbacks.clear()
                pending.forEach { it.accept(scheduledTask) }
            },
            typeAt = { x, y, z -> types.getOrDefault(Triple(x, y, z), initialType) },
            initialState = runtimeState,
            replaceState = { runtimeState = it },
            tick = { periodicCallback!!.accept(scheduledTask) },
        )
    }

    private class FakeBooks : AnalogBookFactory {
        override fun install(
            inventory: org.bukkit.inventory.Inventory,
            deviceId: UUID,
        ) = Unit

        override fun holdsDeviceBook(
            inventory: org.bukkit.inventory.Inventory,
            deviceId: UUID,
        ): Boolean = false

        override fun isSlotEmpty(inventory: org.bukkit.inventory.Inventory): Boolean = true

        override fun clear(inventory: org.bukkit.inventory.Inventory) = Unit
    }

    private class Fixture(
        val manager: RedstoneDeviceManager,
        val startAndRefresh: () -> Unit,
        val requestRefresh: () -> Unit,
        val runPendingRegions: () -> Unit,
        val typeAt: (Int, Int, Int) -> Material,
        val initialState: RuntimeState,
        val replaceState: (RuntimeState) -> Unit,
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
