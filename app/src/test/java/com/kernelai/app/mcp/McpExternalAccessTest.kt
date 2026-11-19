package com.kernelai.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class McpExternalAccessTest {
    private val executor = object : McpToolExecutor {
        override fun getToolDefinitions() = listOf(McpToolExecutor.ToolDefinition("inspect", "Read only", buildJsonObject { put("type", "object") }))
        override suspend fun executeTool(name: String, arguments: JsonObject) = if (arguments["fail"] == JsonPrimitive(true)) "{\"is_error\":true,\"error\":\"partial read\"}" else "{\"ok\":true}"
    }
    @Test fun externalTokensAreDistinctRotatedAndCallsAreAudited() {
        val runtime = McpRuntime(executor)
        val http = OkHttpClient()
        try {
            runtime.start(0, expose = true)
            val endpoint = runtime.endpoint()
            val external = runtime.externalAccessToken()
            assertNotEquals(endpoint.token, external)
            runtime.ensureLocal(0) // A stale Activity startup cannot downgrade this external host.
            assertTrue(runtime.state.value.exposed)
            assertSame(endpoint, runtime.endpoint())
            assertEquals(external, runtime.externalAccessToken())
            var sessionId: String? = null
            fun call(method: String, params: JsonObject, token: String = external): Response {
                val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 7); put("method", method); put("params", params) }
                return http.newCall(Request.Builder().url(endpoint.url).header("Authorization", "Bearer $token")
                    .apply { if (method != "initialize" && sessionId != null) header("Mcp-Session-Id", sessionId!!) }
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
            }
            call("initialize", buildJsonObject { putJsonObject("clientInfo") { put("name", "external-test-client") } }).use { assertEquals(200, it.code); sessionId = it.header("Mcp-Session-Id"); assertNotNull(sessionId) }
            call("tools/call", buildJsonObject { put("name", "inspect"); putJsonObject("arguments") { put("address", "0x1234"); put("_purpose", "核对测试内存"); put("api_key", "must-not-appear"); put("fail", true) } }).use {
                assertEquals(200, it.code); assertTrue(it.body!!.string().contains("isError"))
            }
            val entry = runtime.audit.entries.value.first()
            assertTrue(entry.external); assertEquals("external-test-client", entry.client)
            assertEquals("inspect", entry.tool); assertEquals("核对测试内存", entry.purpose)
            assertEquals("工具失败", entry.status); assertTrue(entry.durationMs >= 0)
            assertFalse(entry.arguments.contains("must-not-appear")); assertTrue(entry.arguments.contains("0x1234"))
            call("tools/list", JsonObject(emptyMap()), endpoint.token).use { assertEquals(200, it.code) }
            assertFalse(runtime.audit.entries.value.first().external)
            val port = runtime.port
            runtime.start(port, expose = false)
            assertFalse(runtime.state.value.exposed)
            assertThrows(IllegalStateException::class.java) { runtime.externalAccessToken() }
            call("tools/list", JsonObject(emptyMap()), external).use { assertEquals(401, it.code) }
            assertTrue(runtime.audit.entries.value.first().status.contains("UNAUTHORIZED"))
        } finally { runtime.stop(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
    @Test fun exposureIsOffByDefaultAndUnknownHostsStillFail() {
        val runtime = McpRuntime(executor)
        val http = OkHttpClient()
        try {
            runtime.start(0)
            assertFalse(runtime.exposed.value); assertTrue(runtime.externalUrls().isEmpty())
            val endpoint = runtime.endpoint()
            http.newCall(Request.Builder().url(endpoint.url).header("Host", "attacker.invalid:${runtime.port}")
                .header("Authorization", "Bearer ${endpoint.token}").post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(403, it.code) }
        } finally { runtime.stop(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
