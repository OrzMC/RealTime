package cn.orzmc.realtime.paper.command

interface CommandTarget {
    fun hasPermission(permission: String): Boolean

    fun sendMessage(message: String)
}
