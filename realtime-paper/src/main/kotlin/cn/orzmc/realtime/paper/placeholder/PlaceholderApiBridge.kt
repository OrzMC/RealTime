package cn.orzmc.realtime.paper.placeholder

import cn.orzmc.realtime.paper.command.RuntimeState
import cn.orzmc.realtime.paper.update.UpdateStatus

/**
 * Loaded only when PlaceholderAPI is installed. Keeping PlaceholderAPI types out
 * of this bridge's public signature allows RealTime to run without that plugin.
 */
object PlaceholderApiBridge {
    fun register(
        pluginVersion: String,
        stateProvider: () -> RuntimeState,
        updateStatus: () -> UpdateStatus = { UpdateStatus.Idle },
    ): Any? {
        val expansion = RealTimePlaceholderExpansion(pluginVersion, stateProvider, updateStatus)
        return expansion.takeIf { it.register() }
    }

    fun unregister(expansion: Any) {
        (expansion as? RealTimePlaceholderExpansion)?.unregister()
    }
}
