package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateDecision
import cn.orzmc.realtime.core.update.UpdateManifest
import cn.orzmc.realtime.core.update.UpdatePolicy
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/** Off-thread execution, injected so the service is testable without a server. */
interface UpdateScheduler {
    /** Runs [action] away from the server thread (network and disk work). */
    fun submit(action: () -> Unit)

    /** Repeats [action] off-thread; closing the handle stops the repetition. */
    fun repeat(
        initialDelay: Duration,
        period: Duration,
        action: () -> Unit,
    ): AutoCloseable
}

/**
 * Checks the platforms for a newer build, verifies it and puts it in the server's
 * update folder when asked to.
 *
 * Invariants:
 *
 * - exactly one check or download runs at a time (`AtomicBoolean`), so repeated
 *   `/rtime update` calls cannot multiply traffic;
 * - all network and disk work happens off the server thread, and nothing here
 *   touches a world, chunk, block or entity, so it is safe under Folia;
 * - a check that cannot verify a download still reports the version, it just never
 *   installs it.
 */
class UpdateService(
    private val configuration: () -> UpdateConfiguration,
    private val currentVersion: String,
    private val sources: (UpdateConfiguration) -> List<UpdateManifestSource>,
    private val installer: UpdateDownloader?,
    private val updateFolder: () -> Path?,
    private val stateStore: UpdateStateStore,
    private val scheduler: UpdateScheduler,
    private val logger: Logger,
    private val onAvailable: (UpdateManifest) -> Unit = {},
    private val now: () -> Instant = Instant::now,
) : UpdateAdministration {
    private val statusRef = AtomicReference<UpdateStatus>(UpdateStatus.Idle)
    private val busy = AtomicBoolean(false)
    private var repetition: AutoCloseable? = null

    override fun status(): UpdateStatus = statusRef.get()

    /** Starts the periodic check and prunes state written by an older build. */
    fun start() {
        val config = configuration()
        if (!config.enabled) {
            updateStatus(UpdateStatus.Disabled("disabled in modules/updates.yml"))
            logger.info("RealTime update check disabled in modules/updates.yml.")
            return
        }
        if (config.checksDevelopmentBuilds.not() && isDevelopmentBuild()) {
            updateStatus(UpdateStatus.Disabled("running a development build ($currentVersion)"))
            logger.info("RealTime update check skipped: $currentVersion is a development build.")
            return
        }
        schedule(config)
        logger.info(
            "RealTime update check every ${config.checkInterval.toHours()}h " +
                "(channel=${config.channel.configValue}, source=${config.source.configValue}, mode=${config.mode.configValue}).",
        )
    }

    /** Re-applies `modules/updates.yml` after `/rtime reload`. */
    fun reconfigure() {
        repetition?.runCatching { close() }
        repetition = null
        start()
    }

    fun stop() {
        repetition?.runCatching { close() }
        repetition = null
    }

    override fun checkNow(onResult: (UpdateOutcome) -> Unit) {
        val config = configuration()
        val refusal = refusalFor(config)
        if (refusal != null) {
            onResult(UpdateOutcome.Checked(refusal))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            // A check is already running: report what is known instead of queueing more work.
            onResult(UpdateOutcome.Checked(statusRef.get()))
            return
        }
        statusRef.set(UpdateStatus.Checking)
        scheduler.submit {
            val outcome =
                try {
                    runCheck(config)
                } catch (exception: Exception) {
                    logger.warning("RealTime update check failed unexpectedly: ${exception.message}")
                    UpdateOutcome.Checked(fail(exception.message ?: "unknown error"))
                } finally {
                    busy.set(false)
                }
            onResult(outcome)
        }
    }

    override fun download(onResult: (UpdateOutcome) -> Unit) {
        val config = configuration()
        val refusal = refusalFor(config)
        if (refusal != null) {
            onResult(UpdateOutcome.Checked(refusal))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            onResult(UpdateOutcome.Refused("a check or download is already running"))
            return
        }
        scheduler.submit {
            val outcome =
                try {
                    val offered = (statusRef.get() as? UpdateStatus.Available)?.manifest
                    val manifest =
                        offered ?: (runCheck(config) as? UpdateOutcome.Checked)?.let { result ->
                            (result.status as? UpdateStatus.Available)?.manifest
                        }
                    if (manifest == null) {
                        UpdateOutcome.Refused(noDownloadReason(statusRef.get()))
                    } else {
                        install(config, manifest)
                    }
                } catch (exception: Exception) {
                    UpdateOutcome.Refused(exception.message ?: "unknown error")
                } finally {
                    busy.set(false)
                }
            onResult(outcome)
        }
    }

    private fun schedule(config: UpdateConfiguration) {
        repetition =
            scheduler.repeat(CHECK_DELAY, config.checkInterval) {
                if (busy.compareAndSet(false, true)) {
                    try {
                        runCheck(config)
                    } catch (exception: Exception) {
                        logger.warning("RealTime update check failed unexpectedly: ${exception.message}")
                    } finally {
                        busy.set(false)
                        // The initial status is replaced by the first scheduled check.
                    }
                }
            }
    }

    private fun runCheck(config: UpdateConfiguration): UpdateOutcome {
        val running =
            SemanticVersion.parseOrNull(currentVersion)
                ?: return UpdateOutcome.Checked(fail("running version '$currentVersion' is not a semantic version"))
        val activeSources = sources(config)
        val candidates = mutableListOf<UpdateManifest>()
        val failures = mutableListOf<String>()
        for (source in activeSources) {
            try {
                candidates += source.fetch()
            } catch (exception: Exception) {
                failures += "${source.displayName}: ${exception.message}"
                logger.fine("RealTime update source ${source.displayName} failed: ${exception.message}")
            }
        }
        if (candidates.isEmpty() && failures.isNotEmpty()) {
            return UpdateOutcome.Checked(fail(failures.joinToString("; ")))
        }

        return when (val decision = UpdatePolicy.decide(running, candidates, config.channel)) {
            is UpdateDecision.Available -> {
                val status = UpdateStatus.Available(decision.manifest, now())
                statusRef.set(status)
                notifyIfNew(decision.manifest)
                if (config.mode == UpdateMode.DOWNLOAD) {
                    install(config, decision.manifest)
                } else {
                    UpdateOutcome.Checked(status)
                }
            }
            is UpdateDecision.UpToDate -> {
                val status = UpdateStatus.UpToDate(decision.current, now())
                statusRef.set(status)
                UpdateOutcome.Checked(status)
            }
            UpdateDecision.NoCandidate -> {
                // Reachable and nothing applicable (for example every publication is a
                // pre-release while the channel is `release`): that is "up to date".
                val status = UpdateStatus.UpToDate(running, now())
                statusRef.set(status)
                UpdateOutcome.Checked(status)
            }
        }
    }

    private fun install(
        config: UpdateConfiguration,
        manifest: UpdateManifest,
    ): UpdateOutcome {
        val target = updateFolder() ?: return UpdateOutcome.Refused(UPDATE_FOLDER_UNAVAILABLE)
        val activeInstaller = installer ?: return UpdateOutcome.Refused("the updater is not available on this server")
        if (manifest.digest == null) {
            return UpdateOutcome.Refused("${manifest.version.normalized()} was published without a checksum")
        }
        return try {
            val installed = activeInstaller.install(manifest, target, config.maxDownloadBytes)
            val status = UpdateStatus.Downloaded(manifest, installed.file, now())
            statusRef.set(status)
            UpdateOutcome.Installed(installed.file, manifest.version, installed.bytes)
        } catch (exception: Exception) {
            logger.warning("RealTime update download refused: ${exception.message}")
            UpdateOutcome.Refused(exception.message ?: "download failed")
        }
    }

    private fun notifyIfNew(manifest: UpdateManifest) {
        val version = manifest.version.normalized()
        val alreadyNotified = stateStore.notifiedVersion()
        if (configuration().notifyConsole && alreadyNotified != version) {
            logger.info(
                "RealTime $version is available (running $currentVersion, ${manifest.source.configValue}). " +
                    "Use /rtime update download to fetch it, then restart the server.",
            )
        }
        if (alreadyNotified != version) {
            stateStore.saveNotifiedVersion(version)
            onAvailable(manifest)
        }
    }

    private fun refusalFor(config: UpdateConfiguration): UpdateStatus.Disabled? {
        if (!config.enabled) return UpdateStatus.Disabled("disabled in modules/updates.yml")
        if (config.skipDevBuilds && isDevelopmentBuild()) {
            return UpdateStatus.Disabled("running a development build ($currentVersion)")
        }
        return null
    }

    private fun isDevelopmentBuild(): Boolean = SemanticVersion.parseOrNull(currentVersion)?.isSnapshot ?: false

    private fun noDownloadReason(status: UpdateStatus): String =
        when (status) {
            is UpdateStatus.UpToDate -> "RealTime $currentVersion is already the newest version"
            is UpdateStatus.Downloaded -> "an update is already waiting in the update folder"
            is UpdateStatus.Disabled -> status.reason
            is UpdateStatus.Failed -> status.reason
            else -> "no update is available right now"
        }

    private fun fail(reason: String): UpdateStatus {
        val status = UpdateStatus.Failed(reason, now())
        updateStatus(status)
        return status
    }

    private fun updateStatus(status: UpdateStatus) {
        statusRef.set(status)
    }

    private companion object {
        /** First check runs shortly after startup, off the join/setup path. */
        val CHECK_DELAY: Duration = Duration.ofSeconds(30)

        const val UPDATE_FOLDER_UNAVAILABLE =
            "the server has no update folder (settings.update-folder is empty in bukkit.yml)"
    }
}
