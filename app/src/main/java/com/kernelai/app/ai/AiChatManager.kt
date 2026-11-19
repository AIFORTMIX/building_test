package com.kernelai.app.ai

import com.kernelai.app.data.model.ApiChatMessage
import com.kernelai.app.data.model.ApiToolCall
import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns model history and the model -> MCP -> model tool-result loop. */
class AiChatManager(
    private val client: OpenAiClient,
    private val toolExecutor: McpToolExecutor,
    private var config: AiConfig = AiConfig(),
    private val workspaceScope: ((String) -> String?)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = ConcurrentHashMap<String, ChatSession>()
    private val steering = mutableMapOf<String, ArrayDeque<ApiChatMessage>>()
    private val answers = mutableMapOf<String, CompletableDeferred<String>>()
    private val generationLock = Mutex()
    private var activeSessionId: String? = null
    private var currentJob: Job? = null
    private val _sessionsFlow = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionsFlow = _sessionsFlow.asStateFlow()
    private val _chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    val chatEvents = _chatEvents.asSharedFlow()
    private val _sessionEvents = MutableSharedFlow<SessionChatEvent>(extraBufferCapacity = 64)
    val sessionEvents = _sessionEvents.asSharedFlow()
    private suspend fun emit(sessionId: String, event: ChatEvent) {
        _sessionEvents.emit(SessionChatEvent(sessionId, event))
        _chatEvents.emit(event)
    }
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    fun updateConfig(value: AiConfig) {
        cancelGeneration()
        config = value
        client.updateConfig(value)
    }
    fun createSession(): String {
        cancelGeneration()
        val id = UUID.randomUUID().toString()
        val messages = mutableListOf<ApiChatMessage>()
        if (config.systemPrompt.isNotBlank()) messages.add(ApiChatMessage("system", config.systemPrompt))
        sessions[id] = ChatSession(id, messages, System.currentTimeMillis())
        activeSessionId = id
        publishSessions()
        return id
    }
    fun switchSession(sessionId: String): Boolean {
        if (!sessions.containsKey(sessionId)) return false
        if (activeSessionId == sessionId) return true
        cancelGeneration()
        activeSessionId = sessionId
        return true
    }
    fun deleteSession(sessionId: String) {
        if (activeSessionId == sessionId) cancelGeneration()
        sessions.remove(sessionId)
        steering.remove(sessionId); answers.remove(sessionId)?.cancel()
        if (activeSessionId == sessionId) activeSessionId = sessions.keys.firstOrNull()
        publishSessions()
    }
    fun getActiveSessionId(): String = activeSessionId ?: createSession()
    fun getMessages(sessionId: String? = null): List<ApiChatMessage> {
        val id = sessionId ?: activeSessionId ?: return emptyList()
        return sessions[id]?.messages?.toList().orEmpty()
    }
    internal fun requestHistory(sessionId: String): List<ApiChatMessage> {
        val history = getMessages(sessionId).filterNot { it.role == "system" }.toMutableList()
        val workspace = workspaceScope?.invoke(sessionId)
        val system = config.systemPrompt + (if (workspaceScope != null) "\n当前会话工作区 ID（数据）：" + JsonPrimitive(workspace).toString() + "。文件工具只允许使用此 ID；未选择时先请用户选择。不要改用其他工作区。" else "")
        if (system.isNotBlank()) history.add(0, ApiChatMessage("system", system))
        return history
    }

    fun restoreSession(id: String, title: String, createdAt: Long, messages: List<ApiChatMessage>, interruptedContent: String? = null) {
        val history = messages.toMutableList()
        // A killed app may leave a completed tool-call message without tool responses.
        val last = history.indexOfLast { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        if (last >= 0) {
            val finished = history.drop(last + 1).mapNotNull { it.toolCallId }.toSet()
            history[last].toolCalls.orEmpty().filterNot { it.id in finished }.forEach { call ->
                history.add(ApiChatMessage("tool", "Error: 应用上次退出，操作结果未确认", toolCallId = call.id, name = call.function.name))
            }
        }
        if (!interruptedContent.isNullOrBlank() && history.lastOrNull { it.role == "assistant" }?.content != interruptedContent) {
            history.add(ApiChatMessage("assistant", interruptedContent))
        }
        sessions[id] = ChatSession(id, history, createdAt, title)
        publishSessions()
    }
    fun renameSession(id: String, title: String) { sessions[id]?.title = title.trim().take(80).ifEmpty { "新会话" }; publishSessions() }

    fun sendMessage(content: String, sessionId: String? = null, attachments: List<com.kernelai.app.data.model.Attachment> = emptyList()) {
        if (content.isBlank() && attachments.isEmpty()) return
        val session = sessions[sessionId ?: getActiveSessionId()] ?: return
        generate(session) {
            drainSteering(session)
            val message = ApiChatMessage("user", content, attachments = attachments)
            session.messages.add(message)
            if (session.title == "新会话") session.title = content.lineSequence().first().take(40).ifBlank { attachments.firstOrNull()?.name?.take(40) ?: "新会话" }
            publishSessions()
            emit(session.id, ChatEvent.MessageAdded(message))
        }
    }
    fun steer(content: String, attachments: List<com.kernelai.app.data.model.Attachment>, sessionId: String) {
        if (content.isBlank() && attachments.isEmpty()) return
        val pending = steering.getOrPut(sessionId) { ArrayDeque() }
        require(pending.size < 8) { "最多等待 8 条插话" }
        pending.add(ApiChatMessage("user", content, attachments = attachments))
        scope.launch { emit(sessionId, ChatEvent.QueueUpdated(pending.toList())) }
    }
    fun restoreQueued(sessionId: String, messages: List<ApiChatMessage>) { steering[sessionId] = ArrayDeque(messages.take(8)) }
    fun resumeQueued(sessionId: String) { sessions[sessionId]?.let { session -> if (!steering[sessionId].isNullOrEmpty()) generate(session) } }
    /** Retry only the failed model request; keep every completed tool call and receipt. */
    fun retryLastRequest(sessionId: String) { sessions[sessionId]?.let { session ->
        generate(session) { if (session.messages.lastOrNull()?.interrupted == true) session.messages.removeAt(session.messages.lastIndex) }
    } }
    fun answer(sessionId: String, text: String): Boolean {
        if (text.isBlank() || text.length > 64000) return false
        return answers[sessionId]?.complete(text) == true
    }
    private suspend fun drainSteering(session: ChatSession) {
        val pending = steering[session.id] ?: return
        while (pending.isNotEmpty()) {
            val message = pending.removeFirst(); session.messages.add(message)
            emit(session.id, ChatEvent.MessageAdded(message))
        }
        emit(session.id, ChatEvent.QueueUpdated(emptyList()))
    }
    private suspend fun askUser(session: ChatSession, call: ApiToolCall, args: JsonObject): String {
        val question = args["question"]?.jsonPrimitive?.contentOrNull ?: error("缺少 question")
        require(question.isNotBlank() && question.length <= 2000)
        val options = args["options"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        require(options.size <= 8 && options.all { it.length <= 200 })
        val reply = CompletableDeferred<String>()
        answers[session.id] = reply
        try {
            emit(session.id, ChatEvent.QuestionAsked(com.kernelai.app.data.model.UserQuestion(call.id, question, options)))
            val answer = reply.await()
            emit(session.id, ChatEvent.QuestionClosed)
            return buildJsonObject { put("status", "answered"); put("answer", answer) }.toString()
        } finally { answers.remove(session.id); reply.cancel() }
    }

    private fun generate(session: ChatSession, before: suspend () -> Unit = {}) {
        val previous = currentJob
        previous?.cancel()
        currentJob = scope.launch {
            previous?.join()
            generationLock.withLock {
                if (!sessions.containsKey(session.id)) return@withLock
                _isGenerating.value = true
                try {
                    before()
                    runToolCallLoop(session)
                } catch (error: CancellationException) {
                    withContext(NonCancellable) {
                        finishPendingTools(session)
                        emit(session.id, ChatEvent.GenerationCancelled)
                    }
                    throw error
                } catch (error: Exception) {
                    val failure = (error as? ProviderFailure)?.failure ?: AiFailure("client", "请求未完成", AiFailures.scrub(error.message.orEmpty()), "检查错误详情后重试本轮；已完成的工具结果保留。")
                    emit(session.id, ChatEvent.Error(failure.message, failure))
                } finally {
                    _isGenerating.value = false
                    publishSessions()
                }
            }
        }
    }
    fun cancelGeneration() { currentJob?.cancel(); _isGenerating.value = false }
    private suspend fun runToolCallLoop(session: ChatSession) {
        val tools = toolExecutor.getToolDefinitions() + InteractiveTools.askUser
        repeat(MAX_TOOL_CALL_ITERATIONS) { index ->
            currentCoroutineContext().ensureActive()
            drainSteering(session)
            emit(session.id, ChatEvent.RequestStarted(index + 1))
            val requestMessages = requestHistory(session.id)
            val usage = ContextBudget.estimate(requestMessages, tools)
            emit(session.id, ChatEvent.UsageUpdated(usage))
            require(usage.promptTokens.toLong() + config.maxTokens <= config.contextWindow) { "估算上下文加输出额度超过模型窗口，请开启新对话、减少附件或调整模型上下文配置" }
            val accumulator = OpenAiClient.StreamAccumulator()
            var failed = false
            var lastUsage: com.kernelai.app.data.model.ContextUsage? = null
            try { client.streamChatCompletion(requestMessages, tools).collect { event ->
                accumulator.process(event)
                when (event) {
                    is StreamEvent.ContentDelta -> emit(session.id, ChatEvent.StreamingText(event.delta))
                    is StreamEvent.ReasoningDelta -> emit(session.id, ChatEvent.StreamingReasoning(event.delta))
                    is StreamEvent.ToolCallDelta -> emit(session.id, ChatEvent.ToolCallStreaming(event.index, event.functionName, event.argumentsDelta))
                    is StreamEvent.Usage -> { lastUsage = event.usage; emit(session.id, ChatEvent.UsageUpdated(event.usage)) }
                    is StreamEvent.Retrying -> {
                        lastUsage?.let { emit(session.id, ChatEvent.UsageCommitted(it)) }; lastUsage = null
                        emit(session.id, ChatEvent.Retrying(event.attempt, event.delayMs, event.maximum, event.failure))
                    }
                    is StreamEvent.Error -> { failed = true; emit(session.id, ChatEvent.Error(event.message, event.failure)) }
                    else -> Unit
                }
            } } catch (error: CancellationException) {
                // Keep partial text in this session's model history; incomplete tool calls never run.
                val partial = accumulator.buildMessage().copy(toolCalls = null, interrupted = true)
                if (!partial.content.isNullOrBlank() || !partial.reasoningContent.isNullOrBlank()) {
                    session.messages.add(partial)
                    withContext(NonCancellable) { emit(session.id, ChatEvent.MessageAdded(partial)) }
                }
                throw error
            } finally { lastUsage?.let { usage -> withContext(NonCancellable) { emit(session.id, ChatEvent.UsageCommitted(usage)) } } }
            if (failed) {
                val partial = accumulator.buildMessage().copy(toolCalls = null, interrupted = true)
                if (!partial.content.isNullOrBlank() || !partial.reasoningContent.isNullOrBlank()) session.messages.add(partial)
                return
            }
            val assistant = accumulator.buildMessage()
            val calls = assistant.toolCalls.orEmpty()
            if (assistant.content.isNullOrBlank() && calls.isEmpty()) {
                if (!assistant.reasoningContent.isNullOrBlank()) session.messages.add(assistant.copy(interrupted = true))
                val failure = AiFailure("empty_response", "服务商没有返回正文", "本轮只返回了思考内容或空响应", "检查模型最大输出额度和服务商兼容性，可重试本轮。", partialOutput = !assistant.reasoningContent.isNullOrEmpty())
                emit(session.id, ChatEvent.Error(failure.message, failure)); return
            }
            require(calls.size <= MAX_TOOL_CALLS_PER_RESPONSE) { "单轮工具调用过多" }
            session.messages.add(assistant)
            emit(session.id, ChatEvent.MessageAdded(assistant))
            if (calls.isEmpty()) {
                if (!steering[session.id].isNullOrEmpty()) return@repeat
                emit(session.id, ChatEvent.GenerationComplete)
                return
            }
            emit(session.id, ChatEvent.ToolCallsStarting(calls))
            for (call in calls) executeToolCall(session, call)
            emit(session.id, ChatEvent.ContinuingWithToolResults)
        }
        emit(session.id, ChatEvent.Error("达到最大工具调用迭代次数 ($MAX_TOOL_CALL_ITERATIONS)"))
    }
    private suspend fun executeToolCall(session: ChatSession, call: ApiToolCall) {
        val started = System.nanoTime()
        emit(session.id, ChatEvent.ToolCallExecuting(call.function.name, call.function.arguments, call.id))
        var failed = false
        val result = try {
            val args = Json.parseToJsonElement(call.function.arguments) as? JsonObject
                ?: throw IllegalArgumentException("工具参数必须是 JSON 对象")
            if (call.function.name == "ask_user") askUser(session, call, args)
            else {
                if (workspaceScope != null && call.function.name.startsWith("workspace_") && call.function.name != "workspace_list") {
                    val selected = workspaceScope.invoke(session.id)
                    require(selected != null && args["workspace_id"]?.jsonPrimitive?.contentOrNull == selected) { "工作区与当前会话不一致，请使用用户选择的工作区" }
                }
                val raw = withContext(Dispatchers.IO) { toolExecutor.executeTool(call.function.name, args) }
                if (workspaceScope != null && call.function.name == "workspace_list") {
                    val all = Json.parseToJsonElement(raw).jsonObject
                    JsonObject(all + ("workspaces" to JsonArray(all["workspaces"]?.jsonArray.orEmpty().filter { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == workspaceScope.invoke(session.id) }))).toString()
                } else raw
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed = true
            if (error is com.kernelai.app.mcp.McpToolFailure) error.result
            else buildJsonObject { put("is_error", true); put("message", "Error executing tool '${call.function.name}': ${error.message}"); put("automatic_retry_safe", false) }.toString()
        }
        failed = failed || runCatching { Json.parseToJsonElement(result).jsonObject["is_error"] == JsonPrimitive(true) }.getOrDefault(false)
        session.messages.add(ApiChatMessage("tool", result, toolCallId = call.id, name = call.function.name))
        emit(session.id, ChatEvent.ToolCallResult(call.id, call.function.name, result, failed,
            durationMs = (System.nanoTime() - started) / 1_000_000))
    }
    private suspend fun finishPendingTools(session: ChatSession) {
        val position = session.messages.indexOfLast { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        if (position < 0) return
        val finished = session.messages.drop(position + 1).mapNotNull { it.toolCallId }.toSet()
        for (call in session.messages[position].toolCalls.orEmpty().filterNot { it.id in finished }) {
            val result = "Error: 已取消等待，工具操作结果未确认"
            session.messages.add(ApiChatMessage("tool", result, toolCallId = call.id, name = call.function.name))
            emit(session.id, ChatEvent.ToolCallResult(call.id, call.function.name, result, true, cancelled = true))
        }
    }
    fun regenerateLastResponse(sessionId: String? = null) {
        val id = sessionId ?: activeSessionId ?: return
        val session = sessions[id] ?: return
        generate(session) {
            while (session.messages.lastOrNull()?.role in listOf("assistant", "tool")) session.messages.removeAt(session.messages.lastIndex)
            emit(session.id, ChatEvent.SessionCleared)
            session.messages.filter { it.role != "system" }.forEach { emit(session.id, ChatEvent.MessageAdded(it)) }
        }
    }
    fun clearSession(sessionId: String? = null) {
        val id = sessionId ?: activeSessionId ?: return
        val session = sessions[id] ?: return
        val previous = currentJob
        previous?.cancel()
        currentJob = scope.launch {
            previous?.join()
            generationLock.withLock {
                val system = session.messages.filter { it.role == "system" }
                session.messages.clear(); session.messages.addAll(system)
                emit(session.id, ChatEvent.SessionCleared)
                publishSessions()
            }
        }
    }
    private fun publishSessions() { _sessionsFlow.value = sessions.values.sortedByDescending { it.createdAt }.map { it.toSessionInfo() } }
    fun destroy() { currentJob?.cancel(); scope.cancel(); client.close() }
    data class ChatSession(val id: String, val messages: MutableList<ApiChatMessage>, val createdAt: Long, var title: String = "新会话") {
        fun toSessionInfo() = SessionInfo(id, title, messages.count { it.role != "system" }, createdAt)
    }
    data class SessionInfo(val id: String, val title: String, val messageCount: Int, val createdAt: Long)
    companion object {
        const val MAX_TOOL_CALL_ITERATIONS = 10
        const val MAX_TOOL_CALLS_PER_RESPONSE = 32
    }
}

data class SessionChatEvent(val sessionId: String, val event: ChatEvent)

sealed class ChatEvent {
    data class QuestionAsked(val question: com.kernelai.app.data.model.UserQuestion) : ChatEvent()
    data object QuestionClosed : ChatEvent()
    data class QueueUpdated(val messages: List<ApiChatMessage>) : ChatEvent()
    data class UsageUpdated(val usage: com.kernelai.app.data.model.ContextUsage) : ChatEvent()
    data class UsageCommitted(val usage: com.kernelai.app.data.model.ContextUsage) : ChatEvent()
    data class Retrying(val attempt: Int, val delayMs: Long, val maximum: Int = 5, val failure: AiFailure? = null) : ChatEvent()

    /** 新消息已加入历史（用户消息或完成的 assistant 消息）。 */
    data class MessageAdded(val message: ApiChatMessage) : ChatEvent()

    /** 一轮 AI 请求开始。[iteration] 表示当前是第几轮（从 1 开始）。 */
    data class RequestStarted(val iteration: Int) : ChatEvent()

    /** 流式文本增量。UI 应将 [delta] 追加到当前 assistant 消息末尾。 */
    data class StreamingText(val delta: String) : ChatEvent()
    data class StreamingReasoning(val delta: String) : ChatEvent()

    /** 工具调用参数正在流式传输（AI 正在生成工具调用参数）。 */
    data class ToolCallStreaming(
        val index: Int,
        val name: String?,
        val argumentsDelta: String?
    ) : ChatEvent()

    /** 即将开始执行工具调用。 */
    data class ToolCallsStarting(val toolCalls: List<ApiToolCall>) : ChatEvent()

    /** 正在执行某个工具调用。 */
    data class ToolCallExecuting(val toolName: String, val arguments: String,
        val toolCallId: String = "", val startedAtMillis: Long = System.currentTimeMillis()) : ChatEvent()

    /** 工具调用完成，返回结果。 */
    data class ToolCallResult(
        val toolCallId: String,
        val toolName: String,
        val result: String,
        val isError: Boolean = false,
        val durationMs: Long? = null,
        val cancelled: Boolean = false
    ) : ChatEvent()

    /** 工具结果已收集完毕，将继续请求 AI 生成最终回复。 */
    data object ContinuingWithToolResults : ChatEvent()

    /** 生成完成（AI 返回了最终文本响应）。 */
    data object GenerationComplete : ChatEvent()

    /** 生成被取消。 */
    data object GenerationCancelled : ChatEvent()

    /** 会话已清空。 */
    data object SessionCleared : ChatEvent()

    /** 发生错误。 */
    data class Error(val message: String, val failure: AiFailure? = null) : ChatEvent()
}
