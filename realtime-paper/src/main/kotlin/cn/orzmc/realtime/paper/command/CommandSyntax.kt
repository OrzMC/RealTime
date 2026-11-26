package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.LayoutAxis
import cn.orzmc.realtime.core.device.OutputResolution
import java.util.Locale
import java.util.UUID

/**
 * The grammar of `/rtime`, kept free of Bukkit types so it can be exercised directly.
 *
 * Reading is two commands: `/rtime` (time plus every clock) and `/rtime <name>` (one
 * clock). Everything else is a verb. Options are keywords, so their order never
 * matters and a typo can be reported by name instead of falling back to the usage
 * text.
 */
object CommandSyntax {
    /** A clock shows seconds unless `minute` is asked for. */
    val DEFAULT_RESOLUTION: OutputResolution = OutputResolution.SECOND

    const val CREATE = "create"
    const val ON = "on"
    const val OFF = "off"
    const val PLACE = "place"
    const val REMOVE = "remove"
    const val STATUS = "status"
    const val CLEANUP = "cleanup"
    const val HELP = "help"
    const val RELOAD = "reload"

    val VERBS: List<String> = listOf(CREATE, ON, OFF, PLACE, REMOVE, STATUS, CLEANUP, HELP, RELOAD)

    /** A clock may not be named after a verb, or `/rtime <name>` would be ambiguous. */
    val RESERVED_NAMES: Set<String> = VERBS.toSet()

    /** Verbs that existed before 0.6.0, with what replaced them. */
    private val REMOVED_VERBS: Map<String, String> =
        mapOf(
            "info" to "/rtime 或 /rtime <钟名>",
            "list" to "/rtime",
            "device" to "/rtime create、/rtime place 等",
            "demo" to "/rtime create <名字>",
            "set" to "/rtime place",
            "now" to "/rtime",
        )

    sealed interface Invocation {
        data object Overview : Invocation

        data class ShowDevice(
            val name: String,
        ) : Invocation

        data class Create(
            val name: String,
            val spec: CreateSpec,
        ) : Invocation

        data class Toggle(
            val name: String,
            val enabled: Boolean,
        ) : Invocation

        data class Place(
            val name: String,
            val slot: Int?,
            val coordinates: Coordinates?,
        ) : Invocation

        data class Remove(
            val name: String,
        ) : Invocation

        data object Status : Invocation

        data class Cleanup(
            val action: CleanupAction,
        ) : Invocation

        data object Help : Invocation

        data object Reload : Invocation

        /** A verb from an older version; [replacement] says what to type instead. */
        data class Removed(
            val verb: String,
            val replacement: String,
        ) : Invocation

        /** Parse error; [key] is a message key and [placeholders] fill it in. */
        data class Invalid(
            val key: String,
            val placeholders: Map<String, String> = emptyMap(),
        ) : Invocation
    }

