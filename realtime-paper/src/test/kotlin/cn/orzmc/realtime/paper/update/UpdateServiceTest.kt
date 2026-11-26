package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.DigestAlgorithm
import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateManifest
import cn.orzmc.realtime.core.update.UpdateSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Logger

class UpdateServiceTest {
    @TempDir
    lateinit var folder: File

    private val logger = Logger.getLogger("UpdateServiceTest").apply { useParentHandlers = false }

    @Test
    fun `reports the newest release and notifies only once per version`() {
        val store = store()
        val notifications = mutableListOf<String>()
        val service =
            service(sources = listOf(StaticSource(manifests("0.7.0", "0.8.0"))), store = store, onAvailable = {
                notifications +=
                    it.version.normalized()
            })

        val first = check(service)
        val second = check(service)

        assertTrue((first as UpdateOutcome.Checked).status is UpdateStatus.Available)
        assertEquals(listOf("0.8.0"), notifications)
        assertTrue((second as UpdateOutcome.Checked).status is UpdateStatus.Available)
        assertEquals("0.8.0", store.notifiedVersion())
        assertEquals(1, notifications.size)
    }

    @Test
    fun `is up to date when the platforms only know older and equal versions`() {
        val service = service(sources = listOf(StaticSource(manifests("0.7.0", "0.6.9"))))

        val outcome = check(service) as UpdateOutcome.Checked

        assertTrue(outcome.status is UpdateStatus.UpToDate, outcome.status.toString())
    }

    @Test
    fun `reports a failure when every source is down`() {
        val service = service(sources = listOf(FailingSource("hangar is down"), FailingSource("github is down")))

        val outcome = check(service) as UpdateOutcome.Checked
        val status = outcome.status as UpdateStatus.Failed

        assertTrue(status.reason.contains("hangar is down"), status.reason)
        assertTrue(status.reason.contains("github is down"), status.reason)
    }

    @Test
    fun `one failing source does not hide a working one`() {
        val service = service(sources = listOf(FailingSource("hangar is down"), StaticSource(manifests("0.8.0"))))

        val outcome = check(service) as UpdateOutcome.Checked

        assertTrue(outcome.status is UpdateStatus.Available)
    }

    @Test
    fun `download mode installs the new build as soon as it is found`() {
        val downloader = RecordingDownloader()
        val service =
            service(
                sources = listOf(StaticSource(manifests("0.8.0"))),
                downloader = downloader,
                configuration = { UpdateConfiguration(mode = UpdateMode.DOWNLOAD) },
            )

        val outcome = check(service)

        assertEquals(1, downloader.calls)
        assertEquals("0.8.0", (outcome as UpdateOutcome.Installed).version.normalized())
        assertTrue(service.status() is UpdateStatus.Downloaded)
    }

    @Test
    fun `notify mode downloads only when asked`() {
        val downloader = RecordingDownloader()
        val service = service(sources = listOf(StaticSource(manifests("0.8.0"))), downloader = downloader)

        check(service)
        assertEquals(0, downloader.calls)

        val outcome = download(service)
        assertEquals(1, downloader.calls)
        assertTrue(outcome is UpdateOutcome.Installed, outcome.toString())
    }

    @Test
    fun `download checks first when nothing has been checked yet`() {
        val downloader = RecordingDownloader()
        val service = service(sources = listOf(StaticSource(manifests("0.8.0"))), downloader = downloader)

        val outcome = download(service)

        assertTrue(outcome is UpdateOutcome.Installed, outcome.toString())
        assertEquals(1, downloader.calls)
    }

    @Test
    fun `refuses to download when there is nothing newer`() {
        val downloader = RecordingDownloader()
        val service = service(sources = listOf(StaticSource(manifests("0.7.0"))), downloader = downloader)

        val outcome = download(service)

        assertTrue(outcome is UpdateOutcome.Refused, outcome.toString())
        assertEquals(0, downloader.calls)
    }

    @Test
    fun `refuses to install a candidate without a checksum`() {
        val downloader = RecordingDownloader()
        val unsigned = manifests("0.8.0").map { it.copy(digest = null) }
        val service = service(sources = listOf(StaticSource(unsigned)), downloader = downloader)

        val outcome = download(service)

        assertTrue((outcome as UpdateOutcome.Refused).reason.contains("without a checksum"), outcome.reason)
        assertEquals(0, downloader.calls)
    }

    @Test
    fun `honours the release channel`() {
        val service = service(sources = listOf(StaticSource(manifests("0.8.0-SNAPSHOT"))))

        val outcome = check(service) as UpdateOutcome.Checked

        assertTrue(outcome.status is UpdateStatus.UpToDate, outcome.status.toString())
    }

    @Test
    fun `does not check development builds unless asked to`() {
        val service = service(currentVersion = "0.8.0-SNAPSHOT", sources = listOf(StaticSource(manifests("0.9.0"))))

        val outcome = check(service) as UpdateOutcome.Checked
        val status = outcome.status as UpdateStatus.Disabled

        assertTrue(status.reason.contains("development build"), status.reason)
    }

