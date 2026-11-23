package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceNotFoundException
import java.util.Locale
import java.util.UUID

class RealTimeCommandHandler(
    private val pluginVersion: String,
    private val currentState: () -> RuntimeState,
    private val reload: () -> Result<RuntimeState>,
    private val deviceAdministration: DeviceAdministration? = null,
) {
    fun execute(
        target: CommandTarget,
        args: Array<out String>,
    ): Boolean {
        val state = currentState()
        val action = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "now"
        when (action) {
            "now" -> if (args.isEmpty() || args.size == 1) showNow(target, state) else sendUsage(target, state)
            "info" -> if (args.size == 1) showInfo(target, state) else sendUsage(target, state)
            "reload" -> if (args.size == 1) reloadConfiguration(target, state) else sendUsage(target, state)
            "device" -> handleDevice(target, args.drop(1), state)
            else -> sendUsage(target, state)
        }
        return true
    }

    fun complete(
        target: CommandTarget,
        args: Array<out String>,
    ): List<String> {
        if (args.isEmpty()) return emptyList()

        val state = currentState()
        if (args.size == 1) {
            val candidates = mutableListOf<String>()
            if (target.hasPermission(COMMAND_PERMISSION)) {
                if (state.configuration.generalEnabled && state.configuration.commandEnabled) {
                    candidates += "now"
                }
                candidates += "info"
                if (deviceAdministration != null) candidates += "device"
            }
            if (target.hasPermission(ADMIN_PERMISSION)) {
                candidates += "reload"
            }
            return candidates.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        }

        if (!args[0].equals("device", ignoreCase = true) || deviceAdministration == null) {
            return emptyList()
        }

        val isAdmin = target.hasPermission(ADMIN_PERMISSION)
        val canRead = target.hasPermission(COMMAND_PERMISSION)
        if (args.size == 2) {
            val candidates =
                buildList {
                    if (canRead) {
                        add("list")
                        add("info")
                    }
                    if (isAdmin) {
                        add("enable")
                        add("disable")
                        add("remove")
                    }
                }
            return candidates.filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
        }

        val subcommand = args[1].lowercase(Locale.ROOT)
        val canReadIds = canRead && subcommand == "info"
        val canMutateIds = isAdmin && subcommand in setOf("enable", "disable")
        if (args.size == 3 && (canReadIds || canMutateIds)) {
            val prefix = args[2].lowercase(Locale.ROOT)
            return deviceAdministration
                .list()
                .map { it.id.toString() }
                .filter { it.startsWith(prefix) }
        }

        return emptyList()
    }

    private fun handleDevice(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
    ) {
        val administration = deviceAdministration
        if (administration == null) {
            target.sendMessage(state.messages.render("command.device.unavailable"))
            return
        }
        val action = args.firstOrNull()?.lowercase(Locale.ROOT)
        when (action) {
            "list" -> {
                if (args.size != 1) return sendUsage(target, state)
                if (!target.hasPermission(COMMAND_PERMISSION)) return sendNoPermission(target, state)
                showDeviceList(target, state, administration.list())
            }
            "info" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(COMMAND_PERMISSION)) return sendNoPermission(target, state)
                val id = parseDeviceId(target, state, args[1]) ?: return
                val device = administration.find(id)
                if (device == null) {
                    target.sendMessage(state.messages.render("command.device.not-found", mapOf("id" to id.toString())))
                } else {
                    showDeviceInfo(target, state, device)
                }
            }
            "enable", "disable" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(ADMIN_PERMISSION)) return sendNoPermission(target, state)
                val id = parseDeviceId(target, state, args[1]) ?: return
                val enabled = action == "enable"
                runCatching { administration.setEnabled(id, enabled) }
                    .fold(
                        onSuccess = { device ->
                            val key = if (enabled) "command.device.enable.success" else "command.device.disable.success"
                            target.sendMessage(
                                state.messages.render(
                                    key,
                                    mapOf("name" to device.name, "id" to device.id.toString()),
                                ),
                            )
                        },
                        onFailure = { exception -> sendDeviceFailure(target, state, id, exception) },
                    )
            }
            else -> sendUsage(target, state)
        }
    }

    private fun showDeviceList(
        target: CommandTarget,
        state: RuntimeState,
        devices: List<RedstoneDevice>,
    ) {
        if (devices.isEmpty()) {
            target.sendMessage(state.messages.render("command.device.list.empty"))
            return
        }
        target.sendMessage(
            state.messages.render(
                "command.device.list.header",
                mapOf("count" to devices.size.toString()),
            ),
        )
        devices.forEach { device ->
            target.sendMessage(
                state.messages.render(
                    "command.device.list.item",
                    mapOf(
                        "name" to device.name,
                        "id" to device.id.toString(),
                        "world" to device.worldName,
                        "enabled" to deviceStateLabel(state, device.enabled),
                    ),
                ),
            )
        }
    }

    private fun showDeviceInfo(
        target: CommandTarget,
        state: RuntimeState,
        device: RedstoneDevice,
    ) {
        target.sendMessage(
            state.messages.render(
                "command.device.info.header",
                mapOf("name" to device.name),
            ),
        )
        target.sendMessage(state.messages.render("command.device.info.id", mapOf("id" to device.id.toString())))
        target.sendMessage(state.messages.render("command.device.info.world", mapOf("world" to device.worldName)))
        target.sendMessage(
            state.messages.render(
                "command.device.info.enabled",
                mapOf("enabled" to deviceStateLabel(state, device.enabled)),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.device.info.outputs",
                mapOf("count" to device.outputs.size.toString()),
            ),
        )
        device.outputs.forEach { output ->
            target.sendMessage(
                state.messages.render(
                    "command.device.info.output",
                    mapOf(
                        "bit" to output.bitIndex.toString(),
                        "x" to output.x.toString(),
                        "y" to output.y.toString(),
                        "z" to output.z.toString(),
                    ),
                ),
            )
        }
    }

    private fun deviceStateLabel(
        state: RuntimeState,
        enabled: Boolean,
    ): String = state.messages.render(if (enabled) "command.device.state.enabled" else "command.device.state.disabled")

    private fun parseDeviceId(
        target: CommandTarget,
        state: RuntimeState,
        raw: String,
    ): UUID? =
        runCatching { UUID.fromString(raw) }.getOrElse {
            target.sendMessage(state.messages.render("command.device.invalid-id", mapOf("id" to raw)))
            null
        }

    private fun sendDeviceFailure(
        target: CommandTarget,
        state: RuntimeState,
        id: UUID,
        exception: Throwable,
    ) {
        if (exception is DeviceNotFoundException) {
            target.sendMessage(state.messages.render("command.device.not-found", mapOf("id" to id.toString())))
        } else {
            target.sendMessage(
                state.messages.render(
                    "command.device.error",
                    mapOf("reason" to (exception.message ?: "unknown error")),
                ),
            )
        }
    }

    private fun showNow(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (!target.hasPermission(COMMAND_PERMISSION)) {
            sendNoPermission(target, state)
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
            sendNoPermission(target, state)
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
            sendNoPermission(target, previousState)
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

    private fun sendNoPermission(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        target.sendMessage(state.messages.render("command.no-permission"))
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
