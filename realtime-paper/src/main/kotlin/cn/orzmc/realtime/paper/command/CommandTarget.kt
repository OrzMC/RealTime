package cn.orzmc.realtime.paper.command

import java.util.UUID

interface CommandTarget {
    /** Player name or `console`. Used for audit logging. */
    val actorName: String

    /** Player UUID, or null when the command comes from the console. */
    val actorId: UUID?

    fun hasPermission(permission: String): Boolean

    fun sendMessage(message: String)
}
