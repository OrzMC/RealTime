package cn.orzmc.realtime.paper.command

import java.util.Locale

class RealTimeCommandHandler(
    private val pluginVersion: String,
    private val currentState: () -> RuntimeState,
    private val reload: () -> Result<RuntimeState>,
) {
    fun execute(
        target: CommandTarget,
        args: Array<out String>,
    ): Boolean {
        if (args.size > 1) {
            sendUsage(target)
            return true
        }

        val state = currentState()
        val action = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "now"
        when (action) {
            "now" -> showNow(target, state)
            "info" -> showInfo(target, state)
            "reload" -> reloadConfiguration(target, state)
            else -> sendUsage(target, state)
        }
        return true
    }

    fun complete(
        target: CommandTarget,
        args: Array<out String>,
    ): List<String> {
        if (args.size != 1) return emptyList()

        val state = currentState()
        val candidates = mutableListOf<String>()
        if (target.hasPermission(COMMAND_PERMISSION)) {
            if (state.configuration.generalEnabled && state.configuration.commandEnabled) {
                candidates += "now"
            }
            candidates += "info"
        }
        if (target.hasPermission(ADMIN_PERMISSION)) {
            candidates += "reload"
        }
        val prefix = args[0].lowercase(Locale.ROOT)
        return candidates.filter { it.startsWith(prefix) }
    }

    private fun showNow(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (!target.hasPermission(COMMAND_PERMISSION)) {
            target.sendMessage(state.messages.render("command.no-permission"))
            return
        }
        if (!state.configuration.generalEnabled || !state.configuration.commandEnabled) {
            target.sendMessage(state.messages.render("command.disabled"))
            return
        }

        val snapshot = state.timeService.snapshot()
        target.sendMessage(
            state.messages.render(
                "command.now",
                mapOf(
                    "datetime" to state.timeService.format(snapshot, state.configuration.commandFormat),
                    "timezone" to state.configuration.zoneId.id,
                ),
            ),
        )
    }

    private fun showInfo(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (!target.hasPermission(COMMAND_PERMISSION)) {
            target.sendMessage(state.messages.render("command.no-permission"))
            return
        }

        val configuration = state.configuration
        target.sendMessage(state.messages.render("command.info.header"))
        target.sendMessage(
            state.messages.render(
                "command.info.version",
                mapOf("version" to pluginVersion),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.info.timezone",
                mapOf("timezone" to configuration.zoneId.id),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.info.locale",
                mapOf("locale" to configuration.locale.toLanguageTag()),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.info.global-enabled",
                mapOf("enabled" to configuration.generalEnabled.toString()),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.info.command-enabled",
                mapOf("enabled" to configuration.commandEnabled.toString()),
            ),
        )
    }

    private fun reloadConfiguration(
        target: CommandTarget,
        previousState: RuntimeState,
    ) {
        if (!target.hasPermission(ADMIN_PERMISSION)) {
            target.sendMessage(previousState.messages.render("command.no-permission"))
            return
        }

        reload().fold(
            onSuccess = { newState ->
                target.sendMessage(newState.messages.render("command.reload.success"))
            },
            onFailure = { exception ->
                target.sendMessage(
                    previousState.messages.render(
                        "command.reload.failure",
                        mapOf("reason" to (exception.message ?: "unknown error")),
                    ),
                )
            },
        )
    }

    private fun sendUsage(
        target: CommandTarget,
        state: RuntimeState = currentState(),
    ) {
        target.sendMessage(state.messages.render("command.usage"))
    }

    companion object {
        const val COMMAND_PERMISSION = "realtime.command"
        const val ADMIN_PERMISSION = "realtime.admin"
    }
}
