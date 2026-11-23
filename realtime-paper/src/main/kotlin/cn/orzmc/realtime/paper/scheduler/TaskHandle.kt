package cn.orzmc.realtime.paper.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.concurrent.atomic.AtomicBoolean

class TaskHandle internal constructor(
    private val scheduledTask: ScheduledTask,
    private val onReleased: (TaskHandle) -> Unit,
) {
    private val released = AtomicBoolean(false)

    val isCancelled: Boolean
        get() = scheduledTask.isCancelled

    fun cancel(): ScheduledTask.CancelledState {
        val result = scheduledTask.cancel()
        release()
        return result
    }

    internal fun release() {
        if (released.compareAndSet(false, true)) {
            onReleased(this)
        }
    }
}
