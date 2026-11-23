package cn.orzmc.realtime.paper.scheduler

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Ownership-aware scheduler facade for Paper and Folia.
 *
 * Global callbacks are for server-wide state, region callbacks are for location-owned
 * world/block work, and entity callbacks follow the entity across region transfers.
 * This facade intentionally exposes no generic asynchronous Bukkit callback.
 */
class PaperFoliaScheduler(
    private val plugin: Plugin,
    private val globalScheduler: GlobalRegionScheduler,
    private val regionScheduler: RegionScheduler,
    private val registry: TaskRegistry = TaskRegistry(),
) {
    val activeTaskCount: Int
        get() = registry.activeTaskCount

    fun runGlobal(action: () -> Unit): TaskHandle =
        registerOneShot(
            schedule = { callback -> globalScheduler.run(plugin, callback) },
            action = action,
        )

    fun repeatGlobal(
        initialDelayTicks: Long,
        periodTicks: Long,
        action: () -> Unit,
    ): TaskHandle {
        requireValidPeriod(initialDelayTicks, periodTicks)
        val task =
            globalScheduler.runAtFixedRate(
                plugin,
                Consumer { action() },
                initialDelayTicks,
                periodTicks,
            )
        return registry.register(task)
    }

    fun runRegion(
        location: Location,
        action: (Location) -> Unit,
    ): TaskHandle {
        val target = location.clone()
        return registerOneShot(
            schedule = { callback -> regionScheduler.run(plugin, target, callback) },
            action = { action(target) },
        )
    }

    fun repeatRegion(
        location: Location,
        initialDelayTicks: Long,
        periodTicks: Long,
        action: (Location) -> Unit,
    ): TaskHandle {
        requireValidPeriod(initialDelayTicks, periodTicks)
        val target = location.clone()
        val task =
            regionScheduler.runAtFixedRate(
                plugin,
                target,
                Consumer { action(target) },
                initialDelayTicks,
                periodTicks,
            )
        return registry.register(task)
    }

    /**
     * Schedules work on the entity's owning region. The retired callback must not
     * access the entity, worlds, chunks, or blocks.
     *
     * Returns null if the entity scheduler has already been retired.
     */
    fun runEntity(
        entity: Entity,
        action: (Entity) -> Unit,
        retired: () -> Unit,
    ): TaskHandle? {
        val handleReference = AtomicReference<TaskHandle?>()
        val terminal = AtomicBoolean(false)
        val scheduledTask =
            entity.scheduler.run(
                plugin,
                Consumer {
                    try {
                        action(entity)
                    } finally {
                        terminal.set(true)
                        handleReference.get()?.release()
                    }
                },
                Runnable {
                    terminal.set(true)
                    handleReference.get()?.release()
                    retired()
                },
            ) ?: return null

        val handle = registry.register(scheduledTask)
        handleReference.set(handle)
        if (terminal.get()) handle.release()
        return handle
    }

    fun repeatEntity(
        entity: Entity,
        initialDelayTicks: Long,
        periodTicks: Long,
        action: (Entity) -> Unit,
        retired: () -> Unit,
    ): TaskHandle? {
        requireValidPeriod(initialDelayTicks, periodTicks)
        val handleReference = AtomicReference<TaskHandle?>()
        val retiredBeforeRegistration = AtomicBoolean(false)
        val scheduledTask =
            entity.scheduler.runAtFixedRate(
                plugin,
                Consumer { action(entity) },
                Runnable {
                    retiredBeforeRegistration.set(true)
                    handleReference.get()?.release()
                    retired()
                },
                initialDelayTicks,
                periodTicks,
            ) ?: return null

        val handle = registry.register(scheduledTask)
        handleReference.set(handle)
        if (retiredBeforeRegistration.get()) handle.release()
        return handle
    }

    fun cancelAll(): Int = registry.cancelAll()

    private fun registerOneShot(
        schedule: (Consumer<ScheduledTask>) -> ScheduledTask,
        action: () -> Unit,
    ): TaskHandle {
        val handleReference = AtomicReference<TaskHandle?>()
        val completed = AtomicBoolean(false)
        val scheduledTask =
            schedule(
                Consumer {
                    try {
                        action()
                    } finally {
                        completed.set(true)
                        handleReference.get()?.release()
                    }
                },
            )

        val handle = registry.register(scheduledTask)
        handleReference.set(handle)
        if (completed.get()) handle.release()
        return handle
    }

    private fun requireValidPeriod(
        initialDelayTicks: Long,
        periodTicks: Long,
    ) {
        require(initialDelayTicks >= 1) { "initialDelayTicks must be at least 1" }
        require(periodTicks >= 1) { "periodTicks must be at least 1" }
    }
}
