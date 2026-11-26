package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.UpdateManifest

/**
 * One place an update can be published.
 *
 * Implementations only parse their platform's JSON and fetch it; all policy
 * (channel filtering, downgrade protection, HTTPS checks) lives in
 * `UpdatePolicy`, so a source can never decide to offer something on its own.
 */
interface UpdateManifestSource {
    val displayName: String

    /** Base API endpoint, overridable for the CI smoke test. */
    val apiBase: String

    /** Candidate versions, newest first is not required. Throws on transport errors. */
    fun fetch(): List<UpdateManifest>
}

/** Shared helper: absolute API URLs, no duplicate slashes. */
internal fun joinUrl(
    base: String,
    path: String,
): String = base.trimEnd('/') + "/" + path.trimStart('/')