    @Test
    fun `checks development builds when skip-dev-builds is off`() {
        val service =
            service(
                currentVersion = "0.8.0-SNAPSHOT",
                sources = listOf(StaticSource(manifests("0.9.0"))),
                configuration = { UpdateConfiguration(skipDevBuilds = false) },
            )

        assertTrue((check(service) as UpdateOutcome.Checked).status is UpdateStatus.Available)
    }

    @Test
    fun `stays disabled when updates are turned off`() {
        val service = service(sources = listOf(StaticSource(manifests("0.9.0"))), configuration = { UpdateConfiguration(enabled = false) })

        val outcome = check(service) as UpdateOutcome.Checked

        assertTrue((outcome.status as UpdateStatus.Disabled).reason.contains("disabled"), outcome.status.toString())
    }

    @Test
    fun `refuses to install when the server has no update folder`() {
        val service = service(sources = listOf(StaticSource(manifests("0.8.0"))), updateFolder = { null })

        val outcome = download(service)

        assertTrue((outcome as UpdateOutcome.Refused).reason.contains("update folder"), outcome.reason)
    }

    @Test
    fun `a second concurrent check reuses the running one instead of starting another`() {
        val scheduler = ManualScheduler()
        val source = CountingSource(manifests("0.8.0"))
        val service = service(sources = listOf(source), scheduler = scheduler)

        var firstResult: UpdateOutcome? = null
        service.checkNow { firstResult = it }
        service.checkNow { firstResult = it }
        scheduler.runPending()

        assertEquals(1, source.fetches)
        assertTrue(firstResult is UpdateOutcome.Checked)
    }

    private fun check(service: UpdateService): UpdateOutcome {
        var result: UpdateOutcome? = null
        service.checkNow { result = it }
        return checkNotNull(result) { "the inline scheduler should have produced a result" }
    }

    private fun download(service: UpdateService): UpdateOutcome {
        var result: UpdateOutcome? = null
        service.download { result = it }
        return checkNotNull(result) { "the inline scheduler should have produced a result" }
    }

    private fun store() = UpdateStateStore(folder)

    private fun service(
        currentVersion: String = "0.7.0",
        sources: List<UpdateManifestSource>,
        downloader: UpdateDownloader = RecordingDownloader(),
        store: UpdateStateStore = UpdateStateStore(folder),
        scheduler: UpdateScheduler = InlineScheduler(),
        configuration: () -> UpdateConfiguration = { UpdateConfiguration() },
        updateFolder: () -> Path? = { folder.toPath().resolve("plugins/update") },
        onAvailable: (UpdateManifest) -> Unit = {},
    ) = UpdateService(
        configuration = configuration,
        currentVersion = currentVersion,
        sources = { sources },
        installer = downloader,
        updateFolder = updateFolder,
        stateStore = store,
        scheduler = scheduler,
        logger = logger,
        onAvailable = onAvailable,
    )

    private fun manifests(vararg versions: String): List<UpdateManifest> =
        versions.map { version ->
            UpdateManifest(
                version = SemanticVersion.parse(version),
                downloadUrl = "https://hangarcdn.papermc.io/plugins/OrzMC/RealTime/versions/$version/PAPER/RealTime-$version.jar",
                digest = Digest.of(DigestAlgorithm.SHA256, "a".repeat(64)),
                sizeBytes = 2183111,
                source = UpdateSource.HANGAR,
            )
        }

    private class StaticSource(
        private val candidates: List<UpdateManifest>,
    ) : UpdateManifestSource {
        override val displayName: String = "static"
        override val apiBase: String = "http://127.0.0.1"

        override fun fetch(): List<UpdateManifest> = candidates
    }

    private class CountingSource(
        private val candidates: List<UpdateManifest>,
    ) : UpdateManifestSource {
        var fetches = 0
        override val displayName: String = "counting"
        override val apiBase: String = "http://127.0.0.1"

        override fun fetch(): List<UpdateManifest> {
            fetches += 1
            return candidates
        }
    }

    private class FailingSource(
        private val message: String,
    ) : UpdateManifestSource {
        override val displayName: String = "failing"
        override val apiBase: String = "http://127.0.0.1"

        override fun fetch(): List<UpdateManifest> = throw IOException(message)
    }

    private class RecordingDownloader : UpdateDownloader {
        var calls = 0

        override fun install(
            manifest: UpdateManifest,
            updateFolder: Path,
            maxBytes: Long,
        ): InstalledUpdate {
            calls += 1
            val file = updateFolder.resolve("RealTime-${manifest.version.normalized()}.jar")
            return InstalledUpdate(file, manifest.version.normalized(), 2183111, manifest.digest!!)
        }
    }

    private class InlineScheduler : UpdateScheduler {
        override fun submit(action: () -> Unit) = action()

        override fun repeat(
            initialDelay: Duration,
            period: Duration,
            action: () -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }

    private class ManualScheduler : UpdateScheduler {
        private val pending = mutableListOf<() -> Unit>()

        override fun submit(action: () -> Unit) {
            pending += action
        }

        override fun repeat(
            initialDelay: Duration,
            period: Duration,
            action: () -> Unit,
        ): AutoCloseable = AutoCloseable { }

        fun runPending() {
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it() }
        }
    }
}
