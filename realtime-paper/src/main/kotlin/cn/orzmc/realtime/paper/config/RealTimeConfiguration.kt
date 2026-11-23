package cn.orzmc.realtime.paper.config

import java.time.ZoneId
import java.util.Locale

data class RealTimeConfiguration(
    val generalEnabled: Boolean,
    val zoneId: ZoneId,
    val locale: Locale,
    val debugLogging: Boolean,
    val commandEnabled: Boolean,
    val commandFormat: String,
    val messages: Map<String, String>,
)
