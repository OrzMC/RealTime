package cn.orzmc.realtime.paper.placeholder

import cn.orzmc.realtime.core.device.BcdHhmmEncoder
import cn.orzmc.realtime.paper.command.RuntimeState
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class RealTimePlaceholderExpansion(
    private val pluginVersion: String,
    private val stateProvider: () -> RuntimeState,
) : PlaceholderExpansion() {
    override fun getAuthor(): String = "OrzMC"

    override fun getIdentifier(): String = IDENTIFIER

    override fun getVersion(): String = pluginVersion

    override fun persist(): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    override fun onRequest(
        player: OfflinePlayer?,
        params: String,
    ): String? {
        val state = stateProvider()
        val snapshot = state.timeService.snapshot()
        val dateTime = snapshot.zonedDateTime
        val localTime = snapshot.localTime
        val bcd = BcdHhmmEncoder.encode(localTime)

        return when (params.lowercase(Locale.ROOT)) {
            "time", "datetime" -> state.timeService.format(snapshot, state.configuration.commandFormat)
            "date" -> format(snapshot, "uuuu-MM-dd", state.timeService.locale)
            "time_24h" -> format(snapshot, "HH:mm:ss", state.timeService.locale)
            "time_12h" -> format(snapshot, "hh:mm:ss a", state.timeService.locale)
            "hour" -> format(snapshot, "HH", state.timeService.locale)
            "minute" -> format(snapshot, "mm", state.timeService.locale)
            "second" -> format(snapshot, "ss", state.timeService.locale)
            "day" -> format(snapshot, "dd", state.timeService.locale)
            "month" -> format(snapshot, "MM", state.timeService.locale)
            "year" -> format(snapshot, "uuuu", state.timeService.locale)
            "weekday" -> dateTime.dayOfWeek.getDisplayName(TextStyle.FULL, state.timeService.locale)
            "weekday_short" -> dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, state.timeService.locale)
            "timezone" -> snapshot.zoneId.id
            "offset" -> snapshot.offset.id
            "iso" -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime)
            "timestamp" -> snapshot.epochSecond.toString()
            "timestamp_millis" -> snapshot.instant.toEpochMilli().toString()
            "bcd" -> bcd.digits.joinToString("")
            "bcd_bits" -> bcd.bits.joinToString("") { if (it) "1" else "0" }
            "redstone_enabled" -> state.redstoneConfiguration.enabled.toString()
            "device_count" -> state.devices.count { it.enabled }.toString()
            else -> null
        }
    }

    private fun format(
        snapshot: cn.orzmc.realtime.core.time.RealTimeSnapshot,
        pattern: String,
        locale: Locale,
    ): String = DateTimeFormatter.ofPattern(pattern, locale).format(snapshot.zonedDateTime)

    private companion object {
        const val IDENTIFIER = "realtime"
    }
}
