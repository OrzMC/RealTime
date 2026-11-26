package cn.orzmc.realtime.core.update

/** Where an update was discovered. */
enum class UpdateSource(
    val configValue: String,
) {
    /** Hangar, the primary distribution and the only source with a full checksum list. */
    HANGAR("hangar"),

    /** GitHub Releases, used as a fallback (works even while a platform review is pending). */
    GITHUB("github"),

    /** Modrinth, optional: a project under review hides its versions from anonymous reads. */
    MODRINTH("modrinth"),
    ;

    companion object {
        fun fromConfig(value: String?): UpdateSource? =
            value?.trim()?.lowercase()?.let { candidate -> entries.firstOrNull { it.configValue == candidate } }
    }
}

/**
 * One downloadable version as published by a platform.
 *
 * [digest] is nullable because a platform response can be malformed; the updater
 * then reports the version but refuses to download it.
 */
data class UpdateManifest(
    val version: SemanticVersion,
    val downloadUrl: String,
    val digest: Digest?,
    val sizeBytes: Long?,
    val source: UpdateSource,
    val pageUrl: String? = null,
) {
    init {
        require(downloadUrl.isNotBlank()) { "downloadUrl must not be blank" }
        require(sizeBytes == null || sizeBytes > 0) { "sizeBytes must be positive when present" }
    }
}

/** Which published versions the updater is allowed to offer. */
enum class UpdateChannel(
    val configValue: String,
) {
    /** Published releases only. */
    RELEASE("release"),

    /** Releases plus pre-releases (`-SNAPSHOT`, `-rc.1`, …). */
    ANY("any"),
    ;

    fun accepts(version: SemanticVersion): Boolean =
        when (this) {
            RELEASE -> !version.isPreRelease
            ANY -> true
        }

    companion object {
        fun fromConfig(value: String?): UpdateChannel? =
            value?.trim()?.lowercase()?.let { candidate -> entries.firstOrNull { it.configValue == candidate } }
    }
}

/** Outcome of comparing the running version with what the platforms publish. */
sealed interface UpdateDecision {
    /** Nothing newer was found. */
    data class UpToDate(
        val current: SemanticVersion,
    ) : UpdateDecision

    /** A newer, downloadable version exists. */
    data class Available(
        val manifest: UpdateManifest,
        val current: SemanticVersion,
    ) : UpdateDecision

    /** No usable candidate at all: offline, every source failed, or no publication yet. */
    data object NoCandidate : UpdateDecision
}

/**
 * Chooses the version to offer.
 *
 * Downgrades are never offered and pre-releases are filtered by [UpdateChannel]. A
 * candidate whose download URL is neither HTTPS nor loopback HTTP is ignored here as
 * well, so a compromised API response cannot redirect the installer to a plain-HTTP
 * host; the HTTP layer still applies the host allowlist and the operator's
 * `allow-insecure-http` switch before a socket is opened.
 */
object UpdatePolicy {
    fun decide(
        current: SemanticVersion,
        candidates: List<UpdateManifest>,
        channel: UpdateChannel,
    ): UpdateDecision {
        val usable =
            candidates.filter { candidate ->
                channel.accepts(candidate.version) && isTransportAcceptable(candidate.downloadUrl)
            }
        val best = usable.maxByOrNull { it.version } ?: return UpdateDecision.NoCandidate
        return if (best.version > current) {
            UpdateDecision.Available(best, current)
        } else {
            UpdateDecision.UpToDate(current)
        }
    }

    /**
     * HTTPS anywhere, plain HTTP only for loopback (the CI smoke test serves its
     * fixture there). Everything else — `file:`, another port on a real host, a
     * lookalike such as `http://127.0.0.1.example.com` — is refused.
     */
    private fun isTransportAcceptable(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("https://")) return true
        if (!lower.startsWith("http://")) return false
        return LOOPBACK_HTTP.any { lower.startsWith(it) }
    }

    private val LOOPBACK_HTTP =
        listOf("http://127.0.0.1:", "http://127.0.0.1/", "http://[::1]:", "http://[::1]/", "http://localhost:", "http://localhost/")
}
