package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.DeviceDemo
import cn.orzmc.realtime.core.device.DeviceLayout
import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.command.CommandSyntax.CleanupAction
import cn.orzmc.realtime.paper.command.CommandSyntax.Coordinates
import cn.orzmc.realtime.paper.command.CommandSyntax.CreateMode
import cn.orzmc.realtime.paper.command.CommandSyntax.Invocation
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceAreaNotAllowedException
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupJobHasPendingOutputsException
import cn.orzmc.realtime.paper.device.DeviceCleanupJobNotFoundException
import cn.orzmc.realtime.paper.device.DeviceCleanupStatus
import cn.orzmc.realtime.paper.device.DeviceCreateRequest
import cn.orzmc.realtime.paper.device.DeviceDiagnostics
import cn.orzmc.realtime.paper.device.DeviceIncompleteException
import cn.orzmc.realtime.paper.device.DeviceLimitReachedException
import cn.orzmc.realtime.paper.device.DeviceMustBeDisabledException
import cn.orzmc.realtime.paper.device.DeviceNameTakenException
import cn.orzmc.realtime.paper.device.DeviceNotFoundException
import cn.orzmc.realtime.paper.device.DeviceOutputConflictException
import cn.orzmc.realtime.paper.device.DeviceOutputIndexException
import cn.orzmc.realtime.paper.device.DevicePlacement
import cn.orzmc.realtime.paper.device.DeviceRetirementAdministration
import java.util.UUID
import java.util.logging.Logger

/**
 * Executes `/rtime`.
 *
 * The grammar lives in [CommandSyntax]; this class only checks permissions, calls
 * the device services and renders messages, so every command reads as
 * "permission, service call, message".
 */
