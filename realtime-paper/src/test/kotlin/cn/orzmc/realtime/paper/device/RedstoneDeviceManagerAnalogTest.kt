package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.AnalogScale
import cn.orzmc.realtime.core.device.BcdTimeEncoder
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
import org.bukkit.block.Lectern
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Logger

/**
 * Analog devices write lecterns instead of redstone blocks: one slot per digit,
 * the page index being the digit.
 */
class RedstoneDeviceManagerAnalogTest {
    @Test
    fun `installs a book and the digit page in every lectern`() {
        val fixture = fixture(OutputResolution.SECOND)

        fixture.startAndRefresh()

        assertEquals(1, fixture.pendingRegions(), "all lecterns share one chunk and one region task")
        fixture.runPendingRegions()
        val digits = fixture.expectedDigits()
        for (slot in 0 until 6) {
            val lectern = fixture.lectern(slot)
            assertEquals(Material.LECTERN, fixture.typeAt(slot), "slot $slot")
            assertTrue(fixture.books.owns(lectern), "slot $slot book")
            assertEquals(AnalogScale.pageForDigit(digits[slot]), lectern.page, "slot $slot page")
        }
        assertEquals(6L, fixture.manager.snapshot().lecternPlacements)
        assertEquals(6L, fixture.manager.snapshot().bookWrites)
        assertEquals(6, fixture.books.installs)
    }

    @Test
    fun `reuses lecterns and only rewrites the digit that changed`() {
        val fixture = fixture(OutputResolution.SECOND)
        fixture.startAndRefresh()
        fixture.runPendingRegions()
        val placements = fixture.manager.snapshot().lecternPlacements
        val books = fixture.manager.snapshot().bookWrites

        // 23:59:00 -> 23:59:01 flips only the last digit.
        fixture.setInstant(Instant.parse("2026-11-23T15:59:01Z"))
        fixture.tick()
        assertEquals(1, fixture.pendingRegions())
        fixture.runPendingRegions()

        assertEquals(placements, fixture.manager.snapshot().lecternPlacements, "no lectern may be replaced")
        assertEquals(books, fixture.manager.snapshot().bookWrites, "the book is installed once")
        assertEquals(6, fixture.books.installs, "books are installed exactly once")
        assertEquals(2, fixture.lectern(5).pageWrites, "one install page plus one digit change")
        for (slot in 0 until 5) {
            assertEquals(1, fixture.lectern(slot).pageWrites, "slot $slot must keep its page")
        }
        assertEquals(AnalogScale.pageForDigit(1), fixture.lectern(5).page)
    }

    @Test
    fun `disabling the device empties the slot so the comparator reads zero`() {
        val fixture = fixture(OutputResolution.MINUTE)
        fixture.startAndRefresh()
        fixture.runPendingRegions()
        assertEquals(4, fixture.books.installs)

        fixture.replaceState(
            fixture.initialState.copy(
                devices = listOf(copyWithEnabled(fixture.initialState.devices.single(), false)),
            ),
        )
        fixture.requestRefresh()
        fixture.runPendingRegions()

        for (slot in 0 until 4) {
            val lectern = fixture.lectern(slot)
            // The slot ends up empty, exactly like a BCD slot: level 0 comes from
            // air, so a moved slot cannot leave an orphaned lectern behind.
            assertEquals(Material.AIR, fixture.typeAt(slot), "slot $slot must be cleared")
            assertNull(lectern.marker, "slot $slot book must be removed")
            assertFalse(fixture.books.owns(lectern), "slot $slot")
        }
        assertEquals(4, fixture.books.clears)
        assertEquals(4L + 4L, fixture.manager.snapshot().bookWrites, "4 installs + 4 removals")
    }

    @Test
    fun `a foreign block in an analog slot is reported and never overwritten`() {
        val fixture = fixture(OutputResolution.MINUTE, initialType = Material.STONE)

        fixture.startAndRefresh()
        fixture.runPendingRegions()

        for (slot in 0 until 4) {
            assertEquals(Material.STONE, fixture.typeAt(slot), "slot $slot")
        }
        assertTrue(fixture.manager.snapshot().invalidOutputBlocks >= 4)
        assertEquals(0, fixture.books.installs)
        assertEquals(0L, fixture.manager.snapshot().bookWrites)
    }

    @Test
    fun `a lectern with a foreign book keeps it`() {
        val fixture = fixture(OutputResolution.MINUTE, initialType = Material.LECTERN)
        val foreign = Any()
        for (slot in 0 until 4) {
            fixture.lectern(slot).marker = foreign
        }

        fixture.startAndRefresh()
        fixture.runPendingRegions()

        for (slot in 0 until 4) {
            assertSame(foreign, fixture.lectern(slot).marker, "slot $slot")
            assertEquals(0, fixture.lectern(slot).pageWrites, "slot $slot")
        }
        assertTrue(fixture.manager.snapshot().invalidOutputBlocks >= 4)
        assertEquals(0, fixture.books.installs)
        assertEquals(0, fixture.books.clears)
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
        )

    private fun fixture(
        resolution: OutputResolution,
        initialType: Material = Material.AIR,
    ): Fixture {
        val deviceId = UUID.randomUUID()
        val types = mutableMapOf<Int, Material>()
        val lecterns = mutableMapOf<Int, FakeLectern>()
        var currentInstant = Instant.parse("2026-11-23T15:59:00Z")
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = currentInstant
            }
        val books = RecordingBooks()
        val worldName = "world"

