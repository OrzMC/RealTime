package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.DigestAlgorithm
import cn.orzmc.realtime.core.update.SemanticVersion
import cn.orzmc.realtime.core.update.UpdateManifest
import cn.orzmc.realtime.core.update.UpdateSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.logging.Logger

class UpdateInstallerTest {
    private val server = TestHttpServer()
    private val logger = Logger.getLogger("UpdateInstallerTest").apply { useParentHandlers = false }

    @TempDir
    lateinit var folder: File

    @AfterEach
    fun tearDown() = server.stop()

    @Test
    fun `verifies the checksum and writes the versioned jar into the update folder`() {
        val jar = ByteArray(4096) { (it % 61).toByte() }
        server.on("/RealTime-0.7.0.jar", TestHttpServer.Route(200, jar))
        val updateFolder = folder.toPath().resolve("plugins/update")

        val installed = installer().install(manifest(jar), updateFolder, MAX_BYTES)

        assertEquals("RealTime-0.7.0.jar", installed.file.fileName.toString())
        assertEquals(jar.size.toLong(), installed.bytes)
        assertEquals(jar.toList(), Files.readAllBytes(installed.file).toList())
        // Nothing half-written is left behind, and nothing outside the folder was touched.
        assertFalse(Files.exists(updateFolder.resolve("RealTime-0.7.0.jar.part")))
        assertEquals(listOf("RealTime-0.7.0.jar"), Files.list(updateFolder).use { it.map { p -> p.fileName.toString() }.toList() })
    }

    @Test
    fun `a checksum mismatch leaves no jar and no partial file`() {
        val jar = ByteArray(2048) { 7 }
        server.on("/RealTime-0.8.0.jar", TestHttpServer.Route(200, jar))
        val updateFolder = folder.toPath().resolve("update")
        val wrongDigest = Digest.compute(DigestAlgorithm.SHA256, "something else".toByteArray())

        val failure =
            assertThrows(UpdateInstallException::class.java) {
                installer().install(manifest(jar, version = "0.8.0", digest = wrongDigest), updateFolder, MAX_BYTES)
            }

        assertTrue(failure.message!!.contains("checksum mismatch"), failure.message)
        assertTrue(Files.list(updateFolder).use { it.toList() }.isEmpty())
    }

    @Test
    fun `a size mismatch is caught before the jar becomes eligible for the swap`() {
        val jar = ByteArray(1024) { 3 }
        server.on("/RealTime-0.8.0.jar", TestHttpServer.Route(200, jar))
        val updateFolder = folder.toPath().resolve("update")
        val manifest = manifest(jar, version = "0.8.0").copy(sizeBytes = 4096)

        val failure = assertThrows(UpdateInstallException::class.java) { installer().install(manifest, updateFolder, MAX_BYTES) }

        assertTrue(failure.message!!.contains("size mismatch"), failure.message)
        assertTrue(Files.list(updateFolder).use { it.toList() }.isEmpty())
    }

    @Test
    fun `refuses to install a publication without a checksum`() {
        val jar = ByteArray(16)
        val updateFolder = folder.toPath().resolve("update")

        val failure =
            assertThrows(UpdateInstallException::class.java) {
                installer().install(manifest(jar).copy(digest = null), updateFolder, MAX_BYTES)
            }

        assertTrue(failure.message!!.contains("no checksum"), failure.message)
        assertFalse(Files.exists(updateFolder))
    }

    @Test
    fun `replaces an older pending download instead of queueing two jars`() {
        val updateFolder = folder.toPath().resolve("update")
        Files.createDirectories(updateFolder)
        Files.write(updateFolder.resolve("RealTime-0.6.0.jar"), ByteArray(8))
        Files.write(updateFolder.resolve("RealTime-0.6.1.jar.part"), ByteArray(8))
        // Someone else's file must survive.
        Files.write(updateFolder.resolve("OtherPlugin-1.0.jar"), ByteArray(8))

        val jar = ByteArray(512) { 9 }
        server.on("/RealTime-0.7.0.jar", TestHttpServer.Route(200, jar))
        installer().install(manifest(jar), updateFolder, MAX_BYTES)

        val names = Files.list(updateFolder).use { it.map { p -> p.fileName.toString() }.sorted().toList() }
        assertTrue(names.contains("RealTime-0.7.0.jar"), names.toString())
        assertFalse(names.contains("RealTime-0.6.0.jar"), names.toString())
        assertFalse(names.contains("RealTime-0.6.1.jar.part"), names.toString())
        assertTrue(names.contains("OtherPlugin-1.0.jar"), names.toString())
    }

    @Test
    fun `refuses a download that exceeds the configured size`() {
        server.on("/RealTime-0.7.0.jar", TestHttpServer.Route(200, ByteArray(8192)))

        assertThrows(UpdateInstallException::class.java) {
            installer().install(manifest(ByteArray(8192)), folder.toPath().resolve("update"), maxBytes = 1024)
        }
    }

    private fun installer() =
        UpdateInstaller(
            http =
                UpdateHttp(
                    allowedHosts = setOf("127.0.0.1"),
                    allowInsecureHttp = true,
                    userAgent = "RealTime/test",
                ),
            logger = logger,
        )

    private fun manifest(
        jar: ByteArray,
        version: String = "0.7.0",
        digest: Digest = Digest.compute(DigestAlgorithm.SHA256, jar),
    ) = UpdateManifest(
        version = SemanticVersion.parse(version),
        downloadUrl = "${server.baseUrl}/RealTime-$version.jar",
        digest = digest,
        sizeBytes = jar.size.toLong(),
        source = UpdateSource.HANGAR,
    )

    private companion object {
        const val MAX_BYTES = 8L * 1024L * 1024L
    }
}
