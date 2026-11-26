package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.DeviceDemo
import cn.orzmc.realtime.core.device.DeviceLayout
import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.OutputEncoding
import cn.orzmc.realtime.core.device.OutputResolution
import cn.orzmc.realtime.core.device.RedstoneDevice
import cn.orzmc.realtime.paper.device.DeviceAdministration
import cn.orzmc.realtime.paper.device.DeviceAreaNotAllowedException
import cn.orzmc.realtime.paper.device.DeviceCleanupAdministration
import cn.orzmc.realtime.paper.device.DeviceCleanupJobHasPendingOutputsException
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
        // Flat verbs are the documented surface; `device <subcommand>` stays as a compatibility alias.
        val flatArgs = args.toList()
        when (action) {
            "now" -> if (args.isEmpty() || args.size == 1) showNow(target, state) else sendUsage(target, state)
            "info" ->
                when (args.size) {
                    1 -> {
                        showInfo(target, state)
                        if (target.hasPermission(VIEW_PERMISSION)) {
                            deviceAdministration?.let { showDeviceList(target, state, it.list()) }
                        }
                    }
                    2 -> handleDevice(target, listOf("info", args[1]), state)
                    else -> sendUsage(target, state)
                }
            "list" -> handleDevice(target, listOf("list"), state)
            "status" -> handleDevice(target, listOf("status"), state)
            "create", "demo", "place", "set", "remove", "cleanup" -> handleDevice(target, flatArgs, state)
            "on", "off" -> handleDevice(target, listOf(if (action == "on") "enable" else "disable") + args.drop(1), state)
            "reload" -> if (args.size == 1) reloadConfiguration(target, state) else sendUsage(target, state)
            "help" -> if (args.size <= 2) handleHelp(target, args.drop(1), state) else sendUsage(target, state)
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

        // Flat verbs behave exactly like their `device ...` counterpart; translate and reuse that logic.
        val flat = args[0].lowercase(Locale.ROOT)
        val translated =
            when (flat) {
                "create", "demo", "place", "set", "remove", "cleanup", "list", "status" -> listOf("device", flat) + args.drop(1)
                "on" -> listOf("device", "enable") + args.drop(1)
                "off" -> listOf("device", "disable") + args.drop(1)
                "info" -> if (args.size >= 2) listOf("device", "info") + args.drop(1) else null
                else -> null
            }
        if (translated != null) return complete(target, translated.toTypedArray())

        val state = currentState()
        val administration = deviceAdministration
        val canRead = target.hasPermission(VIEW_PERMISSION)
        val canEdit = target.hasPermission(EDIT_PERMISSION)
        val canToggle = target.hasPermission(TOGGLE_PERMISSION)
        val canRetire = target.hasPermission(RETIRE_PERMISSION) && deviceRetirement != null

        fun List<String>.startingWith(raw: String): List<String> = filter { it.startsWith(raw.lowercase(Locale.ROOT), ignoreCase = true) }

        if (args.size == 1) {
            val candidates =
                buildList {
                    if (target.hasPermission(COMMAND_PERMISSION)) {
                        if (state.configuration.generalEnabled && state.configuration.commandEnabled) add("now")
                        add("info")
                        add("help")
                        if (administration != null) {
                            add("create")
                            add("place")
                            add("on")
                            add("off")
                            add("remove")
                            add("cleanup")
                            add("status")
                        }
                    }
                    if (target.hasPermission(ADMIN_PERMISSION)) add("reload")
                }
            return candidates.startingWith(args[0])
        }

        if (!args[0].equals("device", ignoreCase = true) || administration == null) return emptyList()

        if (args.size == 2) {
            val candidates =
                buildList {
                    if (canRead) {
                        add("list")
                        add("info")
                        add("status")
                        if (cleanupAdministration != null) add("cleanup")
                    }
                    if (canEdit) {
                        add("create")
                        add("demo")
                        add("place")
                        add("set")
                    }
                    if (canToggle) {
                        add("enable")
                        add("disable")
                    }
                    if (canRetire) add("remove")
                }
            return candidates.startingWith(args[1])
        }

        val subcommand = args[1].lowercase(Locale.ROOT)
        val devices = administration.list()

        fun deviceCandidates(enabledOnly: Boolean): List<String> {
            val pool = devices.filter { !enabledOnly || !it.enabled }
            return pool.map { it.name } + pool.map { it.id.toString() }
        }

        when (subcommand) {
            "cleanup" -> {
                if (args.size == 3 && cleanupAdministration != null) {
                    val candidates =
                        buildList {
                            if (canRead) add("list")
                            if (target.hasPermission(RETIRE_PERMISSION)) {
                                add("retry")
                                add("dismiss")
                            }
                        }
                    return candidates.startingWith(args[2])
                }
                if (args.size == 4 &&
                    (args[2].equals("retry", true) || args[2].equals("dismiss", true)) &&
                    target.hasPermission(RETIRE_PERMISSION)
                ) {
                    return cleanupAdministration
                        ?.list()
                        .orEmpty()
                        .filter { job -> job.outputs.any { it.status == DeviceCleanupStatus.NEEDS_ATTENTION } }
                        .map { it.id.toString() }
                        .startingWith(args[3])
                }
                return emptyList()
            }

            "create", "demo" -> {
                // create: /rtime device create <name> <...>  → the encoding/resolution sits in args[3]
                // demo:   /rtime device demo <...>          → in args[2]
                val offset = if (subcommand == "demo") 2 else 4
                if (args.size < offset) return emptyList()
                if (args.size == offset) {
                    // Either the encoding (player-relative form) or the resolution (explicit form).
                    return (OutputEncoding.entries.map { it.configValue } + OutputResolution.entries.map { it.configValue })
                        .startingWith(args.last())
                }
                val encodingFirst = OutputEncoding.fromConfig(args[offset - 1]) != null
                if (encodingFirst) {
                    if (args.size == offset + 1) {
                        return (OutputResolution.entries.map { it.configValue } + CREATE_MODIFIERS)
                            .startingWith(args.last())
                    }
                    return if (args.size == offset + 2) CREATE_MODIFIERS.startingWith(args.last()) else emptyList()
                }
                // Explicit form: <world> <x> <y> <z> <axis> [stride] [encoding] [nodisplay]
                return when (args.size) {
                    offset + 4 -> LayoutAxis.entries.map { it.configValue }.startingWith(args.last())
                    offset + 5, offset + 6 ->
                        (OutputEncoding.entries.map { it.configValue } + listOf("nodisplay")).startingWith(args.last())
                    else -> emptyList()
                }
            }

            "place", "set" -> {
                if (args.size == 3) return deviceCandidates(enabledOnly = true).startingWith(args[2])
                if (args.size == 4) {
                    val device = devices.firstOrNull { device -> device.matches(args[2]) } ?: return emptyList()
                    return (0 until device.outputCount).map { it.toString() }.startingWith(args[3])
                }
                return emptyList()
            }

            "info" -> {
                if (args.size == 3 && canRead) return deviceCandidates(enabledOnly = false).startingWith(args[2])
                return emptyList()
            }

            "enable", "disable" -> {
                if (args.size == 3 && canToggle) return deviceCandidates(enabledOnly = false).startingWith(args[2])
                return emptyList()
            }

            "remove" -> {
                if (args.size == 3 && canRetire) return deviceCandidates(enabledOnly = false).startingWith(args[2])
                return emptyList()
            }
        }
        return emptyList()
    }

    private fun RedstoneDevice.matches(raw: String): Boolean =
        id.toString().equals(raw, ignoreCase = true) || name.equals(raw, ignoreCase = true)

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
                val device = resolveDevice(target, state, administration, args[1]) ?: return
                showDeviceInfo(target, state, device)
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
            "demo" -> handleDeviceDemo(target, args.drop(1), state, administration)
            "place", "set" -> handleDevicePlace(target, args.drop(1), state, administration)
            "enable", "disable" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(TOGGLE_PERMISSION)) return sendNoPermission(target, state)
                val device = resolveDevice(target, state, administration, args[1]) ?: return
                val id = device.id
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
                            if (enabled) warnIfRedstoneDisabled(target, state)
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
                                // Demo display blocks belong to the same job, so the queue
                                // summary must count them as well.
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
            "dismiss" -> {
                if (args.size != 2) return sendUsage(target, state)
                if (!target.hasPermission(RETIRE_PERMISSION)) return sendNoPermission(target, state)
                val id = parseCleanupJobId(target, state, args[1]) ?: return
                runCatching { administration.dismiss(id) }
                    .fold(
                        onSuccess = { job ->
                            logger?.info(
                                "RealTime cleanup job ${job.id} of device '${job.deviceName}' dismissed by " +
                                    "${target.actorName}: " +
                                    "${job.outputs.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }} slot(s) and " +
                                    "${job.display.count { it.status == DeviceCleanupStatus.NEEDS_ATTENTION }} display " +
                                    "block(s) left as they are",
                            )
                            target.sendMessage(
                                state.messages.render(
                                    "command.device.cleanup.dismiss.success",
                                    mapOf("id" to job.id.toString(), "name" to job.deviceName),
                                ),
                            )
                        },
                        onFailure = { exception ->
                            val key =
                                when (exception) {
                                    is DeviceCleanupJobNotFoundException -> "command.device.cleanup.not-found"
                                    is DeviceCleanupJobHasPendingOutputsException ->
                                        "command.device.cleanup.dismiss.has-pending"
                                    else -> "command.device.cleanup.error"
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
        val administration =
            deviceAdministration ?: run {
                target.sendMessage(state.messages.render("command.device.unavailable"))
                return
            }
        val id = resolveDevice(target, state, administration, args[0])?.id ?: return

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
        if (snapshot.demoBlocksPlaced > 0 || snapshot.demoBlocksCleared > 0) {
            target.sendMessage(
                state.messages.render(
                    "command.device.status.demo",
                    mapOf(
                        "placed" to snapshot.demoBlocksPlaced.toString(),
                        "cleared" to snapshot.demoBlocksCleared.toString(),
                    ),
                ),
            )
        }
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

    /**
     * `/rtime device create <name> <bcd|analog> [second|minute] [nodisplay|manual]` — player relative
     * `/rtime device create <name> <minute|second> <world> <x> <y> <z> <axis> [stride] [bcd|analog] [nodisplay]` — explicit
     *
     * Both forms build a device the same way: slots laid out from one origin, display blocks placed so the
     * clock is readable immediately, and the device started enabled. `nodisplay` keeps the world untouched
     * apart from the slots, and `manual` (player form only) creates a disabled device with no display so each
     * slot can be positioned with `device place`.
     */
    private fun handleDeviceCreate(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
        nameOverride: String? = null,
        defaultResolution: OutputResolution = OutputResolution.MINUTE,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        if (args.isEmpty()) return sendUsage(target, state)
        // An explicit build always carries <world> <x> <y> <z> <axis>, so it has at least seven tokens.
        // Anything shorter that starts with an encoding is the player-relative form; shorter than that with a
        // resolution is a half-typed explicit command and gets the usage text.
        val explicit = args.size >= 7
        return if (!explicit && OutputEncoding.fromConfig(args[1]) != null) {
            createAtPlacement(target, args, state, administration, nameOverride, defaultResolution)
        } else {
            createAtCoordinates(target, args, state, administration, nameOverride, defaultResolution)
        }
    }

    /** Player-relative form: `create <name> <bcd|analog> [second|minute] [nodisplay|manual]`. */
    private fun createAtPlacement(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
        nameOverride: String?,
        defaultResolution: OutputResolution,
    ) {
        val name = nameOverride ?: args[0]
        val encoding = OutputEncoding.fromConfig(args[1]) ?: return sendUsage(target, state)
        val modifiers = args.drop(2)
        val resolution =
            modifiers.firstNotNullOfOrNull { OutputResolution.fromConfig(it.lowercase(Locale.ROOT)) } ?: defaultResolution
        val unknown =
            modifiers.firstOrNull { modifier ->
                OutputResolution.fromConfig(modifier.lowercase(Locale.ROOT)) == null &&
                    !modifier.equals("nodisplay", true) &&
                    !modifier.equals("manual", true)
            }
        if (unknown != null) {
            target.sendMessage(state.messages.render("command.device.create.invalid-modifier", mapOf("value" to unknown)))
            return
        }
        val noDisplay = modifiers.any { it.equals("nodisplay", true) }
        val manual = modifiers.any { it.equals("manual", true) }
        val placement = target.placement ?: return sendPlacementRequired(target, state)

        val layout = DeviceLayout.default(encoding)
        val outputs =
            runCatching {
                layout.outputs(encoding, resolution, placement.axis, placement.x, placement.y, placement.z)
            }.getOrElse { return sendUsage(target, state) }
        val demoBlocks =
            if (noDisplay || manual) emptyList() else DeviceDemo.display(encoding, placement.axis, outputs)
        if (!acquireEditSlot(target, state)) return

        runCatching {
            administration.create(
                DeviceCreateRequest(
                    name = name,
                    worldName = placement.worldName,
                    resolution = resolution,
                    outputs = outputs,
                    encoding = encoding,
                    demoBlocks = demoBlocks,
                    enabled = !manual,
                ),
            )
        }.fold(
            onSuccess = { device ->
                logger?.info(
                    "RealTime device '${device.name}' (${device.id}) created by ${target.actorName} at " +
                        "${placement.worldName} ${placement.x},${placement.y},${placement.z} " +
                        "(${device.encoding.configValue}, ${device.resolution.label}, ${device.outputs.size} slots, " +
                        "${device.demoBlocks.size} display blocks)",
                )
                target.sendMessage(
                    state.messages.render(
                        "command.device.create.built",
                        mapOf(
                            "name" to device.name,
                            "id" to device.id.toString(),
                            "encoding" to device.encoding.configValue,
                            "resolution" to device.resolution.label,
                            "world" to device.worldName,
                            "count" to device.outputs.size.toString(),
                            "display" to device.demoBlocks.size.toString(),
                            "state" to
                                state.messages.render(
                                    if (device.enabled) "command.device.state.enabled" else "command.device.state.disabled",
                                ),
                        ),
                    ),
                )
                if (manual) {
                    target.sendMessage(
                        state.messages.render(
                            "command.device.create.manual-hint",
                            mapOf("name" to device.name, "max" to (device.outputs.size - 1).toString()),
                        ),
                    )
                }
                warnIfRedstoneDisabled(target, state)
            },
            onFailure = { exception -> sendEditFailure(target, state, exception) },
        )
    }

    /** Explicit form: `create <name> <minute|second> <world> <x> <y> <z> <axis> [stride] [bcd|analog] [nodisplay]`. */
    private fun createAtCoordinates(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
        nameOverride: String?,
        defaultResolution: OutputResolution,
        encodingOverride: OutputEncoding? = null,
    ) {
        // args: <name> <minute|second> <world> <x> <y> <z> <x+|x-|z+|z-> [stride] [bcd|analog] [nodisplay]
        if (args.size !in 7..10) return sendUsage(target, state)

        val name = nameOverride ?: args[0]
        // Resolution and encoding may both lead the coordinates, in any order (`<minute|second>` and
        // `<bcd|analog>` are disjoint token sets), so consume them before the world name.
        var index = 1
        var resolution: OutputResolution? = null
        var encodingLead: OutputEncoding? = null
        while (index < args.size) {
            val token = args[index].lowercase(Locale.ROOT)
            val candidateResolution = OutputResolution.fromConfig(token)
            val candidateEncoding = OutputEncoding.fromConfig(token)
            when {
                candidateResolution != null && resolution == null -> {
                    resolution = candidateResolution
                    index++
                }
                candidateEncoding != null && encodingLead == null -> {
                    encodingLead = candidateEncoding
                    index++
                }
                else -> break
            }
        }
        if (index == 1) {
            // Neither a resolution nor an encoding: say which token was wrong instead of dumping the usage.
            target.sendMessage(
                state.messages.render("command.device.create.invalid-resolution", mapOf("value" to args[1])),
            )
            return
        }
        val worldName = args.getOrNull(index) ?: return sendUsage(target, state)
        val x = args.getOrNull(index + 1)?.toIntOrNull() ?: return sendUsage(target, state)
        val y = args.getOrNull(index + 2)?.toIntOrNull() ?: return sendUsage(target, state)
        val z = args.getOrNull(index + 3)?.toIntOrNull() ?: return sendUsage(target, state)
        val axis = LayoutAxis.fromConfig(args.getOrNull(index + 4) ?: "") ?: return sendUsage(target, state)
        val effectiveResolution = resolution ?: defaultResolution
        val optional = args.drop(index + 5)
        val stride = optional.firstOrNull { it.toIntOrNull() != null }?.toInt()
        val noDisplay = optional.any { it.equals("nodisplay", true) }
        val encodingValue = optional.firstOrNull { it.toIntOrNull() == null && !it.equals("nodisplay", true) }
        val encoding =
            when {
                encodingOverride != null -> encodingOverride
                encodingLead != null -> encodingLead
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
        if (!acquireEditSlot(target, state)) return

        val layout = DeviceLayout.default(encoding)
        val customLayout = if (stride == null) layout else layout.copy(stride = stride)
        val outputs =
            runCatching {
                customLayout.outputs(encoding, effectiveResolution, axis, x, y, z)
            }.getOrElse { return sendUsage(target, state) }
        val demoBlocks = if (noDisplay) emptyList() else DeviceDemo.display(encoding, axis, outputs)

        runCatching {
            administration.create(
                DeviceCreateRequest(
                    name = name,
                    worldName = worldName,
                    resolution = effectiveResolution,
                    outputs = outputs,
                    encoding = encoding,
                    demoBlocks = demoBlocks,
                    enabled = true,
                ),
            )
        }.fold(
            onSuccess = { device ->
                logger?.info(
                    "RealTime device '${device.name}' (${device.id}) created by ${target.actorName}: " +
                        "${device.worldName} ${device.encoding.configValue} ${device.resolution.label} " +
                        "${device.outputs.size} slots + ${device.demoBlocks.size} display blocks " +
                        "starting at ${device.outputs.first().x},${device.outputs.first().y},${device.outputs.first().z}",
                )
                target.sendMessage(
                    state.messages.render(
                        "command.device.create.built",
                        mapOf(
                            "name" to device.name,
                            "id" to device.id.toString(),
                            "encoding" to device.encoding.configValue,
                            "resolution" to device.resolution.label,
                            "world" to device.worldName,
                            "count" to device.outputs.size.toString(),
                            "display" to device.demoBlocks.size.toString(),
                            "state" to
                                state.messages.render(
                                    if (device.enabled) "command.device.state.enabled" else "command.device.state.disabled",
                                ),
                        ),
                    ),
                )
                warnIfRedstoneDisabled(target, state)
            },
            onFailure = { exception -> sendEditFailure(target, state, exception) },
        )
    }

    /**
     * `/rtime device demo <bcd|analog> [second|minute]` or the explicit form with
     * `<world> <x> <y> <z> <axis>`.
     *
     * Demo is the same build as `create`, only named `demo-<encoding>` and defaulting to second resolution so a
     * freshly built clock visibly ticks.
     */
    private fun handleDeviceDemo(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        if (args.isEmpty()) return sendUsage(target, state)
        val encoding =
            OutputEncoding.fromConfig(args[0]) ?: run {
                target.sendMessage(
                    state.messages.render("command.device.create.invalid-encoding", mapOf("value" to args[0])),
                )
                return
            }
        val name = "demo-" + encoding.configValue
        val rest = args.drop(1)
        val playerRelative =
            rest.isEmpty() ||
                OutputResolution.fromConfig(rest[0].lowercase(Locale.ROOT)) != null ||
                rest[0].equals("nodisplay", true)
        if (playerRelative) {
            createAtPlacement(
                target = target,
                args = listOf(name, encoding.configValue) + rest,
                state = state,
                administration = administration,
                nameOverride = name,
                defaultResolution = OutputResolution.SECOND,
            )
        } else {
            // Explicit form: <world> <x> <y> <z> <axis>; demo always uses second resolution.
            createAtCoordinates(
                target = target,
                args = listOf(name, OutputResolution.SECOND.configValue) + rest,
                state = state,
                administration = administration,
                nameOverride = name,
                defaultResolution = OutputResolution.SECOND,
                encodingOverride = encoding,
            )
        }
    }

    /**
     * `/rtime device place <device> <slot> [x y z]`
     *
     * Moves one slot to an exact position: without coordinates it uses the block the player is aiming at (or
     * standing on), which is how a builder handles the rows where another build already occupies the default
     * layout. `device set` remains an alias.
     */
    private fun handleDevicePlace(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
        administration: DeviceAdministration,
    ) {
        if (!target.hasPermission(EDIT_PERMISSION)) return sendNoPermission(target, state)
        if (args.size != 2 && args.size != 5) return sendUsage(target, state)
        val device = resolveDevice(target, state, administration, args[0]) ?: return
        val bitIndex = args[1].toIntOrNull() ?: return sendUsage(target, state)
        val coordinates =
            if (args.size == 5) {
                val x = args[2].toIntOrNull() ?: return sendUsage(target, state)
                val y = args[3].toIntOrNull() ?: return sendUsage(target, state)
                val z = args[4].toIntOrNull() ?: return sendUsage(target, state)
                Triple(x, y, z)
            } else {
                val placement = target.placement ?: return sendPlacementRequired(target, state)
                if (!placement.worldName.equals(device.worldName, ignoreCase = true)) {
                    target.sendMessage(
                        state.messages.render(
                            "command.device.place.world-mismatch",
                            mapOf("world" to device.worldName, "current" to placement.worldName),
                        ),
                    )
                    return
                }
                Triple(placement.x, placement.y, placement.z)
            }
        if (!acquireEditSlot(target, state)) return

        val (x, y, z) = coordinates
        runCatching { administration.setOutput(device.id, bitIndex, x, y, z) }.fold(
            onSuccess = { updated ->
                logger?.info(
                    "RealTime device '${updated.name}' (${updated.id}) slot $bitIndex placed at " +
                        "$x,$y,$z by ${target.actorName}",
                )
                target.sendMessage(
                    state.messages.render(
                        "command.device.set.success",
                        mapOf("bit" to bitIndex.toString(), "x" to x.toString(), "y" to y.toString(), "z" to z.toString()),
                    ),
                )
                warnIfRedstoneDisabled(target, state)
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
                is DeviceMustBeDisabledException ->
                    state.messages.render("command.device.set.must-disable", mapOf("id" to exception.message.orEmpty()))
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

    /**
     * Accepts a device name (case-insensitive) or its UUID.
     *
     * Names are the readable handle in help text and tab completion; ids stay
     * supported because they appear in logs and older documentation.
     */
    private fun resolveDevice(
        target: CommandTarget,
        state: RuntimeState,
        administration: DeviceAdministration,
        raw: String,
    ): RedstoneDevice? {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
        if (id != null) {
            val device = administration.find(id)
            if (device == null) {
                target.sendMessage(state.messages.render("command.device.not-found", mapOf("id" to id.toString())))
            }
            return device
        }
        val matches = administration.list().filter { it.name.equals(raw, ignoreCase = true) }
        return when (matches.size) {
            0 -> {
                target.sendMessage(state.messages.render("command.device.not-found", mapOf("id" to raw)))
                null
            }
            1 -> matches.single()
            else -> {
                target.sendMessage(
                    state.messages.render(
                        "command.device.ambiguous",
                        mapOf("name" to raw, "count" to matches.size.toString()),
                    ),
                )
                null
            }
        }
    }

    private fun sendPlacementRequired(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        target.sendMessage(state.messages.render("command.device.placement-required"))
    }

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

    /**
     * `/rtime help [主题]`
     *
     * The command surface grew (two creation forms, per-slot placement, cleanup), so the help is a compact list
     * plus three topics that spell out the parts people get wrong.
     */
    private fun handleHelp(
        target: CommandTarget,
        args: List<String>,
        state: RuntimeState,
    ) {
        val topic = args.firstOrNull()?.lowercase(Locale.ROOT)
        val lines =
            when (topic) {
                null -> HELP_BASIC
                "all" -> HELP_BASIC + HELP_ADVANCED
                "create", "demo" -> listOf("command.help.header", "command.help.create", "command.help.topic.create")
                "place", "set" -> listOf("command.help.header", "command.help.place", "command.help.topic.place")
                "cleanup" -> listOf("command.help.header", "command.help.cleanup", "command.help.topic.cleanup")
                else -> {
                    sendUsage(target, state)
                    return
                }
            }
        lines.forEach { target.sendMessage(state.messages.render(it)) }
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
        target.sendMessage(
            state.messages.render(
                "command.info.redstone-enabled",
                mapOf("enabled" to state.redstoneConfiguration.enabled.toString()),
            ),
        )
    }

    /**
     * Warns once per command when the redstone module is off.
     *
     * With `modules/redstone.yml` disabled nothing is ever written: outputs stay
     * empty and demo blocks are treated as absent, so `device demo` used to report
     * success while the world stayed untouched. Say so at the moment the device is
     * created instead of leaving the operator to guess.
     */
    private fun warnIfRedstoneDisabled(
        target: CommandTarget,
        state: RuntimeState,
    ) {
        if (state.redstoneConfiguration.enabled) return
        target.sendMessage(state.messages.render("command.device.warn.redstone-disabled"))
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

        /** Modifiers accepted by the player-relative `device create` form. */
        private val CREATE_MODIFIERS = listOf("nodisplay", "manual")

        /** The five commands that cover the everyday use, printed by `/rtime help`. */
        private val HELP_BASIC =
            listOf(
                "command.help.header",
                "command.help.time",
                "command.help.create",
                "command.help.info",
                "command.help.toggle",
                "command.help.remove",
                "command.help.footer",
            )

        /** Everything else, appended by `/rtime help all`. */
        private val HELP_ADVANCED =
            listOf(
                "command.help.advanced.header",
                "command.help.place",
                "command.help.status",
                "command.help.cleanup",
                "command.help.reload",
            )
    }
}
