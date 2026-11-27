package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.Json
import cn.orzmc.realtime.core.update.JsonValue
import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateManifest
import cn.orzmc.realtime.core.update.UpdateSource
import cn.orzmc.realtime.core.update.array
import cn.orzmc.realtime.core.update.asObject
import cn.orzmc.realtime.core.update.boolean
import cn.orzmc.realtime.core.update.long
import cn.orzmc.realtime.core.update.string

/**
 * GitHub Releases: the fallback source.
 *
 * It exists because a platform review can hide versions from anonymous readers
 * (Modrinth did exactly that during 0.6.0), while repository releases stay public.
 * `assets[].digest` carries `sha256:…`, so the same checksum gate applies as on
 * Hangar.
 */
class GitHubUpdateSource(
    private val http: UpdateHttp,
    private val repository: String = DEFAULT_REPOSITORY,
    override val apiBase: String = DEFAULT_API,
    private val perPage: Int = 20,
) : UpdateManifestSource {
    override val displayName: String = "GitHub Releases"

    override fun fetch(): List<UpdateManifest> = parse(http.getText(releasesUrl()))

    private fun releasesUrl(): String = joinUrl(apiBase, "repos/$repository/releases?per_page=$perPage")

    companion object {
        const val DEFAULT_REPOSITORY = "OrzMC/RealTime"
        const val DEFAULT_API = "https://api.github.com"

        /** Accepts both `/releases` (array) and `/releases/latest` (object). */
        fun parse(body: String): List<UpdateManifest> {
            val roots =
                when (val document = Json.parse(body)) {
                    is JsonValue.Arr -> document.values.mapNotNull { it.asObject() }
                    is JsonValue.Obj -> listOf(document)
                    else -> emptyList()
                }
            return roots.flatMap(::parseRelease)
        }

        private fun parseRelease(release: JsonValue.Obj): List<UpdateManifest> {
            if (release.boolean("draft") == true) return emptyList()
            val tag = release.string("tag_name") ?: return emptyList()
            val version = SemanticVersion.parseOrNull(tag) ?: return emptyList()
            val pageUrl = release.string("html_url")
            return (release.array("assets") ?: emptyList()).mapNotNull { asset ->
                val entry = asset.asObject() ?: return@mapNotNull null
                val name = entry.string("name") ?: return@mapNotNull null
                // The `.sha256` sidecar is not a plugin jar.
                if (!name.endsWith(".jar", ignoreCase = true)) return@mapNotNull null
                val url = entry.string("browser_download_url") ?: return@mapNotNull null
                UpdateManifest(
                    version = version,
                    downloadUrl = url,
                    digest = Digest.parse(entry.string("digest")),
                    sizeBytes = entry.long("size"),
                    source = UpdateSource.GITHUB,
                    pageUrl = pageUrl,
                )
            }
        }
    }
}
