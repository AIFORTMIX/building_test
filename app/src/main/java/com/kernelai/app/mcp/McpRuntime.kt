package com.kernelai.app.mcp

import java.security.SecureRandom
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

class McpEndpoint(val url: String, val token: String) {
    override fun toString(): String = "McpEndpoint($url)"
}

/** Owns the integrated host; network exposure is opt-in and tokens rotate on restart. */
data class McpHostState(val running: Boolean = false, val exposed: Boolean = false, val port: Int = -1)

class McpRuntime(executor: McpToolExecutor, val audit: McpAuditTrail = McpAuditTrail()) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(McpHostState())
    val state = _state.asStateFlow()
    private val protocol = McpServer(executor)
    private var server: McpHttpServer? = null
    private var externalToken: String? = null
    private val _exposed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val exposed = _exposed.asStateFlow()
    @Synchronized fun externalAccessToken(): String = externalToken?.takeIf { _exposed.value } ?: error("外部访问未开启")
    @Synchronized fun externalUrls(): List<String> = if (!_exposed.value) emptyList() else McpHttpServer.localAddresses().filter { it != "127.0.0.1" }.map { "http://$it:$port/mcp" }
    private var currentEndpoint: McpEndpoint? = null
    val isRunning: Boolean @Synchronized get() = server?.isAlive == true
    val port: Int @Synchronized get() = server?.listeningPort ?: -1
    @Synchronized fun endpoint(): McpEndpoint =
        currentEndpoint?.takeIf { isRunning } ?: error("MCP 服务尚未启动")
    @Synchronized fun start(port: Int = 8080, expose: Boolean = false) {
        require(port in 0..65535)
        if (isRunning && _exposed.value == expose) return
        if (isRunning) stop()
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val external = if (expose) Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }) else null
        val next = McpHttpServer(port, token, protocol, expose, external, audit)
        try { next.start(10_000, true) }
        catch (error: Exception) { next.stop(); throw error }
        server = next
        externalToken = external
        _exposed.value = expose
        currentEndpoint = McpEndpoint("http://127.0.0.1:${next.listeningPort}/mcp", token)
        _state.value = McpHostState(true, expose, next.listeningPort)
    }
    @Synchronized fun stop() {
        server?.stop()
        server = null
        currentEndpoint = null
        externalToken = null
        _exposed.value = false
        _state.value = McpHostState()
    }
}