class RealTimeCommandHandler(
    private val pluginVersion: String,
    private val currentState: () -> RuntimeState,
    private val reload: () -> Result<RuntimeState>,
    private val deviceAdministration: DeviceAdministration? = null,
    private val deviceDiagnostics: DeviceDiagnostics? = null,
    private val devicePlacement: DevicePlacement? = null,
    private val cleanupAdministration: DeviceCleanupAdministration? = null,
    private val deviceRetirement: DeviceRetirementAdministration? = null,
    private val skippedLegacyDevices: () -> List<String> = { emptyList() },
    private val logger: Logger? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastEditAt = mutableMapOf<UUID, Long>()

    fun execute(
        target: CommandTarget,
        args: Array<out String>,
    ): Boolean {
        val state = currentState()
        when (val invocation = CommandSyntax.parse(args)) {
            Invocation.Overview -> showOverview(target, state)
            is Invocation.ShowDevice -> showDevice(target, state, invocation.name)
            is Invocation.Create -> createDevice(target, state, invocation)
            is Invocation.Toggle -> toggleDevice(target, state, invocation)
            is Invocation.Place -> placeSlot(target, state, invocation)
            is Invocation.Remove -> removeDevice(target, state, invocation)
            Invocation.Status -> showStatus(target, state)
            is Invocation.Cleanup -> showCleanup(target, state, invocation.action)
            Invocation.Help -> showHelp(target, state)
            Invocation.Reload -> reloadConfiguration(target, state)
            is Invocation.Removed ->
                target.sendMessage(
                    state.messages.render(
                        "command.removed",
                        mapOf("verb" to invocation.verb, "replacement" to invocation.replacement),
                    ),
                )
            is Invocation.Invalid -> target.sendMessage(state.messages.render(invocation.key, invocation.placeholders))
        }
        return true
    }

    fun complete(
        target: CommandTarget,
        args: Array<out String>,
    ): List<String> {
        if (args.isEmpty()) return emptyList()
        val devices =
            deviceAdministration
                ?.list()
                .orEmpty()
                .map { CompletionDevice(it.name, it.enabled, it.outputCount) }
        val jobIds = cleanupAdministration?.list().orEmpty().map { it.id.toString() }
        return CommandCompletion.complete(args, devices, jobIds) { permission -> target.hasPermission(permission) }
    }

    private fun showOverview(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (!target.hasPermission(COMMAND_PERMISSION)) return sendNoPermission(target, state)
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
        if (!target.hasPermission(VIEW_PERMISSION)) return
        val administration = deviceAdministration ?: return
        val devices = administration.list()
        if (devices.isEmpty()) {
            target.sendMessage(state.messages.render("command.overview.empty"))
        } else {
            target.sendMessage(
                state.messages.render("command.overview.header", mapOf("count" to devices.size.toString())),
            )
            devices.forEach { device ->
                val unplaced = device.unplacedOutputs.size
                val extra =
                    if (unplaced > 0) {
                        state.messages.render("command.overview.unplaced", mapOf("count" to unplaced.toString()))
                    } else {
                        ""
                    }
                target.sendMessage(
                    state.messages.render(
                        "command.overview.item",
                        mapOf(
                            "state" to deviceStateLabel(state, device.enabled),
                            "name" to device.name,
                            "resolution" to device.resolution.label,
                            "count" to device.outputCount.toString(),
                            "extra" to extra,
                        ),
                    ),
                )
            }
        }
        val pending = state.cleanupJobs.size
        if (pending > 0) {
            target.sendMessage(
                state.messages.render("command.overview.cleanup-hint", mapOf("count" to pending.toString())),
            )
        }
    }

    private fun showDevice(
        target: CommandTarget,
        state: RuntimeState,
        name: String,
    ) {
        if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
        val administration = deviceAdministration ?: return sendUnavailable(target, state)
        val device = resolve(target, state, administration, name) ?: return
        target.sendMessage(state.messages.render("command.device.header", mapOf("name" to device.name)))
        target.sendMessage(
            state.messages.render("command.device.state-line", mapOf("state" to deviceStateLabel(state, device.enabled))),
        )
        target.sendMessage(state.messages.render("command.device.world", mapOf("world" to device.worldName)))
        target.sendMessage(
            state.messages.render(
                "command.device.resolution",
                mapOf("resolution" to device.resolution.label, "count" to device.outputCount.toString()),
            ),
        )
        target.sendMessage(
            state.messages.render("command.device.outputs", mapOf("count" to device.outputCount.toString())),
        )
        device.outputs.forEach { output ->
            val placeholders =
                mapOf(
                    "bit" to output.bitIndex.toString(),
                    "x" to output.x.toString(),
                    "y" to output.y.toString(),
                    "z" to output.z.toString(),
                    "name" to device.name,
                )
            val key = if (output.unplaced) "command.device.output-unplaced" else "command.device.output"
            target.sendMessage(state.messages.render(key, placeholders))
        }
        target.sendMessage(state.messages.render("command.device.hint", mapOf("name" to device.name)))
    }

    private fun createDevice(
        target: CommandTarget,
        state: RuntimeState,
        invocation: Invocation.Create,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        val administration = deviceAdministration ?: return sendUnavailable(target, state)
        val spec = invocation.spec
        // A clock is built in the world the builder stands in, so `create` is a
        // player command; `at` only replaces the origin, not the world.
        val placement = target.placement
        if (placement == null) {
            target.sendMessage(state.messages.render("command.create.placement-required"))
            return
        }
        val origin: Coordinates = spec.at ?: Coordinates(placement.x, placement.y, placement.z)
        val worldName = placement.worldName
        val axis = spec.facing ?: placement?.axis ?: LayoutAxis.X_PLUS
        val outputs =
            runCatching { DeviceLayout.DEFAULT.outputs(spec.resolution, axis, origin.x, origin.y, origin.z) }
                .getOrElse {
                    target.sendMessage(state.messages.render("command.usage"))
                    return
                }
        val demoBlocks =
            if (spec.mode == CreateMode.DISPLAY) DeviceDemo.display(axis, outputs) else emptyList()
        val effectiveOutputs = if (spec.mode == CreateMode.MANUAL) outputs.map { it.copy(unplaced = true) } else outputs
        if (!acquireEditSlot(target, state)) return

        runCatching {
            administration.create(
                DeviceCreateRequest(
                    name = invocation.name,
                    worldName = worldName,
                    resolution = spec.resolution,
                    outputs = effectiveOutputs,
                    demoBlocks = demoBlocks,
                    enabled = spec.mode != CreateMode.MANUAL,
                ),
            )
        }.fold(
            onSuccess = { device ->
                logger?.info(
                    "RealTime clock '${device.name}' (${device.id}) created by ${target.actorName}: " +
                        "${device.worldName} ${device.resolution.label} ${device.outputCount} slots + " +
                        "${device.demoBlocks.size} display blocks, enabled=${device.enabled}",
                )
                target.sendMessage(
                    state.messages.render(
                        "command.create.built",
                        mapOf(
                            "name" to device.name,
                            "resolution" to device.resolution.label,
                            "count" to device.outputCount.toString(),
                            "display" to device.demoBlocks.size.toString(),
                            "state" to deviceStateLabel(state, device.enabled),
                        ),
                    ),
                )
                if (spec.mode == CreateMode.MANUAL) {
                    target.sendMessage(
                        state.messages.render(
                            "command.create.manual-hint",
                            mapOf("name" to device.name, "max" to (device.outputCount - 1).toString()),
                        ),
                    )
                }
                warnIfRedstoneDisabled(target, state)
            },
            onFailure = { exception -> sendEditFailure(target, state, exception) },
        )
    }

    private fun toggleDevice(
        target: CommandTarget,
        state: RuntimeState,
        invocation: Invocation.Toggle,
    ) {
        if (!target.hasPermission(TOGGLE_PERMISSION)) return sendNoPermission(target, state)
        val administration = deviceAdministration ?: return sendUnavailable(target, state)
        val device = resolve(target, state, administration, invocation.name) ?: return
        runCatching { administration.setEnabled(device.id, invocation.enabled) }.fold(
            onSuccess = { updated ->
                target.sendMessage(
                    state.messages.render(
                        if (invocation.enabled) "command.toggle.enabled" else "command.toggle.disabled",
                        mapOf("name" to updated.name),
                    ),
                )
                if (invocation.enabled) warnIfRedstoneDisabled(target, state)
            },
            onFailure = { exception ->
                if (exception is DeviceIncompleteException) {
                    target.sendMessage(
                        state.messages.render("command.toggle.incomplete", mapOf("count" to exception.unplaced.toString())),
                    )
                } else {
                    sendFailure(target, state, exception)
                }
            },
        )
    }

    private fun placeSlot(
        target: CommandTarget,
        state: RuntimeState,
        invocation: Invocation.Place,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        val administration = deviceAdministration ?: return sendUnavailable(target, state)
        val device = resolve(target, state, administration, invocation.name) ?: return
        val coordinates =
            invocation.coordinates
                ?: run {
                    val placement = target.placement
                    if (placement == null) {
                        target.sendMessage(state.messages.render("command.create.placement-required"))
                        return
                    }
                    if (!placement.worldName.equals(device.worldName, ignoreCase = true)) {
                        target.sendMessage(
                            state.messages.render(
                                "command.place.world-mismatch",
                                mapOf("world" to device.worldName, "current" to placement.worldName),
                            ),
                        )
                        return
                    }
                    Coordinates(placement.x, placement.y, placement.z)
                }
        val slot = invocation.slot ?: return
        val previous = device.outputs.firstOrNull { it.bitIndex == slot }
        if (!acquireEditSlot(target, state)) return

        runCatching { administration.setOutput(device.id, slot, coordinates.x, coordinates.y, coordinates.z) }.fold(
            onSuccess = { updated ->
                val placed = updated.outputs.first { it.bitIndex == slot }
                logger?.info(
                    "RealTime clock '${updated.name}' (${updated.id}) slot $slot placed at " +
                        "${coordinates.x},${coordinates.y},${coordinates.z} by ${target.actorName}",
                )
                // Show the lectern right away: seeing it (and which way it looks) is
                // the point of placing a slot by hand.
                devicePlacement?.placeSlotNow(
                    deviceId = updated.id,
                    slot = placed,
                    previous = previous,
                    facing = target.placement?.lecternFacing,
                )
                target.sendMessage(
                    state.messages.render(
                        "command.place.success",
                        mapOf(
                            "bit" to slot.toString(),
                            "x" to coordinates.x.toString(),
                            "y" to coordinates.y.toString(),
                            "z" to coordinates.z.toString(),
                        ),
                    ),
                )
                warnIfRedstoneDisabled(target, state)
            },
            onFailure = { exception -> sendEditFailure(target, state, exception) },
        )
    }

    private fun removeDevice(
        target: CommandTarget,
        state: RuntimeState,
        invocation: Invocation.Remove,
    ) {
        if (!target.hasPermission(RETIRE_PERMISSION)) return sendNoPermission(target, state)
        val administration = deviceAdministration ?: return sendUnavailable(target, state)
        val retirement = deviceRetirement ?: return sendUnavailable(target, state)
        val device = resolve(target, state, administration, invocation.name) ?: return
        runCatching { retirement.retire(device.id) }.fold(
            onSuccess = { job ->
                target.sendMessage(
                    state.messages.render(
                        "command.remove.success",
                        mapOf("name" to device.name, "job" to job.id.toString()),
                    ),
                )
            },
            onFailure = { exception ->
                when (exception) {
                    is DeviceMustBeDisabledException ->
                        target.sendMessage(state.messages.render("command.remove.must-disable"))
                    is DeviceNotFoundException ->
                        target.sendMessage(
                            state.messages.render("command.remove.not-found", mapOf("name" to device.name)),
                        )
                    else -> sendFailure(target, state, exception)
                }
            },
        )
    }

    private fun showStatus(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
        target.sendMessage(state.messages.render("command.info.header"))
        target.sendMessage(state.messages.render("command.info.version", mapOf("version" to pluginVersion)))
        target.sendMessage(
            state.messages.render("command.info.timezone", mapOf("timezone" to state.configuration.zoneId.id)),
        )
        target.sendMessage(
            state.messages.render("command.info.locale", mapOf("locale" to state.configuration.locale.toLanguageTag())),
        )
        target.sendMessage(
            state.messages.render(
                "command.info.redstone-enabled",
                mapOf("enabled" to state.redstoneConfiguration.enabled.toString()),
            ),
        )
        target.sendMessage(state.messages.render("command.status.header"))
        val diagnostics = deviceDiagnostics
        if (diagnostics == null) {
            target.sendMessage(state.messages.render("command.unavailable"))
        } else {
            val snapshot = diagnostics.snapshot()
            target.sendMessage(
                state.messages.render(
                    "command.status.devices",
                    mapOf(
                        "configured" to snapshot.configuredDevices.toString(),
                        "enabled" to snapshot.enabledDevices.toString(),
                        "outputs" to snapshot.configuredOutputs.toString(),
                    ),
                ),
            )
            target.sendMessage(
                state.messages.render(
                    "command.status.checks",
                    mapOf(
                        "checks" to snapshot.outputChecks.toString(),
                        "unloaded" to snapshot.unloadedChunkSkips.toString(),
                        "stale" to snapshot.staleOutputUpdates.toString(),
                    ),
                ),
            )
            target.sendMessage(
                state.messages.render(
                    "command.status.outcomes",
                    mapOf(
                        "changed" to snapshot.powerChanges.toString(),
                        "unchanged" to snapshot.unchangedOutputs.toString(),
                        "invalid" to snapshot.invalidOutputBlocks.toString(),
                        "failures" to snapshot.writeFailures.toString(),
                    ),
                ),
            )
            if (snapshot.demoBlocksPlaced > 0 || snapshot.demoBlocksCleared > 0) {
                target.sendMessage(
                    state.messages.render(
                        "command.status.display",
                        mapOf(
                            "placed" to snapshot.demoBlocksPlaced.toString(),
                            "cleared" to snapshot.demoBlocksCleared.toString(),
                        ),
                    ),
                )
            }
            target.sendMessage(
                state.messages.render(
                    "command.status.analog",
                    mapOf(
                        "lecterns" to snapshot.lecternPlacements.toString(),
                        "books" to snapshot.bookWrites.toString(),
                    ),
                ),
            )
        }
        val legacy = skippedLegacyDevices()
        if (legacy.isNotEmpty()) {
            target.sendMessage(
                state.messages.render(
                    "command.status.ignored",
                    mapOf("count" to legacy.size.toString(), "names" to legacy.joinToString("; ")),
                ),
            )
        }
    }

    private fun showCleanup(
        target: CommandTarget,
        state: RuntimeState,
        action: CleanupAction,
    ) {
        val administration = cleanupAdministration ?: return sendUnavailable(target, state)
        when (action) {
            CleanupAction.List -> {
                if (!target.hasPermission(VIEW_PERMISSION)) return sendNoPermission(target, state)
                val jobs = administration.list()
                if (jobs.isEmpty()) {
                    target.sendMessage(state.messages.render("command.cleanup.empty"))
                    return
                }
                target.sendMessage(
                    state.messages.render("command.cleanup.header", mapOf("count" to jobs.size.toString())),
                )
                jobs.forEach { job ->
                    target.sendMessage(
                        state.messages.render(
                            "command.cleanup.item",
                            mapOf(
                                "name" to job.deviceName,
                                "id" to job.id.toString(),
                                "world" to job.worldName,
                                "pending" to
                                    (
                                        job.outputs.count { it.status == DeviceCleanupStatus.PENDING } +
                                            job.display.count { it.status == DeviceCleanupStatus.PENDING }
                                    ).toString(),
                                "attention" to
                                    (
                                        job.outputs.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION } +
                                            job.display.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }
                                    ).toString(),
                            ),
                        ),
                    )
                }
            }
            is CleanupAction.Retry -> {
                if (!target.hasPermission(RETIRE_PERMISSION)) return sendNoPermission(target, state)
                runCatching { administration.retry(action.id) }.fold(
                    onSuccess = { job ->
                        target.sendMessage(
                            state.messages.render("command.cleanup.retry-success", mapOf("id" to job.id.toString())),
                        )
                    },
                    onFailure = { exception -> sendCleanupFailure(target, state, action.id, exception) },
                )
            }
            is CleanupAction.Dismiss -> {
                if (!target.hasPermission(RETIRE_PERMISSION)) return sendNoPermission(target, state)
                runCatching { administration.dismiss(action.id) }.fold(
                    onSuccess = { job ->
                        logger?.info(
                            "RealTime cleanup job ${job.id} of clock '${job.deviceName}' dismissed by " +
                                "${target.actorName}: " +
                                "${job.outputs.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }} slot(s) and " +
                                "${job.display.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }} display " +
                                "block(s) left as they are",
                        )
                        target.sendMessage(
                            state.messages.render(
                                "command.cleanup.dismiss-success",
                                mapOf("id" to job.id.toString(), "name" to job.deviceName),
                            ),
                        )
                    },
                    onFailure = { exception ->
                        if (exception is DeviceCleanupJobHasPendingOutputsException) {
                            target.sendMessage(
                                state.messages.render(
                                    "command.cleanup.dismiss-has-pending",
                                    mapOf("id" to action.id.toString()),
                                ),
                            )
                        } else {
                            sendCleanupFailure(target, state, action.id, exception)
                        }
                    },
                )
            }
        }
    }

    private fun sendCleanupFailure(
        target: CommandTarget,
        state: RuntimeState,
        id: UUID,
        exception: Throwable,
    ) {
        val key =
            if (exception is DeviceCleanupJobNotFoundException) {
                "command.cleanup.not-found"
            } else {
                "command.cleanup.error"
            }
        target.sendMessage(
            state.messages.render(
                key,
                mapOf("id" to id.toString(), "reason" to (exception.message ?: "unknown error")),
            ),
        )
    }

    private fun showHelp(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        HELP_KEYS.forEach { key -> target.sendMessage(state.messages.render(key)) }
    }

    private fun reloadConfiguration(
        target: CommandTarget,
        previousState: RuntimeState,
    ) {
        if (!target.hasPermission(ADMIN_PERMISSION)) return sendNoPermission(target, previousState)
        reload().fold(
            onSuccess = { newState -> target.sendMessage(newState.messages.render("command.reload.success")) },
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

    /** Resolves a clock by name; names are unique, so there is no ambiguity left. */
    private fun resolve(
        target: CommandTarget,
        state: RuntimeState,
        administration: DeviceAdministration,
        name: String,
    ): RedstoneDevice? {
        val matches = administration.list().filter { it.name.equals(name, ignoreCase = true) }
        if (matches.size == 1) return matches.single()
        val key = if (matches.isEmpty()) "command.not-found" else "command.ambiguous"
        target.sendMessage(
            state.messages.render(key, mapOf("name" to name, "count" to matches.size.toString())),
        )
        return null
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
                        "command.retry",
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
                is DeviceNameTakenException ->
                    state.messages.render("command.create.duplicate-name", mapOf("name" to exception.name))
                is DeviceLimitReachedException ->
                    state.messages.render("command.create.limit", mapOf("limit" to exception.limit.toString()))
                is DeviceAreaNotAllowedException ->
                    state.messages.render(
                        "command.create.area",
                        mapOf(
                            "world" to exception.worldName,
                            "x" to exception.x.toString(),
                            "z" to exception.z.toString(),
                        ),
                    )
                is DeviceOutputConflictException ->
                    state.messages.render(
                        "command.create.conflict",
                        mapOf(
                            "x" to exception.x.toString(),
                            "y" to exception.y.toString(),
                            "z" to exception.z.toString(),
                        ),
                    )
                is DeviceOutputIndexException ->
                    state.messages.render(
                        "command.place.index",
                        mapOf("bit" to exception.bitIndex.toString(), "max" to exception.maxIndex.toString()),
                    )
                is DeviceMustBeDisabledException ->
                    state.messages.render("command.place.must-disable")
                is DeviceNotFoundException ->
                    state.messages.render("command.not-found", mapOf("name" to exception.message.orEmpty()))
                else ->
                    state.messages.render(
                        "command.error",
                        mapOf("reason" to (exception.message ?: "unknown error")),
                    )
            }
        target.sendMessage(message)
    }

    private fun sendFailure(
        target: CommandTarget,
        state: RuntimeState,
        exception: Throwable,
    ) {
        if (exception is DeviceNotFoundException) {
            target.sendMessage(state.messages.render("command.not-found", mapOf("name" to exception.message.orEmpty())))
            return
        }
        target.sendMessage(
            state.messages.render("command.error", mapOf("reason" to (exception.message ?: "unknown error"))),
        )
    }

    private fun deviceStateLabel(
        state: RuntimeState,
        enabled: Boolean,
    ): String = state.messages.render(if (enabled) "command.device.state-enabled" else "command.device.state-disabled")

    /**
     * Warns when the redstone module is off: the command succeeds, but nothing is
     * ever written, which otherwise looks like a broken plugin.
     */
    private fun warnIfRedstoneDisabled(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (state.redstoneConfiguration.enabled) return
        target.sendMessage(state.messages.render("command.warn.redstone-disabled"))
    }

    private fun sendNoPermission(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        target.sendMessage(state.messages.render("command.no-permission"))
    }

    private fun sendUnavailable(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        target.sendMessage(state.messages.render("command.unavailable"))
    }

    companion object {
        const val COMMAND_PERMISSION = "realtime.command"
        const val VIEW_PERMISSION = "realtime.device.view"
        const val TOGGLE_PERMISSION = "realtime.device.toggle"
        const val EDIT_PERMISSION = "realtime.device.edit"
        const val RETIRE_PERMISSION = "realtime.device.retire"
        const val ADMIN_PERMISSION = "realtime.admin"

        private val HELP_KEYS =
            listOf(
                "command.help.header",
                "command.help.time",
                "command.help.device",
                "command.help.create",
                "command.help.toggle",
                "command.help.place",
                "command.help.remove",
                "command.help.status",
                "command.help.cleanup",
                "command.help.reload",
                "command.help.footer",
            )
    }
}
