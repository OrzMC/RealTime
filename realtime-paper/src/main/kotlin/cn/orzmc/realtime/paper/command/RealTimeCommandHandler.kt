package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupJobNotFoundException
import cn.orzmc.realtime.paper.device.DeviceDiagnostics
import cn.orzmc.realtime.paper.device.DeviceNotFoundException
import cn.orzmc.realtime.paper.device.RedstoneDiagnosticsSnapshot
import java.util.Locale
import java.util.UUID

class RealTimeCommandHandler(
    private val pluginVersion: String,
    private val currentState: () -> RuntimeState,
    private val reload: () -> Result<RuntimeState>,
    private val deviceAdministration: DeviceAdministration? = null,
    private val deviceDiagnostics: DeviceDiagnostics? = null,
    private val cleanupAdministration: DeviceCleanupAdministration? = null,
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
                        add("status")
                        if (cleanupAdministration != null) add("cleanup")
                    }
                    if (isAdmin) {
                        add("enable")
                        add("disable")
                    }
                }
            return candidates.filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
        }

        val subcommand = args[1].lowercase(Locale.ROOT)
        if (args.size == 3 && subcommand == "cleanup" && cleanupAdministration != null) {
            val candidates =
                buildList {
                    if (canRead) add("list")
                    if (isAdmin) add("retry")
                }
            return candidates.filter { it.startsWith(args[2].lowercase(Locale.ROOT)) }
        }

        if (args.size == 4 && subcommand == "cleanup" && args[2].equals("retry", true) && isAdmin) {
            val prefix = args[3].lowercase(Locale.ROOT)
            return cleanupAdministration
                ?.list()
                .orEmpty()
                .map { it.id.toString() }
                .filter { it.startsWith(prefix) }
        }

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
            "cleanup" -> handleDeviceCleanup(target, args.drop(1), state)
            "status" -> {
                if (args.size != 1) return sendUsage(target, state)
                if (!target.hasPermission(COMMAND_PERMISSION)) return sendNoPermission(target, state)
                val diagnostics = deviceDiagnostics
                if (diagnostics == null) {
                    target.sendMessage(state.messages.render("command.device.unavailable"))
                } else {
                    showDeviceStatus(target, state, diagnostics.snapshot())
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

    private fun handleDeviceCleanup(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
    ) {
        val administration = cleanupAdministration
        if (administration == null) {
            target.sendMessage(state.messages.render("command.device.unavailable"))
            return
        }

        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "list" -> {
                if (args.size != 1) return sendUsage(target, state)
                if (!target.hasPermission(COMMAND_PERMISSION)) return sendNoPermission(target, state)
                val jobs = administration.list()
                if (jobs.isEmpty()) {
                    target.sendMessage(state.messages.render("command.device.cleanup.empty"))
                    return
                }
                target.sendMessage(
                    state.messages.render(
                        "command.device.cleanup.header",
                        mapOf("count" to jobs.size.toString()),
                    ),
                )
                jobs.forEach { job ->
                    target.sendMessage(
                        state.messages.render(
                            "command.device.cleanup.item",
                            mapOf(
                                "name" to job.deviceName,
                                "id" to job.id.toString(),
                                "world" to job.worldName,
                                "pending" to job.outputs.count { it.status.name == "PENDING" }.toString(),
                                "attention" to job.outputs.count { it.status.name == "NEEDS_ATTENTION" }.toString(),
                            ),
                        ),
                    )
                }
            }
            "retry" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(ADMIN_PERMISSION)) return sendNoPermission(target, state)
                val id = parseCleanupJobId(target, state, args[1]) ?: return
                runCatching { administration.retry(id) }
                    .fold(
                        onSuccess = { job ->
                            target.sendMessage(
                                state.messages.render(
                                    "command.device.cleanup.retry.success",
                                    mapOf("id" to job.id.toString()),
                                ),
                            )
                        },
                        onFailure = { exception ->
                            val key =
                                if (exception is DeviceCleanupJobNotFoundException) {
                                    "command.device.cleanup.not-found"
                                } else {
                                    "command.device.cleanup.error"
                                }
                            target.sendMessage(
                                state.messages.render(
                                    key,
                                    mapOf("id" to id.toString(), "reason" to (exception.message ?: "unknown error")),
                                ),
                            )
                        },
                    )
            }
            else -> sendUsage(target, state)
        }
    }

    private fun parseCleanupJobId(
        target: CommandTarget,
        state: RuntimeState,
        raw: String,
    ): UUID? =
        runCatching { UUID.fromString(raw) }.getOrElse {
            target.sendMessage(state.messages.render("command.device.cleanup.invalid-id", mapOf("id" to raw)))
            null
        }

    private fun showDeviceStatus(
        target: CommandTarget,
        state: RuntimeState,
        snapshot: RedstoneDiagnosticsSnapshot,
    ) {
        target.sendMessage(state.messages.render("command.device.status.header"))
        target.sendMessage(
            state.messages.render(
                "command.device.status.devices",
                mapOf(
                    "configured" to snapshot.configuredDevices.toString(),
                    "enabled" to snapshot.enabledDevices.toString(),
                    "outputs" to snapshot.configuredOutputs.toString(),
                ),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.device.status.checks",
                mapOf(
                    "checks" to snapshot.outputChecks.toString(),
                    "unloaded" to snapshot.unloadedChunkSkips.toString(),
                    "stale" to snapshot.staleOutputUpdates.toString(),
                ),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.device.status.outcomes",
                mapOf(
                    "changed" to snapshot.powerChanges.toString(),
                    "unchanged" to snapshot.unchangedOutputs.toString(),
                    "invalid" to snapshot.invalidOutputBlocks.toString(),
                    "failures" to snapshot.writeFailures.toString(),
                ),
            ),
        )
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
