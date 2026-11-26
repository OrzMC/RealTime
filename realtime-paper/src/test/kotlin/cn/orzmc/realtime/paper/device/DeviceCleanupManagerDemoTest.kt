package cn.orzmc.realtime.paper.device

import cn.orzmc.realtime.core.device.DemoBlock
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
 * Retiring a demo device also clears the display blocks it placed, and never
 * touches a block whose type no longer matches.
 */
class DeviceCleanupManagerDemoTest {
    @Test
    fun `clears the display blocks of a retired demo`() {
        val fixture =
            fixture(
                displayType = Material.REDSTONE_LAMP,
            )

        fixture.process()

        assertEquals(Material.AIR, fixture.types[Triple(0, 65, 0)])
        assertNull(fixture.remainingJobStatus(), "the job is fully resolved")
    }

    @Test
    fun `clears a display entry that is not the first one in the job`() {
        // Regression: the world scan indexed the filtered list, so once every
        // earlier display entry was CLEARED the remaining PENDING entry was
        // addressed with index 0, looked up a CLEARED entry and never cleared.
        val fixture =
            fixture(
                displayType = Material.REDSTONE_LAMP,
                displayStatuses =
                    listOf(
                        DeviceCleanupStatus.CLEARED,
                        DeviceCleanupStatus.CLEARED,
                        DeviceCleanupStatus.PENDING,
                    ),
            )

        fixture.process()

        assertEquals(Material.AIR, fixture.types[Triple(8, 65, 0)])
        assertNull(fixture.remainingJobStatus(), "the job is fully resolved")
    }

    @Test
    fun `keeps a display block that a player replaced`() {
        val fixture = fixture(displayType = Material.GLOWSTONE)

        fixture.process()

        assertEquals(Material.GLOWSTONE, fixture.types[Triple(0, 65, 0)])
        assertEquals(DeviceCleanupStatus.NEEDS_ATTENTION, fixture.remainingDisplayStatus())
    }

    private fun fixture(
        displayType: Material,
        displayStatuses: List<DeviceCleanupStatus> = listOf(DeviceCleanupStatus.PENDING),
    ): Fixture {
        val worldId = UUID.randomUUID()
        val types = mutableMapOf<Triple<Int, Int, Int>, Material>()
        types[Triple(0, 64, 0)] = Material.AIR
        displayStatuses.forEachIndexed { index, status ->
            if (status != DeviceCleanupStatus.CLEARED) {
                types[Triple(index * 4, 65, 0)] = displayType
            }
        }

        fun blockAt(
            x: Int,
            y: Int,
            z: Int,
        ): Block {
            val key = Triple(x, y, z)
            return fake { method, args ->
                when (method.name) {
                    "getType" -> types.getOrDefault(key, Material.AIR)
                    "setType" -> {
                        types[key] = args!![0] as Material
                        null
                    }
                    "getState" -> null
                    else -> null
                }
            }
        }
        val world =
            fake<World> { method, args ->
                when (method.name) {
                    "getName" -> "world"
                    "getUID" -> worldId
                    "isChunkLoaded" -> true
                    "getBlockAt" -> blockAt(args!![0] as Int, args[1] as Int, args[2] as Int)
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
                name = "demo-retired",
                worldName = "world",
                enabled = false,
                outputs = (0 until 4).map { index -> DeviceOutput(index, index, 64, 0) },
                resolution = OutputResolution.MINUTE,
                demoBlocks =
                    displayStatuses.indices.map { index ->
                        DemoBlock(index * 4, 65, 0, "minecraft:redstone_lamp")
                    },
            )
        val base = DeviceCleanupJob.fromDevice(device, worldId)
        val job =
            base.copy(
                display =
                    base.display.mapIndexed { index, entry ->
                        DeviceCleanupDemo(entry.block, displayStatuses[index])
                    },
            )
        var activeState = state(job)
        val manager =
            DeviceCleanupManager(
                server = fake<Server> { _, _ -> null },
                scheduler = scheduler,
                stateLock = Any(),
                stateProvider = { activeState },
                persistStore = {},
                replaceState = { activeState = it },
                logger = Logger.getLogger("cleanup-demo-test"),
            )
        val event = ChunkLoadEvent(chunk, false)

        return Fixture(
            types = types,
            remainingJobStatus = {
                activeState.cleanupJobs
                    .singleOrNull()
                    ?.outputs
                    ?.first()
                    ?.status
            },
            remainingDisplayStatus = {
                activeState.cleanupJobs
                    .singleOrNull()
                    ?.display
                    ?.first()
                    ?.status
            },
            process = {
                manager.onChunkLoad(event)
                globalCallbacks.first().accept(scheduledTask)
                regionCallbacks.toList().forEach { it.accept(scheduledTask) }
                globalCallbacks.toList().forEach { it.accept(scheduledTask) }
            },
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

    private class Fixture(
        val types: MutableMap<Triple<Int, Int, Int>, Material>,
        val remainingJobStatus: () -> DeviceCleanupStatus?,
        val remainingDisplayStatus: () -> DeviceCleanupStatus?,
        val process: () -> Unit,
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
