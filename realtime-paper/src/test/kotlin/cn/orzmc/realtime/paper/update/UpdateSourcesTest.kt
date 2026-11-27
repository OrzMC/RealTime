package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.DigestAlgorithm
import cn.orzmc.realtime.core.update.UpdateSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateSourcesTest {
    @Test
    fun `hangar picks the paper download with its checksum`() {
        val body =
            """
            {
              "result": [
                {
                  "name": "0.7.0",
                  "channel": { "name": "Release" },
                  "createdAt": "2026-11-26T22:45:58.656168Z",
                  "downloads": {
                    "PAPER": {
                      "fileInfo": {
                        "name": "RealTime-0.7.0.jar",
                        "sizeBytes": 2183111,
                        "sha256Hash": "${"d4f3dae1".repeat(8)}"
                      },
                      "externalUrl": null,
                      "downloadUrl": "https://hangarcdn.papermc.io/plugins/OrzMC/RealTime/versions/0.7.0/PAPER/RealTime-0.7.0.jar"
                    }
                  }
                },
                {
                  "name": "0.8.0-SNAPSHOT",
                  "channel": { "name": "Snapshot" },
                  "downloads": {
                    "PAPER": {
                      "fileInfo": { "sizeBytes": 12, "sha256Hash": "not-a-checksum" },
                      "downloadUrl": "https://hangarcdn.papermc.io/plugins/OrzMC/RealTime/versions/0.8.0-SNAPSHOT/PAPER/RealTime.jar"
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        val manifests = HangarUpdateSource.parse(body)

        assertEquals(2, manifests.size)
        val release = manifests.first()
        assertEquals("0.7.0", release.version.normalized())
        assertEquals(UpdateSource.HANGAR, release.source)
        assertEquals(2183111L, release.sizeBytes)
        assertTrue(release.downloadUrl.startsWith("https://hangarcdn.papermc.io/"))
        assertEquals(Digest.parse("d4f3dae1".repeat(8)), release.digest)
        // An unparsable checksum does not make the version disappear, it makes it non-installable.
        assertNull(manifests[1].digest)
        assertEquals("0.8.0-SNAPSHOT", manifests[1].version.normalized())
    }

    @Test
    fun `hangar skips entries without a paper download`() {
        val body = """{"result":[{"name":"0.7.0","downloads":{"VELOCITY":{"downloadUrl":"https://x/y.jar"}}},{"name":"oops"}]}"""

        assertTrue(HangarUpdateSource.parse(body).isEmpty())
    }

    @Test
    fun `github reads the release array and ignores the sha256 sidecar`() {
        val sha = "a".repeat(64)
        val body =
            """
            [
              {
                "tag_name": "v0.7.0",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/OrzMC/RealTime/releases/tag/v0.7.0",
                "assets": [
                  {
                    "name": "RealTime-0.7.0.jar",
                    "size": 2183111,
                    "digest": "sha256:$sha",
                    "browser_download_url": "https://github.com/OrzMC/RealTime/releases/download/v0.7.0/RealTime-0.7.0.jar"
                  },
                  {
                    "name": "RealTime-0.7.0.jar.sha256",
                    "size": 111,
                    "digest": "sha256:${"b".repeat(64)}",
                    "browser_download_url": "https://github.com/OrzMC/RealTime/releases/download/v0.7.0/RealTime-0.7.0.jar.sha256"
                  }
                ]
              },
              { "tag_name": "v0.9.0", "draft": true, "assets": [] }
            ]
            """.trimIndent()

        val manifests = GitHubUpdateSource.parse(body)

        assertEquals(1, manifests.size)
        assertEquals("0.7.0", manifests.single().version.normalized())
        assertEquals(Digest.parse("sha256:$sha"), manifests.single().digest)
        assertEquals(2183111L, manifests.single().sizeBytes)
        assertEquals(UpdateSource.GITHUB, manifests.single().source)
    }

    @Test
    fun `github also accepts the single release object`() {
        val body =
            """
            {"tag_name":"v0.7.0","draft":false,"assets":[
              {"name":"RealTime-0.7.0.jar","size":1,"digest":"sha256:${"c".repeat(64)}",
               "browser_download_url":"https://github.com/OrzMC/RealTime/releases/download/v0.7.0/RealTime-0.7.0.jar"}]}
            """.trimIndent()

        assertEquals(
            "0.7.0",
            GitHubUpdateSource
                .parse(body)
                .single()
                .version
                .normalized(),
        )
    }

    @Test
    fun `modrinth reads sha512 from the primary file`() {
        val sha512 = Digest.compute(DigestAlgorithm.SHA512, "payload".toByteArray()).hex
        val body =
            """
            [
              {
                "version_number": "0.7.0",
                "version_type": "release",
                "game_versions": ["26.3"],
                "loaders": ["paper", "folia"],
                "files": [
                  {
                    "primary": false,
                    "url": "https://cdn.modrinth.com/data/x/secondary.jar",
                    "size": 5,
                    "hashes": { "sha512": "${"0".repeat(128)}" }
                  },
                  {
                    "primary": true,
                    "url": "https://cdn.modrinth.com/data/x/RealTime-0.7.0.jar",
                    "size": 2183111,
                    "hashes": { "sha512": "$sha512" }
                  }
                ]
              }
            ]
            """.trimIndent()

        val manifest = ModrinthUpdateSource.parse(body).single()

        assertEquals("0.7.0", manifest.version.normalized())
        assertEquals(UpdateSource.MODRINTH, manifest.source)
        assertEquals(sha512, manifest.digest?.hex)
        assertTrue(manifest.downloadUrl.endsWith("RealTime-0.7.0.jar"))
    }

    @Test
    fun `fetches the hangar payload over http with the documented path`() {
        val server = TestHttpServer()
        try {
            server.json(
                "/api/v1/projects/RealTime/versions",
                """{"result":[{"name":"9.9.9","downloads":{"PAPER":{"fileInfo":{"sizeBytes":10,"sha256Hash":"${"a".repeat(
                    64,
                )}"},"downloadUrl":"https://hangarcdn.papermc.io/x.jar"}}}]}""",
            )
            val source =
                HangarUpdateSource(
                    http = UpdateHttp(setOf("127.0.0.1"), allowInsecureHttp = true, userAgent = "RealTime/test"),
                    apiBase = server.baseUrl,
                )

            val manifests = source.fetch()

            assertEquals(1, manifests.size)
            assertEquals("9.9.9", manifests.single().version.normalized())
            assertTrue(server.requests.single().contains("/api/v1/projects/RealTime/versions?limit=20"), server.requests.toString())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a malformed payload yields no candidates instead of throwing`() {
        assertEquals(emptyList<Any>(), HangarUpdateSource.parse("{}"))
        assertEquals(emptyList<Any>(), GitHubUpdateSource.parse("""{"no":"assets"}"""))
        assertEquals(emptyList<Any>(), ModrinthUpdateSource.parse("""{"not":"an array"}"""))
    }
}
