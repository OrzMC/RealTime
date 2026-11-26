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
import org.bukkit.block.Lectern
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * Analog cleanup removes the lectern the plugin installed, but only while it
 * still holds this device's book.
 */
class DeviceCleanupManagerAnalogTest {
    @Test
    fun `removes a lectern that still holds this device book`() {
        val fixture = fixture(initialType = Material.LECTERN, bookOwner = OWN_DEVICE)

        fixture.runFirstChunkLoad()

        assertEquals(Material.AIR, fixture.currentType())
        assertNull(fixture.remainingJobStatus(), "a fully cleared job is removed from the store")
    }

    @Test
    fun `leaves a lectern with a foreign book for an operator`() {
        val fixture = fixture(initialType = Material.LECTERN, bookOwner = null, foreignBook = true)

        fixture.runFirstChunkLoad()

        assertEquals(Material.LECTERN, fixture.currentType())
        assertEquals(DeviceCleanupStatus.NEEDS_ATTENTION, fixture.remainingJobStatus())
        assertEquals(0, fixture.books.clears)
    }

    @Test
    fun `clears a blank lectern left behind by a disabled device`() {
        // Disabling a device removes its book but keeps the lectern, so
        // retirement has to recognise a blank lectern as this device's slot.
        val fixture = fixture(initialType = Material.LECTERN, bookOwner = null)

        fixture.runFirstChunkLoad()

        assertEquals(Material.AIR, fixture.currentType())
        assertNull(fixture.remainingJobStatus(), "the job is fully resolved")
    }

    @Test
    fun `empties the book before removing the lectern`() {
        val fixture = fixture(initialType = Material.LECTERN, bookOwner = OWN_DEVICE)

        fixture.runFirstChunkLoad()

        assertEquals(1, fixture.books.clears, "the book is cleared so no item entity drops")
        assertEquals(Material.AIR, fixture.currentType())
    }

    @Test
    fun `treats an empty analog slot as cleared`() {
        val fixture = fixture(initialType = Material.AIR)

        fixture.runFirstChunkLoad()

        assertEquals(Material.AIR, fixture.currentType())
        assertNull(fixture.remainingJobStatus(), "a fully cleared job is removed from the store")
    }

    @Test
    fun `reports a redstone block left in an analog slot`() {
        val fixture = fixture(initialType = Material.REDSTONE_BLOCK)

        fixture.runFirstChunkLoad()

        assertEquals(Material.REDSTONE_BLOCK, fixture.currentType())
        assertEquals(DeviceCleanupStatus.NEEDS_ATTENTION, fixture.remainingJobStatus())
    }

    private fun fixture(
        initialType: Material,
        bookOwner: UUID? = OWN_DEVICE,
        foreignBook: Boolean = false,
    ): Fixture {
        val worldId = UUID.randomUUID()
        var currentType = initialType
        var blockAccessed = false
        val books = RecordingBooks()
        var lecternInventory: Inventory? = null
        val block =
            fake<Block> { method, args ->
                when (method.name) {
                    "getType" -> currentType
                    "setType" -> {
                        currentType = args!![0] as Material
                        null
                    }
                    "getState" -> {
                        if (currentType != Material.LECTERN) {
                            null
                        } else {
                            val inventory =
                                lecternInventory ?: fake<Inventory> { _, _ -> null }.also {
                                    lecternInventory = it
                                    books.register(it, bookOwner, foreignBook)
                                }
                            fake<Lectern> { lecternMethod, _ ->
                                when (lecternMethod.name) {
                                    "getInventory" -> inventory
                                    else -> null
                                }
                            }
                        }
                    }
                    else -> null
                }
            }
        val world =
            fake<World> { method, _ ->
                when (method.name) {
                    "getName" -> "world"
                    "getUID" -> worldId
                    "isChunkLoaded" -> true
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
                id = OWN_DEVICE,
                name = "retired-analog-clock",
                worldName = "world",
                enabled = false,
                outputs =
                    (0 until 4).map { slot ->
                        DeviceOutput(bitIndex = slot, x = slot * 2, y = 64, z = 0)
                    },
                resolution = OutputResolution.MINUTE,
            )
        val job = DeviceCleanupJob.fromDevice(device, worldId)
        var activeState = state(job)
        var persisted: DeviceStoreSnapshot? = null
        val manager =
            DeviceCleanupManager(
                server = fake<Server> { _, _ -> null },
                scheduler = scheduler,
                stateLock = Any(),
                stateProvider = { activeState },
                persistStore = {
                    persisted = it
                },
                replaceState = { activeState = it },
                logger = Logger.getLogger("cleanup-analog-test"),
                books = books,
            )

        return Fixture(
            manager = manager,
            event = event,
            regionCallbacks = regionCallbacks,
            globalCallbacks = globalCallbacks,
            scheduledTask = scheduledTask,
            currentType = { currentType },
            remainingJobStatus = {
                activeState.cleanupJobs
                    .singleOrNull()
                    ?.outputs
                    ?.firstOrNull()
                    ?.status
            },
            accessed = { blockAccessed },
            books = books,
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

    private class RecordingBooks : AnalogBookFactory {
        /** Lectern inventory identity -> the device that owns the book in it. */
        private val owners = mutableMapOf<Int, UUID>()
        var clears: Int = 0

        fun register(
            inventory: Inventory,
            owner: UUID?,
            foreignBook: Boolean,
        ) {
            val key = System.identityHashCode(inventory)
            when {
                foreignBook -> owners[key] = FOREIGN_OWNER
                owner != null -> owners[key] = owner
                else -> owners.remove(key)
            }
        }

        override fun install(
            inventory: Inventory,
            deviceId: UUID,
        ) {
            owners[System.identityHashCode(inventory)] = deviceId
        }

        override fun holdsDeviceBook(
            inventory: Inventory,
            deviceId: UUID,
        ): Boolean = owners[System.identityHashCode(inventory)] == deviceId

        override fun isSlotEmpty(inventory: Inventory): Boolean = !owners.containsKey(System.identityHashCode(inventory))

        override fun clear(inventory: Inventory) {
            owners.remove(System.identityHashCode(inventory))
            clears += 1
        }
    }

    private class Fixture(
        val manager: DeviceCleanupManager,
        val event: ChunkLoadEvent,
        val regionCallbacks: List<Consumer<ScheduledTask>>,
        val globalCallbacks: List<Consumer<ScheduledTask>>,
        val scheduledTask: ScheduledTask,
        val currentType: () -> Material,
        val remainingJobStatus: () -> DeviceCleanupStatus?,
        val accessed: () -> Boolean,
        val books: RecordingBooks,
    ) {
        /** Loads the chunk, lets every scheduled output run, then drains the results. */
        fun runFirstChunkLoad() {
            manager.onChunkLoad(event)
            globalCallbacks.first().accept(scheduledTask)
            regionCallbacks.toList().forEach { it.accept(scheduledTask) }
            globalCallbacks.toList().forEach { it.accept(scheduledTask) }
        }
    }

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

    private companion object {
        val OWN_DEVICE: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val FOREIGN_OWNER: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
    }
}