        fun inventoryProxy(state: FakeLectern): Inventory =
            fake { method, args ->
                when (method.name) {
                    "getItem" -> state.marker
                    "setItem" -> {
                        state.marker = args!![1]
                        null
                    }
                    else -> null
                }
            }

        fun lecternProxy(slot: Int): Lectern {
            val state = lecterns.getOrPut(slot) { FakeLectern() }
            val inventory = state.inventory ?: inventoryProxy(state).also { state.inventory = it }
            books.register(inventory, state)
            return fake { method, args ->
                when (method.name) {
                    "getInventory" -> inventory
                    "getPage" -> state.page
                    "setPage" -> {
                        state.page = args!![0] as Int
                        state.pageWrites += 1
                        null
                    }
                    "getType" -> Material.LECTERN
                    "hashCode" -> slot.hashCode()
                    "equals" -> false
                    "toString" -> "FakeLectern($slot)"
                    else -> null
                }
            }
        }

        fun blockAt(slot: Int): Block =
            fake { method, args ->
                when (method.name) {
                    "getType" -> types.getOrDefault(slot, initialType)
                    "setType" -> {
                        types[slot] = args!![0] as Material
                        null
                    }
                    "getState" -> {
                        val current = types.getOrDefault(slot, initialType)
                        if (current == Material.LECTERN) lecternProxy(slot) else null
                    }
                    else -> null
                }
            }

        val world =
            fake<World> { method, args ->
                when (method.name) {
                    "getName" -> worldName
                    "isChunkLoaded" -> true
                    "getBlockAt" -> blockAt(args!![0] as Int)
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
        val outputs =
            (0 until OutputEncoding.ANALOG.outputCount(resolution)).map { slot ->
                DeviceOutput(slot, slot, 64, 0)
            }
        var runtimeState =
            RuntimeState(
                configuration = configuration,
                timeService = ClockRealTimeService(clock, configuration.zoneId, configuration.locale),
                messages = MessageCatalog(emptyMap()),
                redstoneConfiguration = RedstoneConfiguration(enabled = true),
                devices =
                    listOf(
                        RedstoneDevice(
                            id = deviceId,
                            name = "analog-clock",
                            worldName = worldName,
                            enabled = true,
                            outputs = outputs,
                            resolution = resolution,
                            encoding = OutputEncoding.ANALOG,
                        ),
                    ),
            )
        val manager =
            RedstoneDeviceManager(
                server = server,
                scheduler = scheduler,
                stateProvider = { runtimeState },
                logger = Logger.getLogger("test"),
                books = books,
            )

        return Fixture(
            manager = manager,
            books = books,
            startAndRefresh = {
                manager.start(plugin)
                globalCallback!!.accept(scheduledTask)
            },
            tick = { periodicCallback!!.accept(scheduledTask) },
            requestRefresh = {
                manager.requestRefresh()
                globalCallback!!.accept(scheduledTask)
            },
            pendingRegions = { regionCallbacks.size },
            runPendingRegions = {
                val pending = regionCallbacks.toList()
                regionCallbacks.clear()
                pending.forEach { it.accept(scheduledTask) }
            },
            typeAt = { types.getOrDefault(it, initialType) },
            lectern = { lecterns.getOrPut(it) { FakeLectern() } },
            expectedDigits = {
                val snapshot = runtimeState.timeService.snapshot()
                BcdTimeEncoder
                    .encode(snapshot.zonedDateTime.toLocalTime())
                    .values(OutputEncoding.ANALOG, resolution)
            },
            initialState = runtimeState,
            replaceState = { runtimeState = it },
            setInstant = { currentInstant = it },
        )
    }

    private class FakeLectern {
        var page: Int = -1
        var pageWrites: Int = 0
        var inventory: Inventory? = null

        /** Stands in for the book item; tests only ever compare identity. */
        var marker: Any? = null
    }

    /**
     * Tracks the books the manager installs without touching Bukkit's item
     * factory, which needs a running server.
     */
    private class RecordingBooks : AnalogBookFactory {
        private val states = IdentityHashMap<Inventory, FakeLectern>()
        private val owned = IdentityHashMap<Any, UUID>()
        var installs: Int = 0
        var clears: Int = 0

        fun register(
            inventory: Inventory,
            state: FakeLectern,
        ) {
            states[inventory] = state
        }

        fun owns(state: FakeLectern): Boolean = state.marker != null && owned.containsKey(state.marker)

        override fun install(
            inventory: Inventory,
            deviceId: UUID,
        ) {
            val marker = Any()
            owned[marker] = deviceId
            state(inventory).marker = marker
            installs += 1
        }

        override fun holdsDeviceBook(
            inventory: Inventory,
            deviceId: UUID,
        ): Boolean {
            val marker = state(inventory).marker ?: return false
            return owned[marker] == deviceId
        }

        override fun isSlotEmpty(inventory: Inventory): Boolean = state(inventory).marker == null

        override fun clear(inventory: Inventory) {
            val current = state(inventory)
            current.marker?.let(owned::remove)
            current.marker = null
            clears += 1
        }

        private fun state(inventory: Inventory): FakeLectern = states[inventory] ?: error("unknown lectern inventory")
    }

    private class Fixture(
        val manager: RedstoneDeviceManager,
        val books: RecordingBooks,
        val startAndRefresh: () -> Unit,
        val tick: () -> Unit,
        val requestRefresh: () -> Unit,
        val pendingRegions: () -> Int,
        val runPendingRegions: () -> Unit,
        val typeAt: (Int) -> Material,
        val lectern: (Int) -> FakeLectern,
        val expectedDigits: () -> List<Int>,
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
