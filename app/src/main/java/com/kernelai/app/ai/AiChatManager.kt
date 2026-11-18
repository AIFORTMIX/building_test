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
    private var config: AiConfig = AiConfig()
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
                    emit(session.id, ChatEvent.Error("生成失败: ${error.message}"))
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
            val usage = ContextBudget.estimate(session.messages, tools)
            emit(session.id, ChatEvent.UsageUpdated(usage))
            require(usage.promptTokens.toLong() + config.maxTokens <= config.contextWindow) { "估算上下文加输出额度超过模型窗口，请开启新对话、减少附件或调整模型上下文配置" }
            val accumulator = OpenAiClient.StreamAccumulator()
            var failed = false
            try { client.streamChatCompletion(session.messages.toList(), tools).collect { event ->
                accumulator.process(event)
                when (event) {
                    is StreamEvent.ContentDelta -> emit(session.id, ChatEvent.StreamingText(event.delta))
                    is StreamEvent.ToolCallDelta -> emit(session.id, ChatEvent.ToolCallStreaming(event.index, event.functionName, event.argumentsDelta))
                    is StreamEvent.Usage -> emit(session.id, ChatEvent.UsageUpdated(event.usage))
                    is StreamEvent.Retrying -> emit(session.id, ChatEvent.Retrying(event.attempt, event.delayMs))
                    is StreamEvent.Error -> { failed = true; emit(session.id, ChatEvent.Error(event.message)) }
                    else -> Unit
                }
            } } catch (error: CancellationException) {
                // Keep partial text in this session's model history; incomplete tool calls never run.
                val partial = accumulator.buildMessage().copy(toolCalls = null)
                if (!partial.content.isNullOrBlank()) {
                    session.messages.add(partial)
                    withContext(NonCancellable) { emit(session.id, ChatEvent.MessageAdded(partial)) }
                }
                throw error
            }
            if (failed) return
            val assistant = accumulator.buildMessage()
            val calls = assistant.toolCalls.orEmpty()
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
            else withContext(Dispatchers.IO) { toolExecutor.executeTool(call.function.name, args) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed = true
            "Error executing tool '${call.function.name}': ${error.message}"
        }
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
    data class Retrying(val attempt: Int, val delayMs: Long) : ChatEvent()

    /** 新消息已加入历史（用户消息或完成的 assistant 消息）。 */
    data class MessageAdded(val message: ApiChatMessage) : ChatEvent()

    /** 一轮 AI 请求开始。[iteration] 表示当前是第几轮（从 1 开始）。 */
    data class RequestStarted(val iteration: Int) : ChatEvent()

    /** 流式文本增量。UI 应将 [delta] 追加到当前 assistant 消息末尾。 */
    data class StreamingText(val delta: String) : ChatEvent()

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
    data class Error(val message: String) : ChatEvent()
}
