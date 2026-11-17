package com.kernelai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ai.AiChatManager
import com.kernelai.app.ai.ChatEvent
import com.kernelai.app.ai.OpenAiClient
import com.kernelai.app.ai.AiConfig as ClientConfig
import com.kernelai.app.data.model.*
import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val sessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val hasOlderMessages: Boolean = false
)

class ChatViewModel(
    private val tools: McpToolExecutor = KernelAiApplication.instance.aiToolExecutor,
    private val connectionChanged: (Boolean) -> Unit = KernelAiApplication.instance::setAiConnected
) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()
    private var config = AiConfig()
    private var manager: AiChatManager? = null
    private var collector: Job? = null
    private data class SessionUi(
        var messages: List<ChatMessage> = emptyList(),
        var input: String = "",
        var assistantId: String? = null,
        var streaming: Boolean = false,
        var visibleCount: Int = 80
    )
    private val cache = mutableMapOf<String, SessionUi>()
    private var activeId = ""
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private val _sessions = MutableStateFlow<List<AiChatManager.SessionInfo>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private var sessionCollector: Job? = null
    init { ensureManager() }
    private fun clientConfig() = ClientConfig(config.baseUrl, config.apiKey, config.model, config.maxTokens, config.temperature.toFloat())
    private fun ensureManager(): AiChatManager {
        manager?.let { return it }
        val next = AiChatManager(OpenAiClient(clientConfig()), tools, clientConfig())
        manager = next
        collector = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            next.sessionEvents.collect { event ->
                cache[event.sessionId]?.let { state ->
                    handleEvent(state, event.event)
                    if (activeId == event.sessionId) publish()
                }
            }
        }
        sessionCollector = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            next.sessionsFlow.collect { _sessions.value = it }
        }
        activeId = next.createSession()
        cache[activeId] = SessionUi()
        publish()
        return next
    }
    private fun publish() {
        val state = cache[activeId] ?: return
        _messages.value = state.messages
        _inputText.value = state.input
        _isStreaming.value = state.streaming
        _uiState.value = ChatUiState(activeId, state.messages.takeLast(state.visibleCount), state.input,
            state.streaming, state.messages.size > state.visibleCount)
    }
    fun updateAiConfig(value: AiConfig) {
        if (config == value) return
        stopStreaming()
        config = value
        manager?.updateConfig(clientConfig())
        connectionChanged(false)
    }
    fun updateInputText(text: String) { cache[activeId]?.input = text; publish() }
    fun sendMessage() {
        val state = cache[activeId] ?: return
        val text = state.input.trim()
        if (text.isEmpty() || state.streaming) return
        state.input = ""
        state.streaming = true
        publish()
        ensureManager().sendMessage(text, activeId)
    }
    fun stopStreaming() {
        manager?.cancelGeneration()
        cache[activeId]?.let { finishAssistant(it); it.streaming = false }
        publish()
    }
    fun newSession() {
        stopStreaming()
        activeId = ensureManager().createSession()
        cache[activeId] = SessionUi()
        publish()
    }
    fun switchSession(id: String) {
        if (id == activeId || id !in cache) return
        stopStreaming()
        if (ensureManager().switchSession(id)) { activeId = id; publish() }
    }
    fun deleteSession(id: String) {
        if (id !in cache) return
        if (activeId == id) stopStreaming()
        manager?.deleteSession(id)
        cache.remove(id)
        if (activeId == id) {
            val next = cache.keys.firstOrNull()
            if (next == null) { newSession(); return }
            activeId = next
            manager?.switchSession(next)
        }
        publish()
    }
    fun loadOlderMessages() { cache[activeId]?.let { it.visibleCount += 80 }; publish() }
    fun clearChat() {
        // Deleting the old session also prevents late cancellation events from reintroducing history.
        deleteSession(activeId)
    }
    private fun append(state: SessionUi, message: ChatMessage) { state.messages = state.messages + message }
    private fun update(state: SessionUi, id: String?, transform: (ChatMessage) -> ChatMessage) {
        if (id != null) state.messages = state.messages.map { if (it.id == id) transform(it) else it }
    }
    private fun finishAssistant(state: SessionUi, phase: AssistantPhase? = null) {
        update(state, state.assistantId) { it.copy(isStreaming = false, phase = phase ?: it.phase) }
    }
    private fun handleEvent(state: SessionUi, event: ChatEvent) {
        when (event) {
            is ChatEvent.MessageAdded -> when (event.message.role) {
                "user" -> append(state, ChatMessage(role = MessageRole.USER, content = event.message.content.orEmpty()))
                "assistant" -> {
                    connectionChanged(true)
                    if (state.assistantId == null) {
                        state.assistantId = UUID.randomUUID().toString()
                        append(state, ChatMessage(id = state.assistantId!!, role = MessageRole.ASSISTANT, content = ""))
                    }
                    update(state, state.assistantId) { it.copy(content = event.message.content.orEmpty(),
                        toolCalls = event.message.toolCalls?.map { c -> ToolCallInfo(c.id, c.function.name, c.function.arguments) }) }
                }
            }
            is ChatEvent.RequestStarted -> {
                finishAssistant(state)
                state.assistantId = UUID.randomUUID().toString()
                append(state, ChatMessage(id = state.assistantId!!, role = MessageRole.ASSISTANT, content = "", isStreaming = true,
                    phase = if (event.iteration > 1) AssistantPhase.SUMMARIZING else AssistantPhase.THINKING))
                state.streaming = true
            }
            is ChatEvent.StreamingText -> {
                connectionChanged(true)
                update(state, state.assistantId) { it.copy(content = it.content + event.delta,
                    phase = if (it.phase == AssistantPhase.SUMMARIZING) it.phase else AssistantPhase.RESPONDING) }
            }
            is ChatEvent.ToolCallStreaming -> update(state, state.assistantId) { message ->
                val calls = message.toolCalls.orEmpty().toMutableList()
                val id = "pending_${event.index}"
                val position = calls.indexOfFirst { it.id == id }
                val previous = calls.getOrNull(position) ?: ToolCallInfo(id, "", "")
                val call = previous.copy(name = previous.name + event.name.orEmpty(), arguments = previous.arguments + event.argumentsDelta.orEmpty())
                if (position < 0) calls.add(call) else calls[position] = call
                message.copy(phase = AssistantPhase.PREPARING_TOOL, toolCalls = calls)
            }
            is ChatEvent.ToolCallsStarting -> update(state, state.assistantId) { it.copy(phase = AssistantPhase.PREPARING_TOOL, toolCalls =
                event.toolCalls.map { c -> ToolCallInfo(c.id, c.function.name, c.function.arguments) }) }
            is ChatEvent.ToolCallExecuting -> update(state, state.assistantId) { message -> message.copy(phase = AssistantPhase.EXECUTING_TOOL,
                toolCalls = message.toolCalls?.map { if (it.id == event.toolCallId) it.copy(status = ToolCallStatus.RUNNING,
                    startedAtMillis = event.startedAtMillis) else it }) }
            is ChatEvent.ToolCallResult -> {
                val status = when { event.cancelled -> ToolCallStatus.CANCELLED; event.isError -> ToolCallStatus.FAILED; else -> ToolCallStatus.COMPLETED }
                state.messages = state.messages.map { message -> message.copy(toolCalls = message.toolCalls?.map {
                    if (it.id == event.toolCallId) it.copy(result = event.result, status = status, durationMs = event.durationMs) else it
                }) }
                append(state, ChatMessage(role = MessageRole.TOOL, content = event.result, toolCalls = listOf(
                    ToolCallInfo(event.toolCallId, event.toolName, "", event.result, status, durationMs = event.durationMs))))
            }
            ChatEvent.ContinuingWithToolResults -> finishAssistant(state, AssistantPhase.COMPLETE)
            is ChatEvent.Error -> {
                connectionChanged(false)
                if (state.assistantId == null) append(state, ChatMessage(role = MessageRole.ASSISTANT, content = event.message))
                else update(state, state.assistantId) { it.copy(content = listOf(it.content, event.message).filter(String::isNotBlank).joinToString("\n"),
                    isStreaming = false, phase = AssistantPhase.FAILED) }
                state.streaming = false
            }
            ChatEvent.GenerationComplete, ChatEvent.GenerationCancelled -> {
                finishAssistant(state, if (event == ChatEvent.GenerationComplete) AssistantPhase.COMPLETE else AssistantPhase.CANCELLED)
                state.messages = state.messages.map { message -> message.copy(toolCalls = message.toolCalls?.map {
                    if (it.status == ToolCallStatus.PENDING || it.status == ToolCallStatus.RUNNING) it.copy(status = ToolCallStatus.CANCELLED) else it
                }) }
                state.streaming = false
            }
            ChatEvent.SessionCleared -> { state.assistantId = null; state.messages = emptyList() }
        }
    }
    override fun onCleared() { collector?.cancel(); sessionCollector?.cancel(); manager?.destroy(); super.onCleared() }
}
