package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.paper.command.RealTimeCommandHandler
import cn.orzmc.realtime.paper.command.RuntimeState
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.Collections
import java.util.UUID

/**
 * Tells players who can act on it (holders of `realtime.update`, default op) that a
 * new version is waiting.
 *
 * The notice is sent on join, so it happens on the joining player's region thread
 * and never has to reach across threads — the safe form under Folia. Each player is
 * told once per session; [reset] runs whenever a different version becomes available.
 */
class UpdateNotifier(
    private val status: () -> UpdateStatus,
    private val pluginVersion: String,
    private val stateProvider: () -> RuntimeState?,
) : Listener {
    private val notified: MutableSet<UUID> = Collections.synchronizedSet(mutableSetOf())

    fun reset() {
        notified.clear()
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val state = stateProvider() ?: return
        if (!state.updateConfiguration.enabled || !state.updateConfiguration.notifyPlayers) return
        val player = event.player
        if (!player.hasPermission(RealTimeCommandHandler.UPDATE_PERMISSION)) return

        val message =
            when (val current = status()) {
                is UpdateStatus.Available ->
                    state.messages.render(
                        "command.update.available",
                        mapOf(
                            "version" to current.manifest.version.normalized(),
                            "current" to pluginVersion,
                            "source" to current.manifest.source.configValue,
                        ),
                    )
                is UpdateStatus.Downloaded ->
                    state.messages.render(
                        "command.update.pending",
                        mapOf(
                            "version" to current.manifest.version.normalized(),
                            "file" to current.file.toString(),
                        ),
                    )
                else -> return
            }
        if (!notified.add(player.uniqueId)) return
        player.sendMessage(message)
    }
}
