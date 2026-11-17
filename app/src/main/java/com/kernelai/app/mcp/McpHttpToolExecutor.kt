package com.kernelai.app.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** AI tool calls traverse the actual JSON-RPC HTTP endpoint used by other MCP clients. */
class McpHttpToolExecutor(private val endpoint: () -> McpEndpoint) : McpToolExecutor, Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
    private val ids = AtomicLong()
    private val initializeLock = Mutex()
    private var initialized: McpEndpoint? = null
    private var availableTools = emptySet<String>()
    override fun getToolDefinitions() = McpToolDefinitions.ALL_TOOLS.map {
        McpToolExecutor.ToolDefinition(it.name, it.description, McpToolDefinitions.toJsonSchema(it))
    }
    override suspend fun executeTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        val connection = endpoint()
        initializeLock.withLock {
            if (initialized !== connection) {
                rpc(connection, "initialize", buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    putJsonObject("capabilities") { }
                    putJsonObject("clientInfo") { put("name", "qingwei-ai"); put("version", "1.0") }
                })
                rpc(connection, "notifications/initialized", buildJsonObject { }, notification = true)
                availableTools = rpc(connection, "tools/list", buildJsonObject { })
                    ?.get("tools")?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet()
                    ?: throw IOException("MCP 工具列表为空")
                initialized = connection
            }
        }
        require(name in availableTools) { "MCP 服务没有发布工具 $name" }
        val result = rpc(connection, "tools/call", buildJsonObject {
            put("name", name); put("arguments", arguments)
        }) ?: throw IOException("MCP 工具响应为空")
        val text = result["content"]?.jsonArray?.mapNotNull {
            val content = it.jsonObject
            if (content["type"]?.jsonPrimitive?.content == "text") content["text"]?.jsonPrimitive?.content else null
        }?.joinToString("\n") ?: throw IOException("MCP 工具响应缺少 content")
        if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) throw IOException(text)
        text
    }
    private suspend fun rpc(connection: McpEndpoint, method: String, params: JsonObject, notification: Boolean = false): JsonObject? {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); if (!notification) put("id", id)
            put("method", method); put("params", params)
        }
        val request = Request.Builder().url(connection.url)
            .header("Authorization", "Bearer " + connection.token)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-03-26")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine<JsonObject?> { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("MCP HTTP ${it.code}")
                            if (notification) return@use null
                            val source = it.body?.source() ?: throw IOException("MCP 响应体为空")
                            if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("MCP 响应过大")
                            val json = Json.parseToJsonElement(source.readUtf8()).jsonObject
                            if (json["jsonrpc"]?.jsonPrimitive?.content != "2.0" || json["id"]?.jsonPrimitive?.longOrNull != id) {
                                throw IOException("MCP 响应标识不匹配")
                            }
                            json["error"]?.let { throw IOException("MCP 协议错误") }
                            json["result"]?.jsonObject ?: throw IOException("MCP 响应缺少 result")
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }
    override fun close() { http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    companion object { private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024 }
}
