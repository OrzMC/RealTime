package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.UpdateChannel
import java.net.URI
import java.time.Duration

/** What the plugin does when a newer version exists. */
enum class UpdateMode(
    val configValue: String,
) {
    /** Report the new version and wait for `/rtime update download`. */
    NOTIFY("notify"),

    /** Download it into the server's update folder as soon as it is found. */
    DOWNLOAD("download"),
    ;

    companion object {
        fun fromConfig(value: String?): UpdateMode? =
            value?.trim()?.lowercase()?.let { candidate -> entries.firstOrNull { it.configValue == candidate } }
    }
}

/** Which platform the updater asks first. `auto` walks all configured sources in order. */
enum class UpdateSourcePreference(
    val configValue: String,
) {
    AUTO("auto"),
    HANGAR("hangar"),
    GITHUB("github"),
    MODRINTH("modrinth"),
    ;

    companion object {
        fun fromConfig(value: String?): UpdateSourcePreference? =
            value?.trim()?.lowercase()?.let { candidate -> entries.firstOrNull { it.configValue == candidate } }
    }
}

/**
 * `modules/updates.yml`.
 *
 * The defaults are the conservative ones: check the release channel every six
 * hours, tell the operator, and let them decide when to restart. Nothing here can
 * restart the server or replace a running jar.
 */
data class UpdateConfiguration(
    val enabled: Boolean = true,
    val mode: UpdateMode = UpdateMode.NOTIFY,
    val channel: UpdateChannel = UpdateChannel.RELEASE,
    val checkInterval: Duration = Duration.ofHours(DEFAULT_INTERVAL_HOURS),
    val source: UpdateSourcePreference = UpdateSourcePreference.AUTO,
    val notifyConsole: Boolean = true,
    val notifyPlayers: Boolean = true,
    val skipDevBuilds: Boolean = true,
    val maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    val allowInsecureHttp: Boolean = false,
    val apiBaseOverride: String? = null,
    val allowedHosts: List<String> = DEFAULT_ALLOWED_HOSTS,
    /** Hosts that may serve an update; anything else is refused, including redirects. */
    val allowedHostSet: Set<String> = allowedHosts.map { it.lowercase() }.toSet(),
) {
    init {
        require(checkInterval >= MIN_INTERVAL) { "check interval must be at least $MIN_INTERVAL" }
        require(maxDownloadBytes in MIN_DOWNLOAD_BYTES..MAX_DOWNLOAD_BYTES) {
            "max download size must be between $MIN_DOWNLOAD_BYTES and $MAX_DOWNLOAD_BYTES bytes"
        }
        require(allowedHosts.isNotEmpty()) { "at least one allowed host is required" }
        apiBaseOverride?.let {
            val uri = runCatching { URI(it) }.getOrNull()
            require(uri != null && uri.scheme != null && uri.host != null) { "api base override must be an absolute URI" }
        }
    }

    /** Development builds are only checked when the operator asks for it. */
    val checksDevelopmentBuilds: Boolean
        get() = enabled && !skipDevBuilds

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6L
        const val MIN_INTERVAL_HOURS = 1L
        const val MAX_INTERVAL_HOURS = 168L
        const val DEFAULT_MAX_DOWNLOAD_MB = 16L
        const val MAX_DOWNLOAD_MB = 128L

        val MIN_INTERVAL: Duration = Duration.ofHours(MIN_INTERVAL_HOURS)
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = DEFAULT_MAX_DOWNLOAD_MB * 1024L * 1024L
        const val MIN_DOWNLOAD_BYTES: Long = 64L * 1024L
        const val MAX_DOWNLOAD_BYTES: Long = MAX_DOWNLOAD_MB * 1024L * 1024L

        /**
         * Every host the updater may talk to: the three project APIs and the CDNs
         * they redirect to. Redirects are re-validated against this list.
         */
        val DEFAULT_ALLOWED_HOSTS: List<String> =
            listOf(
                "hangar.papermc.io",
                "hangarcdn.papermc.io",
                "api.github.com",
                "github.com",
                "objects.githubusercontent.com",
                "api.modrinth.com",
                "cdn.modrinth.com",
            )
    }
}
