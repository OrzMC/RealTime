package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.DigestAlgorithm
import cn.orzmc.realtime.core.update.Json
import cn.orzmc.realtime.core.update.JsonValue
import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateManifest
import cn.orzmc.realtime.core.update.UpdateSource
import cn.orzmc.realtime.core.update.array
import cn.orzmc.realtime.core.update.asObject
import cn.orzmc.realtime.core.update.long
import cn.orzmc.realtime.core.update.obj
import cn.orzmc.realtime.core.update.string

/**
 * Hangar: the primary source, and the only one that publishes a SHA-256 checksum
 * per file in the same response.
 *
 * Endpoint: `GET {apiBase}/api/v1/projects/{slug}/versions?limit=…`.
 * The PAPER download carries `fileInfo.sha256Hash`, which the installer requires.
 */
class HangarUpdateSource(
    private val http: UpdateHttp,
    private val projectSlug: String = DEFAULT_SLUG,
    override val apiBase: String = DEFAULT_API,
    private val limit: Int = 20,
) : UpdateManifestSource {
    override val displayName: String = "Hangar"

    override fun fetch(): List<UpdateManifest> = parse(http.getText(versionsUrl()))

    private fun versionsUrl(): String = joinUrl(apiBase, "api/v1/projects/$projectSlug/versions?limit=$limit")

    companion object {
        const val DEFAULT_SLUG = "RealTime"
        const val DEFAULT_API = "https://hangar.papermc.io"

        /** Parses a `/versions` response; malformed entries are skipped, not fatal. */
        fun parse(
            body: String,
            source: UpdateSource = UpdateSource.HANGAR,
        ): List<UpdateManifest> {
            val root = Json.parse(body).asObject() ?: return emptyList()
            val rows = root.array("result") ?: return emptyList()
            return rows.mapNotNull { row -> parseVersion(row.asObject() ?: return@mapNotNull null, source) }
        }

        private fun parseVersion(
            row: JsonValue.Obj,
            source: UpdateSource,
        ): UpdateManifest? {
            val version = SemanticVersion.parseOrNull(row.string("name")) ?: return null
            val paper = row.obj("downloads")?.obj("PAPER") ?: return null
            val url = paper.string("downloadUrl")?.takeIf { it.isNotBlank() } ?: return null
            val fileInfo = paper.obj("fileInfo")
            val digest = Digest.parse(fileInfo?.string("sha256Hash"), DigestAlgorithm.SHA256)
            val size = fileInfo?.long("sizeBytes")?.takeIf { it > 0 }
            return UpdateManifest(
                version = version,
                downloadUrl = url,
                digest = digest,
                sizeBytes = size,
                source = source,
                pageUrl = "https://hangar.papermc.io/OrzMC/RealTime",
            )
        }
    }
}
