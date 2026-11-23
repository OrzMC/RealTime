package cn.orzmc.realtime.paper.message

import org.bukkit.ChatColor

class MessageCatalog(
    private val messages: Map<String, String>,
) {
    fun render(
        key: String,
        placeholders: Map<String, String> = emptyMap(),
    ): String {
        val template = messages[key] ?: key
        val values = mapOf("prefix" to messages["prefix"].orEmpty()) + placeholders
        val rendered =
            values.entries.fold(template) { current, (name, value) ->
                current.replace("{$name}", value)
            }
        return ChatColor.translateAlternateColorCodes('&', rendered)
    }
}
