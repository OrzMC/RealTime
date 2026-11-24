package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.DeviceLayout
import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceAreaNotAllowedException
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupJobNotFoundException
import cn.orzmc.realtime.paper.device.DeviceCleanupStatus
import cn.orzmc.realtime.paper.device.DeviceCreateRequest
import cn.orzmc.realtime.paper.device.DeviceDiagnostics
import cn.orzmc.realtime.paper.device.DeviceLimitReachedException
import cn.orzmc.realtime.paper.device.DeviceMustBeDisabledException
import cn.orzmc.realtime.paper.device.DeviceNotFoundException
import cn.orzmc.realtime.paper.device.DeviceOutputConflictException
import cn.orzmc.realtime.paper.device.DeviceOutputIndexException
import cn.orzmc.realtime.paper.device.DeviceRetirementAdministration
import cn.orzmc.realtime.paper.device.RedstoneDiagnosticsSnapshot
import java.util.Locale
import java.util.UUID
import java.util.logging.Logger

class RealTimeCommandHandler(
    private val pluginVersion: String,
    private val currentState: () -> RuntimeState,
    private val reload: () -> Result<RuntimeState>,
    private val deviceAdministration: DeviceAdministration? = null,
    private val deviceDiagnostics: DeviceDiagnostics? = null,
    private val cleanupAdministration: DeviceCleanupAdministration? = null,
    private val deviceRetirement: DeviceRetirementAdministration? = null,
    private val logger: Logger? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastEditAt = mutableMapOf<UUID, Long>()

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

        val isAdmin = target.hasPermission(TOGGLE_PERMISSION)
        val canRead = target.hasPermission(VIEW_PERMISSION)
        if (args.size == 2) {
            val candidates =
                buildList {
                    if (canRead) {
                        add("list")
                        add("info")
                        add("status")
                        if (cleanupAdministration != null) add("cleanup")
                    }
                    if (target.hasPermission(EDIT_PERMISSION)) {
                        add("create")
                        add("set")
                    }
                    if (isAdmin) {
                        add("enable")
                        add("disable")
                    }
                    if (deviceRetirement != null && target.hasPermission(RETIRE_PERMISSION)) {
                        add("remove")
                    }
                }
            return candidates.filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
        }

        val subcommand = args[1].lowercase(Locale.ROOT)
        if (args.size == 3 && subcommand == "cleanup" && cleanupAdministration != null) {
            val candidates =
                buildList {
                    if (canRead) add("list")
                    if (target.hasPermission(RETIRE_PERMISSION)) add("retry")
                }
            return candidates.filter { it.startsWith(args[2].lowercase(Locale.ROOT)) }
        }

        if (args.size == 4 &&
            subcommand == "cleanup" &&
            args[2].equals("retry", true) &&
            target.hasPermission(RETIRE_PERMISSION)
        ) {
            val prefix = args[3].lowercase(Locale.ROOT)
            return cleanupAdministration
                ?.list()
                .orEmpty()
                .filter { job -> job.outputs.any { it.status == DeviceCleanupStatus.NEEDS_ATTENTION } }
                .map { it.id.toString() }
                .filter { it.startsWith(prefix) }
        }

        if (subcommand == "create") {
            val prefix = args.last().lowercase(Locale.ROOT)
            val candidates =
                when (args.size) {
                    4 -> OutputResolution.entries.map { it.configValue }
                    9 -> LayoutAxis.entries.map { it.configValue }
                    in 10..11 -> CREATE_TAIL
                    else -> emptyList()
                }
            return candidates.filter { it.startsWith(prefix) }
        }

        val canReadIds = canRead && subcommand == "info"
        val canMutateIds =
            (isAdmin && subcommand in setOf("enable", "disable")) ||
                (target.hasPermission(RETIRE_PERMISSION) && subcommand == "remove") ||
                (target.hasPermission(EDIT_PERMISSION) && subcommand == "set")
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
                if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
                showDeviceList(target, state, administration.list())
            }
            "info" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
                val id = parseDeviceId(target, state, args[1]) ?: return
                val device = administration.find(id)
                if (device == null) {
                    target.sendMessage(state.messages.render("command.device.not-found", mapOf("id" to id.toString())))
                } else {
                    showDeviceInfo(target, state, device)
                }
            }
            "cleanup" -> handleDeviceCleanup(target, args.drop(1), state)
            "remove" -> handleDeviceRemove(target, args.drop(1), state)
            "status" -> {
                if (args.size != 1) return sendUsage(target, state)
                if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
                val diagnostics = deviceDiagnostics
                if (diagnostics == null) {
                    target.sendMessage(state.messages.render("command.device.unavailable"))
                } else {
                    showDeviceStatus(target, state, diagnostics.snapshot())
                }
            }
            "create" -> handleDeviceCreate(target, args.drop(1), state, administration)
            "set" -> handleDeviceSet(target, args.drop(1), state, administration)
            "enable", "disable" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(TOGGLE_PERMISSION)) return sendNoPermission(target, state)
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
                if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
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
                                "pending" to job.outputs.count { it.status == DeviceCleanupStatus.PENDING }.toString(),
                                "attention" to job.outputs.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }.toString(),
                            ),
                        ),
                    )
                }
            }
            "retry" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(RETIRE_PERMISSION)) return sendNoPermission(target, state)
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

    private fun handleDeviceRemove(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
    ) {
        val retirement = deviceRetirement
        if (retirement == null) {
            target.sendMessage(state.messages.render("command.device.unavailable"))
            return
        }
        if (args.size != 1) return sendUsage(target, state)
        if (!target.hasPermission(RETIRE_PERMISSION)) return sendNoPermission(target, state)
        val id = parseDeviceId(target, state, args[0]) ?: return

        runCatching { retirement.retire(id) }
            .fold(
                onSuccess = { job ->
                    target.sendMessage(
                        state.messages.render(
                            "command.device.remove.success",
                            mapOf(
                                "id" to id.toString(),
                                "job" to job.id.toString(),
                                "name" to job.deviceName,
                            ),
                        ),
                    )
                },
                onFailure = { exception ->
                    val key =
                        when (exception) {
                            is DeviceMustBeDisabledException -> "command.device.remove.must-disable"
                            is DeviceNotFoundException -> "command.device.remove.not-found"
                            else -> "command.device.remove.error"
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
        if (snapshot.lecternPlacements > 0 || snapshot.bookWrites > 0) {
            target.sendMessage(
                state.messages.render(
                    "command.device.status.analog",
                    mapOf(
                        "lecterns" to snapshot.lecternPlacements.toString(),
                        "books" to snapshot.bookWrites.toString(),
                    ),
                ),
            )
        }
    }

    private fun handleDeviceCreate(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        // args: <name> <minute|second> <world> <x> <y> <z> <x+|x-|z+|z-> [stride] [bcd|analog]
        if (args.size !in 7..9) return sendUsage(target, state)

        val name = args[0]
        val resolution = OutputResolution.fromConfig(args[1].lowercase(Locale.ROOT))
        if (resolution == null) {
            target.sendMessage(
                state.messages.render(
                    "command.device.create.invalid-resolution",
                    mapOf("value" to args[1]),
                ),
            )
            return
        }
        val worldName = args[2]
        val x = args[3].toIntOrNull()
        val y = args[4].toIntOrNull()
        val z = args[5].toIntOrNull()
        val axis = LayoutAxis.fromConfig(args[6])
        val optional = args.drop(7)
        val stride = optional.firstNotNullOfOrNull { it.toIntOrNull() }
        val encodingValue = optional.firstOrNull { it.toIntOrNull() == null }
        val encoding =
            when {
                encodingValue == null -> OutputEncoding.BCD
                else ->
                    OutputEncoding.fromConfig(encodingValue) ?: run {
                        target.sendMessage(
                            state.messages.render(
                                "command.device.create.invalid-encoding",
                                mapOf("value" to encodingValue),
                            ),
                        )
                        return
                    }
            }
        if (x == null || y == null || z == null || axis == null || (stride != null && stride !in 1..MAX_STRIDE)) {
            return sendUsage(target, state)
        }
        if (optional.count { it.toIntOrNull() != null } > 1 || optional.size > 2) return sendUsage(target, state)
        if (!acquireEditSlot(target, state)) return

        val layout =
            if (stride == null) {
                DeviceLayout.default(encoding)
            } else {
                DeviceLayout.default(encoding).copy(stride = stride)
            }
        val outputs =
            runCatching { layout.outputs(encoding, resolution, axis, x, y, z) }.getOrElse {
                return sendUsage(target, state)
            }

        runCatching {
            administration.create(
                DeviceCreateRequest(
                    name = name,
                    worldName = worldName,
                    resolution = resolution,
                    outputs = outputs,
                    encoding = encoding,
                ),
            )
        }.fold(
            onSuccess = { device ->
                logger?.info(
                    "RealTime device '${device.name}' (${device.id}) created by ${target.actorName}: " +
                        "${device.worldName} ${device.resolution.configValue} ${device.encoding.configValue} " +
                        "${device.outputs.size} outputs " +
                        "starting at ${device.outputs.first().x},${device.outputs.first().y},${device.outputs.first().z}",
                )
                target.sendMessage(
                    state.messages.render(
                        "command.device.create.success",
                        mapOf(
                            "name" to device.name,
                            "id" to device.id.toString(),
                            "encoding" to device.encoding.configValue,
                            "resolution" to device.resolution.label,
                            "world" to device.worldName,
                            "count" to device.outputs.size.toString(),
                        ),
                    ),
                )
            },
            onFailure = { exception -> sendEditFailure(target, state, exception) },
        )
    }

    private fun handleDeviceSet(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        // device set <uuid> <bit-index> <x> <y> <z>
        if (args.size != 5) return sendUsage(target, state)
        val id = parseDeviceId(target, state, args[0]) ?: return
        val bitIndex = args[1].toIntOrNull() ?: return sendUsage(target, state)
        val x = args[2].toIntOrNull() ?: return sendUsage(target, state)
        val y = args[3].toIntOrNull() ?: return sendUsage(target, state)
        val z = args[4].toIntOrNull() ?: return sendUsage(target, state)
        if (!acquireEditSlot(target, state)) return

        runCatching { administration.setOutput(id, bitIndex, x, y, z) }.fold(
            onSuccess = { device ->
                logger?.info(
                    "RealTime device '${device.name}' (${device.id}) output $bitIndex moved to " +
                        "$x,$y,$z by ${target.actorName}",
                )
                target.sendMessage(
                    state.messages.render(
                        "command.device.set.success",
                        mapOf("bit" to bitIndex.toString(), "x" to x.toString(), "y" to y.toString(), "z" to z.toString()),
                    ),
                )
            },
            onFailure = { exception -> sendEditFailure(target, state, exception) },
        )
    }

    private fun acquireEditSlot(
        target: CommandTarget,
        state: RuntimeState,
    ): Boolean {
        val actor = target.actorId ?: return true
        val cooldown = state.redstoneConfiguration.limits.editCooldownSeconds
        if (cooldown <= 0) return true
        val now = nowMillis()
        val previous = lastEditAt[actor]
        if (previous != null) {
            val remaining = cooldown * 1000L - (now - previous)
            if (remaining > 0) {
                target.sendMessage(
                    state.messages.render(
                        "command.device.edit.cooldown",
                        mapOf("seconds" to ((remaining + 999) / 1000).toString()),
                    ),
                )
                return false
            }
        }
        lastEditAt[actor] = now
        return true
    }

    private fun sendEditFailure(
        target: CommandTarget,
        state: RuntimeState,
        exception: Throwable,
    ) {
        val message =
            when (exception) {
                is DeviceLimitReachedException ->
                    state.messages.render(
                        "command.device.create.limit",
                        mapOf("limit" to exception.limit.toString()),
                    )
                is DeviceAreaNotAllowedException ->
                    state.messages.render(
                        "command.device.create.area",
                        mapOf(
                            "world" to exception.worldName,
                            "x" to exception.x.toString(),
                            "z" to exception.z.toString(),
                        ),
                    )
                is DeviceOutputConflictException ->
                    state.messages.render(
                        "command.device.create.conflict",
                        mapOf(
                            "x" to exception.x.toString(),
                            "y" to exception.y.toString(),
                            "z" to exception.z.toString(),
                        ),
                    )
                is DeviceOutputIndexException ->
                    state.messages.render(
                        "command.device.set.index",
                        mapOf("bit" to exception.bitIndex.toString(), "max" to exception.maxIndex.toString()),
                    )
                is DeviceNotFoundException -> state.messages.render("command.device.not-found", mapOf("id" to exception.message.orEmpty()))
                else ->
                    state.messages.render(
                        "command.device.error",
                        mapOf("reason" to (exception.message ?: "unknown error")),
                    )
            }
        target.sendMessage(message)
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
                "command.device.info.resolution",
                mapOf("resolution" to device.resolution.label),
            ),
        )
        target.sendMessage(
            state.messages.render(
                "command.device.info.encoding",
                mapOf("encoding" to device.encoding.configValue),
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
        private const val MAX_STRIDE = 16
        private val CREATE_TAIL = listOf("bcd", "analog")

        const val COMMAND_PERMISSION = "realtime.command"
        const val VIEW_PERMISSION = "realtime.device.view"
        const val TOGGLE_PERMISSION = "realtime.device.toggle"
        const val EDIT_PERMISSION = "realtime.device.edit"
        const val RETIRE_PERMISSION = "realtime.device.retire"
        const val ADMIN_PERMISSION = "realtime.admin"
    }
}
