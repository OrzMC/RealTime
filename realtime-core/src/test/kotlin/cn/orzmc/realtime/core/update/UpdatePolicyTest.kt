package cn.orzmc.realtime.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdatePolicyTest {
    @Test
    fun `offers the newest usable candidate`() {
        val decision =
            UpdatePolicy.decide(
                current = v("0.7.0"),
                candidates = candidates("0.7.0", "0.8.0", "0.7.1"),
                channel = UpdateChannel.RELEASE,
            )

        val available = decision as UpdateDecision.Available
        assertEquals(v("0.8.0"), available.manifest.version)
        assertEquals(v("0.7.0"), available.current)
    }

    @Test
    fun `never offers the running version or a downgrade`() {
        assertEquals(
            UpdateDecision.UpToDate(v("0.8.0")),
            UpdatePolicy.decide(v("0.8.0"), candidates("0.8.0", "0.7.0"), UpdateChannel.RELEASE),
        )
    }

    @Test
    fun `includes prereleases only on the any channel`() {
        val prerelease = candidates("0.9.0-SNAPSHOT")

        // Nothing on the release channel is usable, so there is nothing to offer.
        assertEquals(UpdateDecision.NoCandidate, UpdatePolicy.decide(v("0.8.0"), prerelease, UpdateChannel.RELEASE))
        val available = UpdatePolicy.decide(v("0.8.0"), prerelease, UpdateChannel.ANY) as UpdateDecision.Available
        assertEquals(v("0.9.0-SNAPSHOT"), available.manifest.version)
    }

    @Test
    fun `ignores candidates that are not downloadable over tls`() {
        val insecure =
            listOf(
                manifest("0.9.0", "http://hangar.papermc.io/RealTime-0.9.0.jar"),
                manifest("0.9.5", "file:///tmp/RealTime-0.9.5.jar"),
                // A lookalike hostname must not pass as loopback.
                manifest("0.9.6", "http://127.0.0.1.example.com/RealTime-0.9.6.jar"),
            )

        assertEquals(UpdateDecision.NoCandidate, UpdatePolicy.decide(v("0.8.0"), insecure, UpdateChannel.ANY))
    }

    @Test
    fun `accepts loopback http so the offline smoke test can serve a fixture`() {
        val loopback = listOf(manifest("0.9.0", "http://127.0.0.1:18080/RealTime-0.9.0.jar"))

        val available = UpdatePolicy.decide(v("0.8.0"), loopback, UpdateChannel.ANY) as UpdateDecision.Available

        assertEquals(v("0.9.0"), available.manifest.version)
    }

    @Test
    fun `reports no candidate when every source is empty`() {
        assertEquals(UpdateDecision.NoCandidate, UpdatePolicy.decide(v("0.8.0"), emptyList(), UpdateChannel.RELEASE))
    }

    @Test
    fun `a development build is offered the release it will become`() {
        // 0.8.0-SNAPSHOT is older than 0.8.0, so a CI build sees the real 0.8.0 as an update.
        val available =
            UpdatePolicy.decide(v("0.8.0-SNAPSHOT"), candidates("0.8.0"), UpdateChannel.RELEASE) as UpdateDecision.Available

        assertEquals(v("0.8.0"), available.manifest.version)
    }

    private fun candidates(vararg versions: String): List<UpdateManifest> = versions.map { manifest(it) }

    private fun manifest(
        version: String,
        url: String = "https://hangarcdn.papermc.io/plugins/OrzMC/RealTime/versions/$version/PAPER/RealTime-$version.jar",
    ) = UpdateManifest(
        version = v(version),
        downloadUrl = url,
        digest = Digest.of(DigestAlgorithm.SHA256, "a".repeat(64)),
        sizeBytes = 2183111,
        source = UpdateSource.HANGAR,
    )

    private fun v(text: String): SemanticVersion = SemanticVersion.parse(text)
}
