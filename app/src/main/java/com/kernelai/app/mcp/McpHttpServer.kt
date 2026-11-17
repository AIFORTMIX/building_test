package com.kernelai.app.mcp

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Stateless Streamable HTTP: JSON responses on POST; no standalone SSE channel. */
class McpHttpServer(port: Int, private val token: String, private val server: McpServer) :
    NanoHTTPD("127.0.0.1", port) {
    init { require(token.length >= 32); setAsyncRunner(BoundedRunner()) }
    override fun serve(session: IHTTPSession): Response {
        fun reply(status: Response.Status, text: String) =
            newFixedLengthResponse(status, "application/json; charset=utf-8", text).apply { closeConnection(true) }
        if (session.uri != "/mcp") return reply(Response.Status.NOT_FOUND, "{}")
        val expectedHosts = setOf("127.0.0.1:$listeningPort", "localhost:$listeningPort")
        if (session.headers["host"]?.lowercase() !in expectedHosts) return reply(Response.Status.FORBIDDEN, "{}")
        val origin = session.headers["origin"]
        if (origin != null && !allowedOrigin(origin)) return reply(Response.Status.FORBIDDEN, "{}")
        val authorization = session.headers["authorization"].orEmpty()
        if (!MessageDigest.isEqual(authorization.toByteArray(), ("Bearer " + token).toByteArray())) {
            return reply(Response.Status.UNAUTHORIZED, "{}")
        }
        if (session.method != Method.POST) return reply(Response.Status.METHOD_NOT_ALLOWED, "{}")
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            return reply(Response.Status.BAD_REQUEST, "{}")
        }
        // Chunked requests and unbounded bodies are intentionally not accepted.
        if (session.headers.containsKey("transfer-encoding")) return reply(Response.Status.BAD_REQUEST, "{}")
        val length = session.headers["content-length"]?.toIntOrNull()
            ?: return reply(Response.Status.BAD_REQUEST, "{}")
        if (length <= 0 || length > MAX_REQUEST_BYTES) return reply(Response.Status.BAD_REQUEST, "{}")
        return try {
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = session.inputStream.read(body, offset, length - offset)
                if (read < 0) return reply(Response.Status.BAD_REQUEST, "{}")
                offset += read
            }
            val message = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body)).toString()
            val result = runBlocking(Dispatchers.IO) { server.handleMessage(message) }
            reply(if (result == null) Response.Status.ACCEPTED else Response.Status.OK, result.orEmpty())
        } catch (_: Exception) {
            reply(Response.Status.BAD_REQUEST, "{}")
        }
    }
    private fun allowedOrigin(origin: String): Boolean = try {
        val uri = URI(origin)
        uri.scheme in setOf("http", "https") && uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") &&
            uri.userInfo == null
    } catch (_: Exception) { false }

    private class BoundedRunner : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val pool = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { work -> Thread(work, "qingwei-mcp-http").apply { isDaemon = true } })
        override fun exec(clientHandler: ClientHandler) {
            clients.add(clientHandler)
            try { pool.execute(clientHandler) }
            catch (_: RejectedExecutionException) { clients.remove(clientHandler); clientHandler.close() }
        }
        override fun closed(clientHandler: ClientHandler) { clients.remove(clientHandler) }
        override fun closeAll() {
            clients.forEach { it.close() }
            clients.clear()
            pool.shutdownNow()
        }
    }
    companion object { const val MAX_REQUEST_BYTES = 1024 * 1024 }
}
