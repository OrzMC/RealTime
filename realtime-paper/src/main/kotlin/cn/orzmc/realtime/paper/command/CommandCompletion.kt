package cn.orzmc.realtime.paper.command

import cn.orzmc.realtime.core.device.OutputResolution
import java.util.Locale

/** One clock as tab completion needs to see it. */
data class CompletionDevice(
    val name: String,
    val enabled: Boolean,
    val slots: Int,
)

/**
 * Tab completion for `/rtime`, written as a pure function of the arguments, the
 * clocks and the sender's permissions.
 *
 * Completion mirrors the grammar exactly: the first token is a verb or a clock
 * name, `create` completes its option keywords, and every verb that takes a clock
 * completes clock names before anything else.
 */
object CommandCompletion {
    private val FACINGS = listOf("north", "south", "east", "west")

    fun complete(
        args: Array<out String>,
        devices: List<CompletionDevice>,
        cleanupJobIds: List<String>,
        hasPermission: (String) -> Boolean,
    ): List<String> {
        if (args.isEmpty()) return emptyList()
        val raw = args.last()
        val typed = args.dropLast(1)

        fun matching(options: List<String>): List<String> = options.filter { it.startsWith(raw, ignoreCase = true) }

        fun deviceNames(): List<String> = devices.map { it.name }

        if (typed.isEmpty()) {
            val verbs =
                buildList {
                    if (hasPermission(RealTimeCommandHandler.VIEW_PERMISSION)) addAll(deviceNames())
                    if (hasPermission(RealTimeCommandHandler.EDIT_PERMISSION)) {
                        add(CommandSyntax.CREATE)
                        add(CommandSyntax.PLACE)
                    }
                    if (hasPermission(RealTimeCommandHandler.TOGGLE_PERMISSION)) {
                        add(CommandSyntax.ON)
                        add(CommandSyntax.OFF)
                    }
                    if (hasPermission(RealTimeCommandHandler.RETIRE_PERMISSION)) add(CommandSyntax.REMOVE)
                    if (hasPermission(RealTimeCommandHandler.VIEW_PERMISSION)) {
                        add(CommandSyntax.STATUS)
                        add(CommandSyntax.CLEANUP)
                    }
                    if (hasPermission(RealTimeCommandHandler.ADMIN_PERMISSION)) add(CommandSyntax.RELOAD)
                    add(CommandSyntax.HELP)
                }
            return matching(verbs)
        }

        return when (val verb = typed[0].lowercase(Locale.ROOT)) {
            CommandSyntax.CREATE -> createCandidates(typed.drop(1), raw) { matching(it) }
            CommandSyntax.ON, CommandSyntax.OFF, CommandSyntax.REMOVE ->
                if (typed.size == 1) matching(deviceNames()) else emptyList()
            CommandSyntax.PLACE -> {
                if (typed.size == 1) return matching(deviceNames())
                val device = devices.firstOrNull { it.name.equals(typed[1], ignoreCase = true) } ?: return emptyList()
                if (typed.size == 2) matching((0 until device.slots).map { it.toString() }) else emptyList()
            }
            CommandSyntax.CLEANUP -> {
                if (typed.size == 1) return matching(listOf("retry", "dismiss"))
                if (typed.size == 2 && !typed[1].equals("retry", true) && !typed[1].equals("dismiss", true)) {
                    return emptyList()
                }
                matching(cleanupJobIds)
            }
            else -> emptyList()
        }
    }

    /**
     * `create` takes the clock name first, so only its options are completed.
     * `at` wants three integers (nothing to suggest) and `facing` wants a compass
     * keyword.
     */
    private fun createCandidates(
        rest: List<String>,
        raw: String,
        matching: (List<String>) -> List<String>,
    ): List<String> {
        if (rest.isEmpty()) return emptyList()
        val last = rest.last().lowercase(Locale.ROOT)
        val previous = rest.getOrNull(rest.size - 2)?.lowercase(Locale.ROOT)
        return when {
            last == "facing" || previous == "facing" -> matching(FACINGS)
            last == "at" || isCoordinatePosition(rest) -> emptyList()
            else ->
                matching(
                    OutputResolution.entries.map { it.configValue } +
                        listOf("manual", "nodisplay", "at", "facing"),
                )
        }
    }

    /** True while the three numbers after `at` are being typed. */
    private fun isCoordinatePosition(rest: List<String>): Boolean {
        val atIndex = rest.indexOfLast { it.equals("at", ignoreCase = true) }
        if (atIndex < 0) return false
        return rest.size - atIndex - 1 <= 3
    }
}
