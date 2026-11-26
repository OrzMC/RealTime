package cn.orzmc.realtime.paper.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * A loopback HTTP server for tests, so the updater can be exercised without the
 * network. Routes are keyed by path; anything else answers 404.
 */
class TestHttpServer {
    data class Route(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    private val routes = mutableMapOf<String, Route>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val lastUserAgent = AtomicReference<String?>(null)
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun on(
        path: String,
        route: Route,
    ): TestHttpServer {
        routes[path] = route
        return this
    }

    fun json(
        path: String,
        body: String,
    ): TestHttpServer = on(path, Route(200, body.toByteArray(), mapOf("Content-Type" to "application/json")))

    fun redirect(
        path: String,
        location: String,
    ): TestHttpServer = on(path, Route(302, ByteArray(0), mapOf("Location" to location)))

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        requests += exchange.requestURI.toString()
        lastUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
        val route = routes[exchange.requestURI.path]
        if (route == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        route.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
        exchange.sendResponseHeaders(route.status, route.body.size.toLong())
        exchange.responseBody.use { it.write(route.body) }
    }
}
