package com.kernelai.app.mcp

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.NetworkInterface
import java.net.Inet4Address
import kotlinx.serialization.json.*
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Stateless Streamable HTTP: JSON responses on POST; no standalone SSE channel. */
class McpHttpServer(port: Int, private val token: String, private val server: McpServer,
    private val expose: Boolean = false, private val externalToken: String? = null, private val audit: McpAuditTrail = McpAuditTrail()) :
    NanoHTTPD(if (expose) "0.0.0.0" else "127.0.0.1", port) {
    init { require(token.length >= 32); setAsyncRunner(BoundedRunner()) }
    private data class ClientSession(val address: String, val name: String, val createdAt: Long)
    private val clients = ConcurrentHashMap<String, ClientSession>()
    override fun serve(session: IHTTPSession): Response {
        val started = System.nanoTime()
        val remote = session.remoteIpAddress.orEmpty().take(100)
        var external = true
        var client = session.headers["user-agent"].orEmpty().take(120).ifBlank { "未自报名称" }
        var method = session.method.name
        var tool = ""; var purpose = ""; var arguments = ""; var requestId = ""
        var recorded = false
        var responseSession: String? = null
        fun record(status: String) {
            if (recorded) return
            recorded = true
            audit.append(McpAuditEntry(remoteAddress = remote, client = client, external = external, method = method, tool = tool,
                purpose = purpose, arguments = arguments, durationMs = (System.nanoTime() - started) / 1_000_000, status = status, requestId = requestId))
        }
        fun reply(status: Response.Status, text: String) =
            newFixedLengthResponse(status, "application/json; charset=utf-8", text).apply { responseSession?.let { addHeader("Mcp-Session-Id", it) }; record(status.toString()); closeConnection(true) }
        if (session.uri != "/mcp") return reply(Response.Status.NOT_FOUND, "{}")
        val expectedHosts = (if (expose) localAddresses() else setOf("127.0.0.1")).map { "$it:$listeningPort" }.toSet() + "localhost:$listeningPort"
        if (session.headers["host"]?.lowercase() !in expectedHosts) return reply(Response.Status.FORBIDDEN, "{}")
        val origin = session.headers["origin"]
        if (origin != null && !allowedOrigin(origin)) return reply(Response.Status.FORBIDDEN, "{}")
        val authorization = session.headers["authorization"].orEmpty()
        val internal = remote in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1") && MessageDigest.isEqual(authorization.toByteArray(), ("Bearer " + token).toByteArray())
        val authorizedExternal = expose && externalToken != null && MessageDigest.isEqual(authorization.toByteArray(), ("Bearer " + externalToken).toByteArray())
        if (!internal && !authorizedExternal) return reply(Response.Status.UNAUTHORIZED, "{}")
        external = !internal
        if (internal) client = "轻微 App"
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
            val parsed = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull()
            method = parsed?.get("method")?.jsonPrimitive?.contentOrNull.orEmpty().take(100)
            requestId = parsed?.get("id")?.toString().orEmpty().take(100)
            val params = parsed?.get("params") as? JsonObject
            if (external) {
                val suppliedSession = session.headers["mcp-session-id"]
                if (method == "initialize") {
                    val now = System.currentTimeMillis()
                    clients.entries.removeIf { now - it.value.createdAt > TimeUnit.HOURS.toMillis(24) }
                    if (clients.size >= 128) return reply(Response.Status.SERVICE_UNAVAILABLE, "{}")
                    val name = (params?.get("clientInfo") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.take(100) ?: client
                    val id = java.util.UUID.randomUUID().toString()
                    clients[id] = ClientSession(remote, name, now)
                    responseSession = id
                    client = name
                } else if (suppliedSession != null) {
                    val known = clients[suppliedSession]?.takeIf { it.address == remote && System.currentTimeMillis() - it.createdAt <= TimeUnit.HOURS.toMillis(24) }
                        ?: return reply(Response.Status.NOT_FOUND, "{}")
                    client = known.name
                }
            }
            if (method == "tools/call") {
                tool = params?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty().take(100)
                val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
                purpose = args["_purpose"]?.jsonPrimitive?.contentOrNull.orEmpty().take(160)
                arguments = McpAuditTrail.summarize(args)
            }
            val result = runBlocking(Dispatchers.IO) { server.handleMessage(message) }
            val response = result?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            record(when { response?.get("error") != null -> "协议错误"; (response?.get("result") as? JsonObject)?.get("isError") == JsonPrimitive(true) -> "工具失败"; else -> "成功" })
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
    companion object {
        const val MAX_REQUEST_BYTES = 1024 * 1024
        fun localAddresses(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().filterNot { it.isMulticastAddress || it.isAnyLocalAddress }.mapNotNull { it.hostAddress }.toSet() + "127.0.0.1"
        }.getOrDefault(setOf("127.0.0.1"))
    }
}
