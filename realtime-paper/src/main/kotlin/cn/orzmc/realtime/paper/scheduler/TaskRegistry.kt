package cn.orzmc.realtime.paper.scheduler

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.concurrent.ConcurrentHashMap

class TaskRegistry {
    private val handles = ConcurrentHashMap.newKeySet<TaskHandle>()

    val activeTaskCount: Int
        get() = handles.size

    internal fun register(scheduledTask: ScheduledTask): TaskHandle =
        TaskHandle(scheduledTask, ::remove).also(handles::add)

    fun cancelAll(): Int {
        val snapshot = handles.toList()
        snapshot.forEach(TaskHandle::cancel)
        return snapshot.size
    }

    private fun remove(handle: TaskHandle) {
        handles.remove(handle)
    }
}
