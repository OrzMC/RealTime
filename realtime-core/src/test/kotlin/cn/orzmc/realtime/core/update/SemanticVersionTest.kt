package cn.orzmc.realtime.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticVersionTest {
    @Test
    fun `compares numeric parts, including multiple digit segments`() {
        assertTrue(v("0.9.0") < v("0.10.0"))
        assertTrue(v("0.7.1") > v("0.7.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertEquals(0, v("0.7.0").compareTo(v("0.7.0")))
    }

    @Test
    fun `accepts the tag shapes the platforms actually publish`() {
        assertEquals("0.7.0", v("0.7.0").normalized())
        assertEquals("0.7.0", v("v0.7.0").normalized())
        assertEquals("0.7.0", v(" 0.7 ").normalized())
        assertEquals("0.7.0", v("0.7").normalized())
        assertEquals("1.0.0", v("1").normalized())
        assertEquals("0.8.0-SNAPSHOT", v("0.8.0-SNAPSHOT").normalized())
        assertEquals("0.8.0-rc.1", v("v0.8.0-rc.1").normalized())
        assertEquals("0.8.0+12", v("0.8.0+12").normalized())
    }

    @Test
    fun `rejects text that is not a version`() {
        assertNull(SemanticVersion.parseOrNull(null))
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("latest"))
        assertNull(SemanticVersion.parseOrNull("0.7.0.1"))
        assertNull(SemanticVersion.parseOrNull("0..1"))
        assertNull(SemanticVersion.parseOrNull("0.7.0-"))
        assertNull(SemanticVersion.parseOrNull("0.7.0+bad build"))
        assertNull(SemanticVersion.parseOrNull("0.7.0-SNAPSHOT extra"))
        // A 64-bit segment does not fit an Int and must not silently wrap.
        assertNull(SemanticVersion.parseOrNull("0.7.99999999999999999999"))
    }

    @Test
    fun `orders pre-releases below the release and by identifier`() {
        assertTrue(v("0.8.0-SNAPSHOT") < v("0.8.0"))
        assertTrue(v("0.8.0-alpha") < v("0.8.0-beta"))
        assertTrue(v("0.8.0-beta") < v("0.8.0-rc.1"))
        assertTrue(v("0.8.0-rc.1") < v("0.8.0-rc.2"))
        // Numeric identifiers sort below alphanumeric ones.
        assertTrue(v("0.8.0-1") < v("0.8.0-alpha"))
        assertTrue(v("0.8.0-alpha") < v("0.8.0-alpha.1"))
        // Build metadata never decides the order.
        assertEquals(0, v("0.8.0+1").compareTo(v("0.8.0+2")))
    }

    @Test
    fun `recognizes development builds`() {
        assertTrue(v("0.8.0-SNAPSHOT").isSnapshot)
        assertTrue(v("0.8.0-snapshot").isSnapshot)
        assertTrue(v("0.8.0-rc.1-SNAPSHOT").isSnapshot)
        assertFalse(v("0.8.0").isSnapshot)
        assertFalse(v("0.8.0-rc.1").isSnapshot)
        assertTrue(v("0.8.0-rc.1").isPreRelease)
        assertFalse(v("0.8.0").isPreRelease)
    }

    @Test
    fun `channel filtering keeps releases out of prerelease channels`() {
        assertTrue(UpdateChannel.RELEASE.accepts(v("0.8.0")))
        assertFalse(UpdateChannel.RELEASE.accepts(v("0.8.0-SNAPSHOT")))
        assertTrue(UpdateChannel.ANY.accepts(v("0.8.0-SNAPSHOT")))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.fromConfig("RELEASE"))
        assertNull(UpdateChannel.fromConfig("nightly"))
    }

    private fun v(text: String): SemanticVersion = SemanticVersion.parse(text)
}
