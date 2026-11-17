package com.kernelai.app.ai

import com.kernelai.app.data.model.ApiChatMessage
import com.kernelai.app.data.model.ApiToolCall
import com.kernelai.app.data.model.ApiFunctionCall
import com.kernelai.app.mcp.McpToolDefinitions
import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume

/**
 * OpenAI 兼容 API 客户端。
 *
 * 支持标准 Chat Completions 端点，包括：
 * - 流式 SSE 响应（`stream: true`）
 * - Function calling / Tool use
 * - 可配置的 base URL、API key、model
 *
 * 适用于 OpenAI、Azure OpenAI、Ollama、vLLM、LM Studio、DeepSeek 等
 * 任何兼容 OpenAI 格式的服务端。
 */
class OpenAiClient(
    @Volatile private var config: AiConfig
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)  // SSE 流需要较长读取超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .followRedirects(false)
        .build()

    /** 更新配置（例如用户切换模型或 API key）。 */
    fun updateConfig(newConfig: AiConfig) {
        config = newConfig
    }

    // -------------------------------------------------------------------------
    // 公共 API
    // -------------------------------------------------------------------------

    /**
     * 流式聊天补全。
     *
     * 返回 [Flow]，按顺序发射 [StreamEvent]：
     * - [StreamEvent.ContentDelta] — 增量文本片段
     * - [StreamEvent.ToolCallDelta] — 增量工具调用片段
     * - [StreamEvent.Done] — 流结束
     * - [StreamEvent.Error] — 发生错误
     *
     * @param messages 完整的消息历史（含 system / user / assistant / tool）
     * @param tools    可用工具列表（OpenAI function 格式），为空则不启用 function calling
     */
    fun streamChatCompletion(
        messages: List<ApiChatMessage>,
        tools: List<McpToolExecutor.ToolDefinition> = emptyList()
    ): Flow<StreamEvent> = callbackFlow {
        fun reportFailure(message: String, cause: Exception? = null) {
            trySendBlocking(StreamEvent.Error(message, cause))
            trySendBlocking(StreamEvent.Done(null))
            close()
        }

        val request = try {
            buildHttpRequest(config, messages, tools, stream = true)
        } catch (e: IllegalArgumentException) {
            send(StreamEvent.Error("AI 请求配置无效: " + e.message, e))
            send(StreamEvent.Done(null))
            close()
            return@callbackFlow
        }
        val call = httpClient.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    reportFailure("网络请求失败: " + e.message, e)
                } else {
                    close()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (call.isCanceled()) {
                        close()
                        return
                    }
                    if (!resp.isSuccessful) {
                        reportFailure("HTTP " + resp.code)
                        return
                    }

                    val body = resp.body ?: run {
                        reportFailure("响应体为空")
                        return
                    }

                    try {
                        val reader = BufferedReader(InputStreamReader(body.byteStream(),
                            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))
                        val finish = if (resp.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase() == "application/json") {
                            val message = parseNonStreamResponse(readBounded(reader))
                            message.content?.takeIf { it.isNotEmpty() }?.let { trySendBlocking(StreamEvent.ContentDelta(it)) }
                            message.toolCalls.orEmpty().forEachIndexed { index, tc ->
                                trySendBlocking(StreamEvent.ToolCallDelta(index, tc.id, tc.type, tc.function.name, tc.function.arguments))
                            }
                            if (message.toolCalls.isNullOrEmpty()) "stop" else "tool_calls"
                        } else parseSseStream(reader) { event -> trySendBlocking(event) }
                        if (!call.isCanceled()) trySendBlocking(StreamEvent.Done(finish))
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            reportFailure("AI 响应解析失败: " + e.message, e)
                        }
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    /**
     * 非流式聊天补全。直接返回完整的 [ApiChatMessage]。
     */
    suspend fun chatCompletion(
        messages: List<ApiChatMessage>,
        tools: List<McpToolExecutor.ToolDefinition> = emptyList()
    ): Result<ApiChatMessage> {
        val request = try {
            buildHttpRequest(config, messages, tools, stream = false)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { resp ->
                        if (!resp.isSuccessful) {
                            Result.failure(IOException("HTTP " + resp.code))
                        } else {
                            try {
                                val body = resp.body?.charStream()?.use { readBounded(it.buffered()) } ?: throw IOException("响应体为空")
                                Result.success(parseNonStreamResponse(body))
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    /**
     * 将 MCP 工具定义转换为 OpenAI function calling 格式。
     *
     * 返回可直接放入请求体 `tools` 字段的 [JsonArray]。
     */
    fun convertMcpTools(tools: List<McpToolExecutor.ToolDefinition>): JsonArray {
        return buildJsonArray {
            tools.forEach { tool ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    }
                }
            }
        }
    }

    /**
     * 将 [McpToolDefinitions.ToolDef] 列表转换为 OpenAI function calling 格式。
     * 适用于直接使用 [McpToolDefinitions.ALL_TOOLS] 的场景。
     */
    fun convertMcpToolDefs(tools: List<McpToolDefinitions.ToolDef>): JsonArray {
        return buildJsonArray {
            tools.forEach { tool ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", McpToolDefinitions.toJsonSchema(tool))
                    }
                }
            }
        }
    }

    /** 释放 HTTP 连接池资源。 */
    fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // -------------------------------------------------------------------------
    // 请求构建
    // -------------------------------------------------------------------------

    private fun buildRequestBody(
        config: AiConfig,
        messages: List<ApiChatMessage>,
        tools: List<McpToolExecutor.ToolDefinition>,
        stream: Boolean
    ): String {
        return buildJsonObject {
            put("model", config.model)
            put("stream", stream)

            if (config.maxTokens > 0) {
                put("max_tokens", config.maxTokens)
            }
            put("temperature", config.temperature.toDouble())

            // 消息列表
            putJsonArray("messages") {
                messages.forEach { msg ->
                    addJsonObject {
                        put("role", msg.role)
                        when (msg.role) {
                            "assistant" -> {
                                // content 可能为 null（纯 tool_calls 响应）
                                if (msg.content != null) {
                                    put("content", msg.content)
                                } else {
                                    put("content", JsonNull)
                                }
                                // 如果有 tool_calls，附加上
                                msg.toolCalls?.let { calls ->
                                    putJsonArray("tool_calls") {
                                        calls.forEach { tc ->
                                            addJsonObject {
                                                put("id", tc.id)
                                                put("type", tc.type)
                                                putJsonObject("function") {
                                                    put("name", tc.function.name)
                                                    put("arguments", tc.function.arguments)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "tool" -> {
                                // tool 响应消息
                                put("content", msg.content ?: "")
                                msg.toolCallId?.let { put("tool_call_id", it) }
                            }
                            else -> {
                                // system / user
                                put("content", msg.content ?: "")
                            }
                        }
                    }
                }
            }

            // 工具定义
            if (tools.isNotEmpty()) {
                put("tools", convertMcpTools(tools))
                put("tool_choice", "auto")
            }
        }.toString()
    }

    private fun buildHttpRequest(
        config: AiConfig,
        messages: List<ApiChatMessage>,
        tools: List<McpToolExecutor.ToolDefinition>,
        stream: Boolean
    ): Request {
        val url = config.chatCompletionsUrl.toHttpUrl()
        val loopback = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "::1"
        require(config.apiKey.isBlank() || url.isHttps || loopback) {
            "带 API Key 的远程地址必须使用 HTTPS"
        }
        val bodyJson = buildRequestBody(config, messages, tools, stream)
        val request = Request.Builder()
            .url(url)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
        if (config.apiKey.isNotBlank()) {
            request.header("Authorization", "Bearer " + config.apiKey)
        }
        return request.build()
    }

    // -------------------------------------------------------------------------
    // SSE 解析
    // -------------------------------------------------------------------------

    /**
     * 解析 SSE 事件流。
     *
     * SSE 格式：
     * ```
     * data: {"id":"...","choices":[{"delta":{...}}]}
     * data: {"id":"...","choices":[{"delta":{...}}]}
     * data: [DONE]
     * ```
     */
    private fun parseSseStream(reader: BufferedReader, onEvent: (StreamEvent) -> Unit): String {
        val data = StringBuilder()
        var total = 0
        var sawChoice = false
        var finish: String? = null
        fun consume(): Boolean {
            if (data.isEmpty()) return false
            val payload = data.toString().removeSuffix("\n")
            data.setLength(0)
            if (payload == "[DONE]") {
                if (!sawChoice) throw IOException("AI 流中没有有效响应")
                return true
            }
            val chunk = json.parseToJsonElement(payload).jsonObject
            if (chunk["error"] != null) throw IOException("AI 服务返回错误")
            val choices = chunk["choices"]?.jsonArray ?: throw IOException("AI 流缺少 choices")
            if (choices.isEmpty()) return false // optional usage frame
            val choice = choices.first().jsonObject
            sawChoice = true
            val delta = choice["delta"] as? JsonObject
            delta?.get("content")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
                if (it.isNotEmpty()) onEvent(StreamEvent.ContentDelta(it))
            }
            delta?.get("refusal")?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
                if (it.isNotEmpty()) onEvent(StreamEvent.ContentDelta(it))
            }
            delta?.get("tool_calls")?.jsonArray?.forEach { item ->
                val tc = item.jsonObject
                val index = tc["index"]?.jsonPrimitive?.intOrNull ?: throw IOException("工具调用缺少 index")
                require(index in 0 until 32) { "工具调用 index 超出范围" }
                val function = tc["function"] as? JsonObject
                onEvent(StreamEvent.ToolCallDelta(index,
                    tc["id"]?.jsonPrimitive?.contentOrNull,
                    tc["type"]?.jsonPrimitive?.contentOrNull,
                    function?.get("name")?.jsonPrimitive?.contentOrNull,
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull))
            }
            val reason = choice["finish_reason"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
            if (!reason.isNullOrBlank()) {
                finish = reason
                onEvent(StreamEvent.FinishReason(reason))
                return true
            }
            return false
        }
        var firstLine = true
        while (true) {
            val rawLine = readBoundedLine(reader) ?: break
            val line = if (firstLine) rawLine.removePrefix("\uFEFF") else rawLine
            firstLine = false
            total += line.length
            if (total > MAX_RESPONSE_CHARS) throw IOException("AI 响应过大")
            if (line.isEmpty()) {
                if (consume()) return finish ?: "stop"
            } else if (line == "data" || line.startsWith("data:")) {
                val value = line.substringAfter(':', "").removePrefix(" ")
                if (data.length + value.length > MAX_EVENT_CHARS) throw IOException("AI 流事件过大")
                data.append(value).append('\n')
            }
        }
        if (consume()) return finish ?: "stop"
        throw IOException("AI 流在结束标记前中断")
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val char = reader.read()
            if (char < 0) return line.takeIf { it.isNotEmpty() }?.toString()
            if (char == '\n'.code) return line.toString()
            if (char == '\r'.code) {
                reader.mark(1)
                val next = reader.read()
                if (next >= 0 && next != '\n'.code) reader.reset()
                return line.toString()
            }
            if (line.length >= MAX_EVENT_CHARS) throw IOException("AI 流事件行过大")
            line.append(char.toChar())
        }
    }
    private fun readBounded(reader: BufferedReader): String {
        val result = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val size = reader.read(buffer)
            if (size < 0) return result.toString()
            if (result.length + size > MAX_RESPONSE_CHARS) throw IOException("AI 响应过大")
            result.append(buffer, 0, size)
        }
    }

    // -------------------------------------------------------------------------
    // 非流式响应解析
    // -------------------------------------------------------------------------

    private fun parseNonStreamResponse(body: String): ApiChatMessage {
        val responseObj = json.parseToJsonElement(body).jsonObject
        val choices = responseObj["choices"]?.jsonArray
            ?: throw IOException("响应中缺少 choices 字段")

        if (choices.isEmpty()) {
            throw IOException("choices 数组为空")
        }

        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw IOException("响应中缺少 message 字段")

        return parseAssistantMessage(message)
    }

    private fun parseAssistantMessage(msgObj: JsonObject): ApiChatMessage {
        val role = msgObj["role"]?.jsonPrimitive?.content ?: "assistant"
        require(role == "assistant") { "AI 响应角色无效" }
        val content = (msgObj["content"]?.takeIf { it !is JsonNull }
            ?: msgObj["refusal"]?.takeIf { it !is JsonNull })?.jsonPrimitive?.content

        val toolCalls = msgObj["tool_calls"]?.jsonArray?.map { tcElem ->
            val tc = tcElem.jsonObject
            ApiToolCall(
                id = tc["id"]?.jsonPrimitive?.content ?: "",
                type = tc["type"]?.jsonPrimitive?.content ?: "function",
                function = ApiFunctionCall(
                    name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "",
                    arguments = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
                )
            )
        }

        return ApiChatMessage(
            role = role,
            content = content,
            toolCalls = toolCalls?.takeIf { it.isNotEmpty() }
        )
    }

    // -------------------------------------------------------------------------
    // 流式响应累积器
    // -------------------------------------------------------------------------

    /**
     * 将流式事件累积为完整的 [ApiChatMessage]。
     *
     * 在 [AiChatManager] 中使用：一边通过 Flow 向 UI 发射增量内容，
     * 一边累积完整响应以便后续处理 tool_calls。
     */
    companion object {
        private const val MAX_EVENT_CHARS = 256 * 1024
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    }

    class StreamAccumulator {
        private val contentBuilder = StringBuilder()
        private val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        private var finishReason: String? = null

        /** 处理一个流事件，返回同一事件供上游继续发射。 */
        fun process(event: StreamEvent): StreamEvent {
            when (event) {
                is StreamEvent.ContentDelta -> contentBuilder.append(event.delta)
                is StreamEvent.ToolCallDelta -> {
                    val acc = toolCallAccumulators.getOrPut(event.index) {
                        ToolCallAccumulator(event.index)
                    }
                    event.id?.let { if (acc.id == null) acc.id = it else if (acc.id != it) acc.id += it }
                    event.type?.let { acc.type = it }
                    event.functionName?.let { if (acc.name == null) acc.name = it else if (acc.name != it) acc.name += it }
                    event.argumentsDelta?.let { acc.argumentsBuilder.append(it) }
                }
                is StreamEvent.FinishReason -> finishReason = event.reason
                is StreamEvent.Done -> { /* 标记结束 */ }
                is StreamEvent.Error -> { /* 错误已上报 */ }
            }
            return event
        }

        /** 构建累积完成的 [ApiChatMessage]。 */
        fun buildMessage(): ApiChatMessage {
            val content = contentBuilder.toString().ifEmpty { null }
            val toolCalls = toolCallAccumulators.values
                .sortedBy { it.index }
                .map { acc ->
                    ApiToolCall(
                        id = acc.id ?: "call_${acc.index}",
                        type = acc.type ?: "function",
                        function = ApiFunctionCall(
                            name = acc.name ?: "",
                            arguments = acc.argumentsBuilder.toString().ifEmpty { "{}" }
                        )
                    )
                }

            return ApiChatMessage(
                role = "assistant",
                content = content,
                toolCalls = toolCalls.takeIf { it.isNotEmpty() }
            )
        }

        fun getFinishReason(): String? = finishReason

        private data class ToolCallAccumulator(
            val index: Int,
            var id: String? = null,
            var type: String? = null,
            var name: String? = null,
            val argumentsBuilder: StringBuilder = StringBuilder()
        )
    }
}

// =============================================================================
// 流事件密封类
// =============================================================================

/**
 * SSE 流式响应事件。
 */
sealed class StreamEvent {
    /** 增量文本内容。 */
    data class ContentDelta(val delta: String) : StreamEvent()

    /** 增量工具调用片段。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val type: String?,
        val functionName: String?,
        val argumentsDelta: String?
    ) : StreamEvent()

    /** 结束原因（stop / tool_calls / length 等）。 */
    data class FinishReason(val reason: String) : StreamEvent()

    /** 流正常结束。 */
    data class Done(val finishReason: String?) : StreamEvent()

    /** 发生错误。 */
    data class Error(val message: String, val exception: Throwable?) : StreamEvent()
}
