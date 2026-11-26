package cn.orzmc.realtime.paper.update

import cn.orzmc.realtime.core.update.Digest
import cn.orzmc.realtime.core.update.UpdateManifest
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.logging.Logger

class UpdateInstallException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class InstalledUpdate(
    val file: Path,
    val version: String,
    val bytes: Long,
    val digest: Digest,
)

/** The install step, injectable so the service can be tested without a network. */
interface UpdateDownloader {
    fun install(
        manifest: UpdateManifest,
        updateFolder: Path,
        maxBytes: Long,
    ): InstalledUpdate
}

/**
 * Writes a verified jar into the server's update folder.
 *
 * Paper applies that folder at plugin load time (`FileProviderSource.checkUpdate`):
 * it reads the candidate's `plugin.yml` name, copies it over the running jar, renames
 * the jar to the candidate's file name and deletes the candidate. Two consequences
 * drive this class:
 *
 * - the partial download must **not** look like a plugin jar, or an interrupted
 *   download would be picked up on the next start — hence the `.part` suffix;
 * - the checksum has to be verified *before* the rename, because the rename is what
 *   makes the file eligible for the swap.
 *
 * This class never touches `plugins/` itself, never deletes the running jar and
 * never restarts the server.
 */
class UpdateInstaller(
    private val http: UpdateHttp,
    private val logger: Logger,
) : UpdateDownloader {
    override fun install(
        manifest: UpdateManifest,
        updateFolder: Path,
        maxBytes: Long,
    ): InstalledUpdate {
        val expected =
            manifest.digest
                ?: throw UpdateInstallException("no checksum was published for ${manifest.version.normalized()}, refusing to install")
        val fileName = "RealTime-${manifest.version.normalized()}.jar"
        val folder = updateFolder.normalize()
        val target = folder.resolve(fileName).normalize()
        if (target.parent != folder) {
            throw UpdateInstallException("refusing to write outside the update folder: $target")
        }

        try {
            Files.createDirectories(folder)
        } catch (exception: IOException) {
            throw UpdateInstallException("cannot create $folder: ${exception.message}", exception)
        }

        val partial = folder.resolve("$fileName.part")
        Files.deleteIfExists(partial)
        val messageDigest = MessageDigest.getInstance(expected.algorithm.jdkName)
        val bytes =
            try {
                http.stream(manifest.downloadUrl, maxBytes) { stream ->
                    stream.copyThrough(messageDigest, partial, maxBytes)
                }
            } catch (exception: UpdateHttpException) {
                Files.deleteIfExists(partial)
                throw UpdateInstallException("download failed: ${exception.message}", exception)
            } catch (exception: IOException) {
                Files.deleteIfExists(partial)
                throw UpdateInstallException("cannot write $partial: ${exception.message}", exception)
            }

        val actual = Digest.of(expected.algorithm, messageDigest.digest().toHex())
        if (actual == null || !expected.matches(actual)) {
            Files.deleteIfExists(partial)
            throw UpdateInstallException(
                "checksum mismatch for ${manifest.version.normalized()}: expected $expected, got ${actual ?: "unparsable"}",
            )
        }
        manifest.sizeBytes?.let { expectedSize ->
            if (bytes != expectedSize) {
                Files.deleteIfExists(partial)
                throw UpdateInstallException(
                    "size mismatch for ${manifest.version.normalized()}: expected $expectedSize bytes, got $bytes",
                )
            }
        }

        try {
            removeStaleVersions(folder, keep = setOf(fileName, "$fileName.part"))
            moveIntoPlace(partial, target)
        } catch (exception: IOException) {
            Files.deleteIfExists(partial)
            throw UpdateInstallException("cannot move the download into place: ${exception.message}", exception)
        }

        logger.info(
            "RealTime ${manifest.version.normalized()} saved as ${folder.fileName}/${target.fileName} " +
                "($bytes bytes, ${expected.algorithm.name.lowercase()} verified). Restart the server to apply it.",
        )
        return InstalledUpdate(target, manifest.version.normalized(), bytes, actual)
    }

    /**
     * Prefers an atomic rename (a crash cannot leave a half-written jar under the
     * final name), and falls back to a replacing move on file systems that refuse
     * `ATOMIC_MOVE` across each other's semantics.
     */
    private fun moveIntoPlace(
        partial: Path,
        target: Path,
    ) {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(target)
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Removes earlier pending downloads so exactly one RealTime jar waits in the
     * update folder. Only names this class writes are considered; nothing else in
     * the folder is touched.
     */
    private fun removeStaleVersions(
        folder: Path,
        keep: Set<String>,
    ) {
        val stale =
            Files.newDirectoryStream(folder).use { stream ->
                stream.filter { Files.isRegularFile(it) && PENDING_PATTERN.matches(it.fileName.toString()) }
            }
        stale
            .filter { it.fileName.toString() !in keep }
            .forEach { file ->
                runCatching {
                    Files.deleteIfExists(file)
                    logger.info("RealTime removed the superseded pending update ${file.fileName}.")
                }.onFailure { exception ->
                    logger.warning("RealTime could not remove the stale pending update ${file.fileName}: ${exception.message}")
                }
            }
    }

    private fun InputStream.copyThrough(
        digest: MessageDigest,
        target: Path,
        maxBytes: Long,
    ): Long {
        var total = 0L
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw UpdateHttpException("download exceeds the $maxBytes byte limit")
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
            output.flush()
        }
        return total
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }

    private companion object {
        /** Only files this installer writes are ever deleted (finished or partial). */
        val PENDING_PATTERN = Regex("""RealTime-[0-9A-Za-z.\-+]+\.jar(\.part)?""")
    }
}
