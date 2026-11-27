package cn.orzmc.realtime.core.update

import java.security.MessageDigest

/** Checksum algorithms the three platforms publish. */
enum class DigestAlgorithm(
    val jdkName: String,
    val hexLength: Int,
) {
    /** Hangar (`fileInfo.sha256Hash`) and GitHub (`assets[].digest`). */
    SHA256("SHA-256", 64),

    /** Modrinth (`files[].hashes.sha512`). */
    SHA512("SHA-512", 128),
}

/**
 * A published checksum, normalized to lowercase hex.
 *
 * The downloader verifies the bytes it wrote against the checksum the platform
 * returned over TLS; a missing or unparsable checksum downgrades the update to
 * "notify only" instead of installing unverified code.
 */
data class Digest private constructor(
    val algorithm: DigestAlgorithm,
    val hex: String,
) {
    fun matches(other: Digest?): Boolean =
        other != null &&
            other.algorithm == algorithm &&
            MessageDigest.isEqual(hex.toByteArray(Charsets.US_ASCII), other.hex.toByteArray(Charsets.US_ASCII))

    override fun toString(): String = "${algorithm.name.lowercase()}:$hex"

    companion object {
        private val HEX = Regex("[0-9a-f]+")

        fun of(
            algorithm: DigestAlgorithm,
            hex: String,
        ): Digest? {
            val normalized = hex.trim().lowercase()
            if (normalized.length != algorithm.hexLength || !HEX.matches(normalized)) return null
            return Digest(algorithm, normalized)
        }

        /**
         * Reads `sha256:<hex>`, `sha512:<hex>` or a bare hex string (Hangar sends
         * the bare form). A bare string is read as [algorithm].
         */
        fun parse(
            text: String?,
            algorithm: DigestAlgorithm = DigestAlgorithm.SHA256,
        ): Digest? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val separator = raw.indexOf(':')
            if (separator <= 0) return of(algorithm, raw)
            val name = raw.substring(0, separator).lowercase()
            val chosen = DigestAlgorithm.entries.firstOrNull { it.name.lowercase() == name } ?: return null
            return of(chosen, raw.substring(separator + 1))
        }

        /** Computes the checksum of [bytes]; used by tests and by the downloader. */
        fun compute(
            algorithm: DigestAlgorithm,
            bytes: ByteArray,
        ): Digest {
            val jdk = MessageDigest.getInstance(algorithm.jdkName)
            return Digest(algorithm, jdk.digest(bytes).toHex())
        }
    }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(digits[value ushr 4]).append(digits[value and 0x0f])
    }
    return out.toString()
}
