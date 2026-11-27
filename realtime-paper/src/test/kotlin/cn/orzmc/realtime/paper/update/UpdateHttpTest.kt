package cn.orzmc.realtime.paper.update

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class UpdateHttpTest {
    private val server = TestHttpServer()

    @AfterEach
    fun tearDown() = server.stop()

    private fun loopbackClient(allowed: Set<String> = setOf("127.0.0.1")) =
        UpdateHttp(
            allowedHosts = allowed,
            allowInsecureHttp = true,
            userAgent = "RealTime/test",
            connectTimeout = Duration.ofSeconds(2),
            requestTimeout = Duration.ofSeconds(5),
        )

    @Test
    fun `reads a json body and sends its user agent`() {
        server.json("/api/x", """{"ok":true}""")

        val body = loopbackClient().getText("${server.baseUrl}/api/x")

        assertEquals("""{"ok":true}""", body)
        assertEquals("RealTime/test", server.lastUserAgent.get())
    }

    @Test
    fun `refuses a host that is not on the allowlist before opening a socket`() {
        val http = loopbackClient(allowed = setOf("hangar.papermc.io"))

        val failure = assertThrows(UpdateHttpException::class.java) { http.getText("https://example.com/x") }

        assertTrue(failure.message!!.contains("allowed-hosts"), failure.message)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `refuses plain http to anything but loopback`() {
        val http = loopbackClient(allowed = setOf("127.0.0.1", "example.com"))

        val failure = assertThrows(UpdateHttpException::class.java) { http.getText("http://example.com/x") }

        assertTrue(failure.message!!.contains("plain HTTP"), failure.message)
    }

    @Test
    fun `refuses an insecure scheme or embedded credentials`() {
        val http = loopbackClient()

        assertThrows(UpdateHttpException::class.java) { http.getText("file:///etc/passwd") }
        assertThrows(UpdateHttpException::class.java) { http.getText("http://user:pass@127.0.0.1/x") }
    }

    @Test
    fun `follows a redirect to another allowlisted host and revalidates every hop`() {
        server.json("/target", """{"ok":true}""")
        server.redirect("/start", "${server.baseUrl}/target")

        assertEquals("""{"ok":true}""", loopbackClient().getText("${server.baseUrl}/start"))
    }

    @Test
    fun `refuses a redirect that leaves the allowlist`() {
        server.redirect("/teleport", "http://169.254.169.254/latest/meta-data/")

        val failure = assertThrows(UpdateHttpException::class.java) { loopbackClient().getText("${server.baseUrl}/teleport") }

        assertTrue(failure.message!!.contains("plain HTTP") || failure.message!!.contains("allowed-hosts"), failure.message)
    }

    @Test
    fun `reports http errors instead of returning an empty body`() {
        val failure = assertThrows(UpdateHttpException::class.java) { loopbackClient().getText("${server.baseUrl}/missing") }

        assertTrue(failure.message!!.contains("HTTP 404"), failure.message)
    }

    @Test
    fun `stops reading a response that exceeds the limit`() {
        server.on("/big", TestHttpServer.Route(200, ByteArray(64 * 1024) { 'a'.code.toByte() }))

        val failure = assertThrows(UpdateHttpException::class.java) { loopbackClient().getText("${server.baseUrl}/big", maxBytes = 1024) }

        assertTrue(failure.message!!.contains("limit"), failure.message)
    }

    @Test
    fun `streams a download through the byte counter`() {
        val payload = ByteArray(4096) { it.toByte() }
        server.on("/jar", TestHttpServer.Route(200, payload))

        val read =
            loopbackClient().stream("${server.baseUrl}/jar", 8192) { stream ->
                stream.readBytes().also { assertEquals(payload.size, it.size) }
            }

        assertEquals(payload.toList(), read.toList())
    }

    @Test
    fun `refuses a download above the configured size`() {
        server.on("/jar", TestHttpServer.Route(200, ByteArray(4096)))

        assertThrows(UpdateHttpException::class.java) {
            loopbackClient().stream("${server.baseUrl}/jar", 1024) { stream -> stream.readBytes() }
        }
    }
}
