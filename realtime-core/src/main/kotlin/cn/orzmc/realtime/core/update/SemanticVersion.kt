package cn.orzmc.realtime.core.update

/**
 * A plugin version as published by Hangar, Modrinth and GitHub Releases.
 *
 * Tags are written by humans, so parsing tolerates an optional `v` prefix and one
 * to three numeric parts (`0.7`, `0.7.0`, `v1.2.3`). Pre-release (`-SNAPSHOT`,
 * `-rc.1`) and build (`+12`) suffixes follow the semantic versioning rules, and
 * build metadata never participates in comparisons.
 */
data class SemanticVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String>,
    private val build: String?,
    private val source: String,
) : Comparable<SemanticVersion> {
    /** A pre-release sorts before the matching release (`0.8.0-SNAPSHOT < 0.8.0`). */
    val isPreRelease: Boolean
        get() = preRelease.isNotEmpty()

    /**
     * Development or CI build, as opposed to a published release. Only these are
     * skipped by `skip-dev-builds`, because a development build always looks older
     * than the release it will become.
     */
    val isSnapshot: Boolean
        get() = preRelease.any { it.equals(SNAPSHOT, ignoreCase = true) } || source.contains(SNAPSHOT, ignoreCase = true)

    /** Canonical text, used in messages and in the update folder file name. */
    fun normalized(): String {
        val core = "$major.$minor.$patch"
        val pre = if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")
        val suffix = if (build == null) "" else "+$build"
        return core + pre + suffix
    }

    override fun compareTo(other: SemanticVersion): Int {
        val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (core != 0) return core
        // A version without a pre-release outranks any pre-release of the same core.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val comparison = compareIdentifier(preRelease[index], other.preRelease[index])
            if (comparison != 0) return comparison
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = normalized()

    private fun compareIdentifier(
        left: String,
        right: String,
    ): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            // Numeric identifiers always sort below alphanumeric ones.
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    companion object {
        private const val SNAPSHOT = "SNAPSHOT"
        private val IDENTIFIER = Regex("[0-9A-Za-z-]+")

        /** Parses [text], returning null for anything that is not a usable version. */
        fun parseOrNull(text: String?): SemanticVersion? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val withoutPrefix = if (trimmed.first() == 'v' || trimmed.first() == 'V') trimmed.substring(1) else trimmed

            var remainder = withoutPrefix
            var build: String? = null
            val buildIndex = remainder.indexOf('+')
            if (buildIndex >= 0) {
                build = remainder.substring(buildIndex + 1)
                if (build.isEmpty() || !IDENTIFIER.matches(build)) return null
                remainder = remainder.substring(0, buildIndex)
            }

            var preRelease = emptyList<String>()
            val preIndex = remainder.indexOf('-')
            if (preIndex >= 0) {
                val pre = remainder.substring(preIndex + 1)
                if (pre.isEmpty()) return null
                preRelease = pre.split('.')
                if (preRelease.any { it.isEmpty() || !IDENTIFIER.matches(it) }) return null
                remainder = remainder.substring(0, preIndex)
            }

            val numbers = remainder.split('.')
            if (numbers.size !in 1..3) return null
            val parsed =
                numbers.map { part ->
                    if (part.isEmpty() || !part.all(Char::isDigit)) return null
                    part.toIntOrNull() ?: return null
                }
            return SemanticVersion(
                major = parsed[0],
                minor = parsed.getOrElse(1) { 0 },
                patch = parsed.getOrElse(2) { 0 },
                preRelease = preRelease,
                build = build,
                source = trimmed,
            )
        }

        /** Like [parseOrNull] but throws, for code paths that own a known-good version. */
        fun parse(text: String): SemanticVersion = parseOrNull(text) ?: throw IllegalArgumentException("Not a semantic version: '$text'")
    }
}
