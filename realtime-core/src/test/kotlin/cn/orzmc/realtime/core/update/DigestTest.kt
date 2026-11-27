package cn.orzmc.realtime.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigestTest {
    @Test
    fun `computes the published checksums`() {
        // Well-known vectors, so a broken digest can never look like a bad download.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Digest.compute(DigestAlgorithm.SHA256, ByteArray(0)).hex,
        )
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Digest.compute(DigestAlgorithm.SHA512, ByteArray(0)).hex,
        )
    }

    @Test
    fun `parses hangar bare hex, github prefixed digests and modrinth sha512`() {
        val sha256 = Digest.compute(DigestAlgorithm.SHA256, "realtime".toByteArray())
        assertEquals(sha256, Digest.parse(sha256.hex))
        assertEquals(sha256, Digest.parse("sha256:${sha256.hex}"))
        assertEquals(sha256, Digest.parse("SHA256:${sha256.hex.uppercase()}"))

        val sha512 = Digest.compute(DigestAlgorithm.SHA512, "realtime".toByteArray())
        assertEquals(sha512, Digest.parse("sha512:${sha512.hex}"))
        // A bare sha512 hex is not a sha256 and must not be accepted as one.
        assertNull(Digest.parse(sha512.hex))
    }

    @Test
    fun `rejects malformed checksums instead of trusting them`() {
        assertNull(Digest.parse(null))
        assertNull(Digest.parse(""))
        assertNull(Digest.parse("sha256:"))
        assertNull(Digest.parse("sha256:xyz"))
        assertNull(Digest.parse("md5:d41d8cd98f00b204e9800998ecf8427e"))
        assertNull(Digest.parse("abc123"))
        assertNull(Digest.of(DigestAlgorithm.SHA256, Digest.compute(DigestAlgorithm.SHA512, ByteArray(0)).hex))
    }

    @Test
    fun `compares only equal algorithms`() {
        val bytes = "payload".toByteArray()
        val sha256 = Digest.compute(DigestAlgorithm.SHA256, bytes)
        val other = Digest.compute(DigestAlgorithm.SHA256, "payload!".toByteArray())

        assertTrue(sha256.matches(Digest.compute(DigestAlgorithm.SHA256, bytes)))
        assertFalse(sha256.matches(other))
        assertFalse(sha256.matches(null))
        assertFalse(sha256.matches(Digest.compute(DigestAlgorithm.SHA512, bytes)))
        assertEquals("sha256:${sha256.hex}", sha256.toString())
    }
}
