package com.kernelai.app.mcp

import java.security.SecureRandom
import java.util.Base64

class McpEndpoint(val url: String, val token: String) {
    override fun toString(): String = "McpEndpoint($url)"
}

/** Owns one loopback server and rotates its access token on every restart. */
class McpRuntime(executor: McpToolExecutor) {
    private val protocol = McpServer(executor)
    private var server: McpHttpServer? = null
    private var currentEndpoint: McpEndpoint? = null
    val isRunning: Boolean @Synchronized get() = server?.isAlive == true
    val port: Int @Synchronized get() = server?.listeningPort ?: -1
    @Synchronized fun endpoint(): McpEndpoint =
        currentEndpoint?.takeIf { isRunning } ?: error("MCP 服务尚未启动")
    @Synchronized fun start(port: Int = 8080) {
        require(port in 0..65535)
        if (isRunning) return
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val next = McpHttpServer(port, token, protocol)
        try { next.start(10_000, true) }
        catch (error: Exception) { next.stop(); throw error }
        server = next
        currentEndpoint = McpEndpoint("http://127.0.0.1:${next.listeningPort}/mcp", token)
    }
    @Synchronized fun stop() {
        server?.stop()
        server = null
        currentEndpoint = null
    }
}