    data class Coordinates(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    enum class CreateMode {
        /** Slots and display blocks are built and the device starts enabled. */
        DISPLAY,

        /** Slots are built, display blocks are not. */
        NO_DISPLAY,

        /** Nothing is built: every slot waits for `place`, the device starts disabled. */
        MANUAL,
    }

    data class CreateSpec(
        val resolution: OutputResolution = DEFAULT_RESOLUTION,
        val mode: CreateMode = CreateMode.DISPLAY,
        /** Explicit origin, for the console and for scripting. */
        val at: Coordinates? = null,
        /** Explicit growth direction; defaults to where the player looks. */
        val facing: LayoutAxis? = null,
    )

    sealed interface CleanupAction {
        data object List : CleanupAction

        data class Retry(
            val id: UUID,
        ) : CleanupAction

        data class Dismiss(
            val id: UUID,
        ) : CleanupAction
    }

    fun parse(args: Array<out String>): Invocation {
        if (args.isEmpty()) return Invocation.Overview
        val verb = args[0].lowercase(Locale.ROOT)
        val rest = args.drop(1)
        REMOVED_VERBS[verb]?.let { replacement -> return Invocation.Removed(verb, replacement) }
        return when (verb) {
            CREATE -> parseCreate(rest)
            ON, OFF -> parseToggle(verb == ON, rest)
            PLACE -> parsePlace(rest)
            REMOVE -> parseName(rest) { Invocation.Remove(it) }
            STATUS -> if (rest.isEmpty()) Invocation.Status else usage()
            CLEANUP -> parseCleanup(rest)
            HELP -> if (rest.isEmpty()) Invocation.Help else usage()
            RELOAD -> if (rest.isEmpty()) Invocation.Reload else usage()
            // Anything else is a clock name: `/rtime lobby` reads one clock.
            else -> Invocation.ShowDevice(args[0])
        }
    }

    private fun parseToggle(
        enabled: Boolean,
        rest: List<String>,
    ): Invocation = parseName(rest) { name -> Invocation.Toggle(name, enabled) }

    private fun parseName(
        rest: List<String>,
        build: (String) -> Invocation,
    ): Invocation {
        if (rest.size != 1) return usage()
        return build(rest.single())
    }

    private fun parseCreate(rest: List<String>): Invocation {
        val name = rest.firstOrNull() ?: return usage()
        if (name.lowercase(Locale.ROOT) in RESERVED_NAMES) {
            return Invocation.Invalid("command.create.reserved-name", mapOf("name" to name))
        }
        var resolution: OutputResolution? = null
        var mode = CreateMode.DISPLAY
        var at: Coordinates? = null
        var facing: LayoutAxis? = null

        var index = 1
        while (index < rest.size) {
            val token = rest[index].lowercase(Locale.ROOT)
            when {
                token == OutputResolution.MINUTE.configValue || token == OutputResolution.SECOND.configValue -> {
                    if (resolution != null) {
                        return Invocation.Invalid("command.create.duplicate-option", mapOf("value" to rest[index]))
                    }
                    resolution = OutputResolution.fromConfig(token)
                    index += 1
                }
                token == "manual" -> {
                    mode = CreateMode.MANUAL
                    index += 1
                }
                token == "nodisplay" -> {
                    if (mode != CreateMode.MANUAL) mode = CreateMode.NO_DISPLAY
                    index += 1
                }
                token == "at" -> {
                    if (at != null) {
                        return Invocation.Invalid("command.create.duplicate-option", mapOf("value" to rest[index]))
                    }
                    val parsed =
                        parseCoordinates(rest.drop(index + 1))
                            ?: return Invocation.Invalid(
                                "command.create.missing-coordinates",
                            )
                    at = parsed.first
                    index += 1 + parsed.second
                }
                token == "facing" -> {
                    if (facing != null) {
                        return Invocation.Invalid("command.create.duplicate-option", mapOf("value" to rest[index]))
                    }
                    val value = rest.getOrNull(index + 1) ?: ""
                    facing =
                        LayoutAxis.fromFacing(value)
                            ?: return Invocation.Invalid("command.create.invalid-facing", mapOf("value" to value))
                    index += 2
                }
                else -> return Invocation.Invalid("command.create.unknown-option", mapOf("value" to rest[index]))
            }
        }
        return Invocation.Create(
            name = name,
            spec = CreateSpec(resolution ?: DEFAULT_RESOLUTION, mode, at, facing),
        )
    }

    private fun parsePlace(rest: List<String>): Invocation {
        val name = rest.firstOrNull() ?: return usage()
        val slot =
            rest.getOrNull(1)?.toIntOrNull()
                ?: return Invocation.Invalid("command.place.invalid-slot", mapOf("value" to (rest.getOrNull(1) ?: "")))
        if (slot < 0) return Invocation.Invalid("command.place.invalid-slot", mapOf("value" to slot.toString()))
        val coordinates =
            when (rest.size) {
                2 -> null
                5 -> {
                    val x = rest[2].toIntOrNull()
                    val y = rest[3].toIntOrNull()
                    val z = rest[4].toIntOrNull()
                    if (x == null || y == null || z == null) {
                        return Invocation.Invalid("command.place.invalid-coordinates", mapOf("value" to rest.drop(2).joinToString(" ")))
                    }
                    Coordinates(x, y, z)
                }
                else -> return usage()
            }
        return Invocation.Place(name, slot, coordinates)
    }

    private fun parseCleanup(rest: List<String>): Invocation =
        when (rest.firstOrNull()?.lowercase(Locale.ROOT)) {
            null -> Invocation.Cleanup(CleanupAction.List)
            "retry", "dismiss" -> {
                val id =
                    rest.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: return Invocation.Invalid("command.cleanup.invalid-id", mapOf("id" to (rest.getOrNull(1) ?: "")))
                if (rest.size != 2) return usage()
                Invocation.Cleanup(
                    if (rest[0].equals("retry", true)) CleanupAction.Retry(id) else CleanupAction.Dismiss(id),
                )
            }
            else -> usage()
        }

    /**
     * Reads exactly three integers starting at [tokens] and returns them with the
     * number of tokens consumed, so a bare `at` can be told apart from a bad one.
     */
    private fun parseCoordinates(tokens: List<String>): Pair<Coordinates, Int>? {
        val x = tokens.getOrNull(0)?.toIntOrNull() ?: return null
        val y = tokens.getOrNull(1)?.toIntOrNull() ?: return null
        val z = tokens.getOrNull(2)?.toIntOrNull() ?: return null
        return Coordinates(x, y, z) to 3
    }

    private fun usage(): Invocation = Invocation.Invalid("command.usage")
}
