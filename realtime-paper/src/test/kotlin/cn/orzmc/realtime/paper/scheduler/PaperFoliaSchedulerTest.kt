package cn.orzmc.realtime.paper.scheduler

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.function.Consumer

class PaperFoliaSchedulerTest {
    @Test
    fun `global one shot dispatches and releases its handle`() {
        var callback: Consumer<ScheduledTask>? = null
        val scheduledTask = fakeScheduledTask()
        val global =
            fake<GlobalRegionScheduler> { method, args ->
                if (method.name == "run") {
                    callback = args!![1] as Consumer<ScheduledTask>
                    scheduledTask
                } else {
                    null
                }
            }
        val scheduler = scheduler(global = global)
        var executed = false

        scheduler.runGlobal { executed = true }

        assertEquals(1, scheduler.activeTaskCount)
        callback!!.accept(scheduledTask)
        assertTrue(executed)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun `region scheduler receives a defensive location copy`() {
        var scheduledLocation: Location? = null
        var callback: Consumer<ScheduledTask>? = null
        val scheduledTask = fakeScheduledTask()
        val region =
            fake<RegionScheduler> { method, args ->
                if (method.name == "run") {
                    scheduledLocation = args!![1] as Location
                    callback = args[2] as Consumer<ScheduledTask>
                    scheduledTask
                } else {
                    null
                }
            }
        val scheduler = scheduler(region = region)
        val original = Location(null, 10.0, 64.0, -5.0)
        var executed = false

        scheduler.runRegion(original) { executed = true }

        assertNotSame(original, scheduledLocation)
        original.x = 999.0
        assertEquals(10.0, scheduledLocation!!.x)
        callback!!.accept(scheduledTask)
        assertTrue(executed)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun `entity scheduler runs callback and releases task handle`() {
        var callback: Consumer<ScheduledTask>? = null
        var retiredCallback: Runnable? = null
        val scheduledTask = fakeScheduledTask()
        val entityScheduler =
            fake<EntityScheduler> { method, args ->
                if (method.name == "run") {
                    callback = args!![1] as Consumer<ScheduledTask>
                    retiredCallback = args[2] as Runnable
                    scheduledTask
                } else {
                    null
                }
            }
        val entity = fake<Entity> { method, _ -> if (method.name == "getScheduler") entityScheduler else null }
        val scheduler = scheduler()
        var executed = false
        var retired = false

        scheduler.runEntity(entity, { executed = true }, { retired = true })

        callback!!.accept(scheduledTask)
        assertTrue(executed)
        assertFalse(retired)
        assertEquals(0, scheduler.activeTaskCount)
        assertTrue(retiredCallback != null)
    }

    @Test
    fun `entity retirement releases tracked task and invokes callback`() {
        var retiredCallback: Runnable? = null
        val scheduledTask = fakeScheduledTask()
        val entityScheduler =
            fake<EntityScheduler> { method, args ->
                if (method.name == "run") {
                    retiredCallback = args!![2] as Runnable
                    scheduledTask
                } else {
                    null
                }
            }
        val entity = fake<Entity> { method, _ -> if (method.name == "getScheduler") entityScheduler else null }
        val scheduler = scheduler()
        var retired = false

        scheduler.runEntity(entity, {}, { retired = true })

        retiredCallback!!.run()
        assertTrue(retired)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun `cancel all cancels tracked repeating tasks`() {
        val scheduledTask = fakeScheduledTask()
        val global =
            fake<GlobalRegionScheduler> { method, _ ->
                if (method.name == "runAtFixedRate") scheduledTask else null
            }
        val scheduler = scheduler(global = global)

        scheduler.repeatGlobal(initialDelayTicks = 1, periodTicks = 20) {}

        assertEquals(1, scheduler.activeTaskCount)
        assertEquals(1, scheduler.cancelAll())
        assertTrue(scheduledTask.isCancelled)
        assertEquals(0, scheduler.activeTaskCount)
    }

    @Test
    fun `repeating schedules reject zero and negative tick values`() {
        val scheduler = scheduler()

        assertThrowsIllegalArgument { scheduler.repeatGlobal(0, 1) {} }
        assertThrowsIllegalArgument { scheduler.repeatGlobal(1, 0) {} }
    }

    private fun scheduler(
        global: GlobalRegionScheduler = fake { _, _ -> fakeScheduledTask() },
        region: RegionScheduler = fake { _, _ -> fakeScheduledTask() },
    ) = PaperFoliaScheduler(
        plugin = fakePlugin(),
        globalScheduler = global,
        regionScheduler = region,
    )

    private fun fakePlugin(): Plugin = fake { _, _ -> null }

    private fun fakeScheduledTask(): ScheduledTask {
        var cancelled = false
        return fake { method, _ ->
            when (method.name) {
                "cancel" -> {
                    cancelled = true
                    ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                }
                "isCancelled" -> cancelled
                "isRepeatingTask" -> true
                else -> null
            }
        }
    }

    private inline fun <reified T> fake(
        noinline handler: (Method, Array<out Any?>?) -> Any?,
    ): T {
        val type = T::class.java
        val instance =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                handler(method, args)
            }
        return type.cast(instance)
    }

    private fun assertThrowsIllegalArgument(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
