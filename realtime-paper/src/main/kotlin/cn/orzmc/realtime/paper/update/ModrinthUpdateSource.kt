package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.DigestAlgorithm
import cn.orzmc.realtime.core.update.Json
import cn.orzmc.realtime.core.update.JsonValue
import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateManifest
import cn.orzmc.realtime.core.update.UpdateSource
import cn.orzmc.realtime.core.update.array
import cn.orzmc.realtime.core.update.asArray
import cn.orzmc.realtime.core.update.asObject
import cn.orzmc.realtime.core.update.boolean
import cn.orzmc.realtime.core.update.long
import cn.orzmc.realtime.core.update.obj
import cn.orzmc.realtime.core.update.string

/**
 * Modrinth: optional third source.
 *
 * Modrinth publishes SHA-512 rather than SHA-256, and a project under review
 * answers 404 to anonymous version reads — which is why it is never the only
 * source. Failures here degrade to "no candidate from this source".
 */
class ModrinthUpdateSource(
    private val http: UpdateHttp,
    private val projectId: String = DEFAULT_PROJECT_ID,
    override val apiBase: String = DEFAULT_API,
) : UpdateManifestSource {
    override val displayName: String = "Modrinth"

    override fun fetch(): List<UpdateManifest> = parse(http.getText(versionsUrl()))

    private fun versionsUrl(): String = joinUrl(apiBase, "v2/project/$projectId/version")

    companion object {
        const val DEFAULT_PROJECT_ID = "pRGtDpaD"
        const val DEFAULT_API = "https://api.modrinth.com"

        fun parse(body: String): List<UpdateManifest> {
            val rows = Json.parse(body).asArray() ?: return emptyList()
            return rows.mapNotNull { row -> parseVersion(row.asObject() ?: return@mapNotNull null) }
        }

        private fun parseVersion(row: JsonValue.Obj): UpdateManifest? {
            val version = SemanticVersion.parseOrNull(row.string("version_number")) ?: return null
            val files = row.array("files") ?: return null
            val file =
                files
                    .mapNotNull { it.asObject() }
                    .firstOrNull { it.boolean("primary") == true }
                    ?: files.mapNotNull { it.asObject() }.firstOrNull()
                    ?: return null
            val url = file.string("url")?.takeIf { it.isNotBlank() } ?: return null
            val digest =
                (file.obj("hashes")?.string("sha512"))
                    ?.let { Digest.of(DigestAlgorithm.SHA512, it) }
            return UpdateManifest(
                version = version,
                downloadUrl = url,
                digest = digest,
                sizeBytes = file.long("size")?.takeIf { it > 0 },
                source = UpdateSource.MODRINTH,
                pageUrl = "https://modrinth.com/plugin/realtimesignal",
            )
        }
    }
}
