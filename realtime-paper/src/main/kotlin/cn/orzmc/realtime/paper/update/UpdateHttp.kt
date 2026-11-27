package cn.orzmc.realtime.paper.update

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class UpdateHttpException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The only way this plugin talks to the network.
 *
 * Three rules keep an update from becoming a remote code execution channel:
 *
 * 1. HTTPS only. Plain HTTP is accepted only for loopback hosts and only when
 *    `safety.allow-insecure-http` is on (the CI smoke test needs it); everything
 *    else is refused before a socket is opened.
 * 2. The host allowlist is checked on the request **and on every redirect hop**,
 *    because Hangar and Modrinth hand out CDN URLs and GitHub redirects to
 *    `objects.githubusercontent.com`. Redirects are followed manually
 *    (`Redirect.NEVER`) precisely so each hop can be validated.
 * 3. Responses are size-capped, so a hostile or broken endpoint cannot exhaust the
 *    server heap before the checksum is even considered.
 */
class UpdateHttp(
    private val allowedHosts: Set<String>,
    private val allowInsecureHttp: Boolean,
    private val userAgent: String,
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(20),
    private val client: HttpClient = defaultClient(connectTimeout),
) {
    /** Fetches a small text payload (a JSON API response). */
    fun getText(
        url: String,
        maxBytes: Int = MAX_JSON_BYTES,
    ): String {
        val response = send(url, maxBytes.toLong())
        return response.body().use { stream -> stream.readBytesLimited(maxBytes) }.toString(Charsets.UTF_8)
    }

    /**
     * Streams a (possibly large) download to [consumer]. The stream is closed when
     * [consumer] returns, and a byte counter refuses anything above [maxBytes].
     */
    fun <T> stream(
        url: String,
        maxBytes: Long,
        consumer: (InputStream) -> T,
    ): T {
        val response = send(url, maxBytes)
        return response.body().use { stream ->
            consumer(CountingLimitInputStream(stream, maxBytes))
        }
    }

    private fun send(
        url: String,
        maxBytes: Long,
    ): HttpResponse<InputStream> {
        var current = parseUrl(url)
        var hops = 0
        while (true) {
            checkAllowed(current)
            val request =
                HttpRequest
                    .newBuilder(current)
                    .timeout(requestTimeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, application/java-archive, */*")
                    .GET()
                    .build()
            val response =
                try {
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                } catch (exception: IOException) {
                    throw UpdateHttpException("request to ${current.host} failed: ${exception.message}", exception)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw UpdateHttpException("request to ${current.host} was interrupted", exception)
                }

            val status = response.statusCode()
            if (status in REDIRECTS) {
                response.body().close()
                val location =
                    response
                        .headers()
                        .firstValue("Location")
                        .orElse(null)
                        ?: throw UpdateHttpException("HTTP $status without a Location header")
                if (hops++ >= MAX_REDIRECTS) throw UpdateHttpException("too many redirects for $url")
                current = current.resolve(location)
                continue
            }
            if (status !in 200..299) {
                response.body().close()
                throw UpdateHttpException("HTTP $status from ${current.host}")
            }
            val length = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            if (length > maxBytes) {
                response.body().close()
                throw UpdateHttpException("response of $length bytes exceeds the $maxBytes byte limit")
            }
            return response
        }
    }

    private fun parseUrl(url: String): URI {
        val uri =
            try {
                URI(url)
            } catch (exception: IllegalArgumentException) {
                throw UpdateHttpException("invalid update URL: $url", exception)
            }
        if (uri.scheme == null || uri.host == null) throw UpdateHttpException("update URL must be absolute: $url")
        if (uri.rawUserInfo != null) throw UpdateHttpException("update URL must not carry credentials: $url")
        return uri
    }

    private fun checkAllowed(uri: URI) {
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
        when (scheme) {
            "https" -> Unit
            "http" ->
                if (!(allowInsecureHttp && loopback)) {
                    throw UpdateHttpException(
                        "refusing plain HTTP to $host (only loopback with safety.allow-insecure-http)",
                    )
                }
            else -> throw UpdateHttpException("refusing '$scheme' URLs")
        }
        if (host !in allowedHosts && !(allowInsecureHttp && loopback)) {
            throw UpdateHttpException("host '$host' is not in safety.allowed-hosts")
        }
        if (allowInsecureHttp && loopback && scheme == "http") return
        if (uri.port != -1 && uri.port != 443) {
            throw UpdateHttpException("unexpected port ${uri.port} for host $host")
        }
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            if (buffer.size() + read > maxBytes) {
                throw UpdateHttpException("response exceeds the $maxBytes byte limit")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private class CountingLimitInputStream(
        private val delegate: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var counted = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) account(read.toLong())
            return read
        }

        override fun close() = delegate.close()

        private fun account(bytes: Long) {
            counted += bytes
            if (counted > limit) throw UpdateHttpException("download exceeds the $limit byte limit")
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 1 shl 20
        const val MAX_REDIRECTS = 5
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        fun defaultClient(connectTimeout: Duration): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }
}
