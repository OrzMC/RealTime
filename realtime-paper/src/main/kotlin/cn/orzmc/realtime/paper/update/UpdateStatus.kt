package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateManifest
import java.nio.file.Path
import java.time.Instant

/** What `/rtime status` and `/rtime update` can report. Immutable, safe to read from any thread. */
sealed interface UpdateStatus {
    /** The updater is off, or the running build is one it refuses to check. */
    data class Disabled(
        val reason: String,
    ) : UpdateStatus

    /** Enabled but no check has run yet. */
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    /** A check completed and the running version is current. */
    data class UpToDate(
        val current: SemanticVersion,
        val checkedAt: Instant,
    ) : UpdateStatus

    /** A newer version was published. [manifest] is null when it cannot be verified. */
    data class Available(
        val manifest: UpdateManifest,
        val checkedAt: Instant,
    ) : UpdateStatus

    /** The jar is already in the server's update folder; a restart applies it. */
    data class Downloaded(
        val manifest: UpdateManifest,
        val file: Path,
        val checkedAt: Instant,
    ) : UpdateStatus

    /** The check itself failed (offline, rate limited, malformed payload). */
    data class Failed(
        val reason: String,
        val checkedAt: Instant,
    ) : UpdateStatus
}

/** Outcome of an explicit `/rtime update` / `/rtime update download`. */
sealed interface UpdateOutcome {
    data class Checked(
        val status: UpdateStatus,
    ) : UpdateOutcome

    data class Installed(
        val file: Path,
        val version: SemanticVersion,
        val bytes: Long,
    ) : UpdateOutcome

    data class Refused(
        val reason: String,
    ) : UpdateOutcome
}

/**
 * The surface the command layer is allowed to use. Keeping it an interface means
 * `/rtime update` is testable without a network, a scheduler or a plugin.
 */
interface UpdateAdministration {
    fun status(): UpdateStatus

    /** Runs a check off the server thread and hands the resulting status back. */
    fun checkNow(onResult: (UpdateOutcome) -> Unit)

    /** Downloads the currently offered version into the update folder. */
    fun download(onResult: (UpdateOutcome) -> Unit)
}
