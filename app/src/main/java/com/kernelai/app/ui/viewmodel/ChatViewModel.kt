package com.kernelai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ai.AiChatManager
import com.kernelai.app.ai.ChatEvent
import com.kernelai.app.ai.OpenAiClient
import com.kernelai.app.ai.AiConfig as ClientConfig
import com.kernelai.app.data.model.*
import com.kernelai.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
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
    val hasOlderMessages: Boolean = false,
    val loading: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val usage: ContextUsage? = null,
    val error: String? = null,
    val modelName: String = "",
    val contextWindow: Int = 32768,
    val question: UserQuestion? = null, val queued: List<ApiChatMessage> = emptyList(), val followLatestRequest: Long = 0,
    val usageTotals: UsageTotals = UsageTotals(), val estimatedUsage: ContextUsage? = null,
    val workspaceId: String = "default", val canRetry: Boolean = false, val context: ContextBreakdown = ContextBreakdown()
)

class ChatViewModel(
    private val tools: McpToolExecutor = KernelAiApplication.instance.aiToolExecutor,
    private val storage: ChatStore? = runCatching { KernelAiApplication.instance.chatStore }.getOrNull(),
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
        var visibleCount: Int = 80,
        var attachments: List<Attachment> = emptyList(),
        var providerId: String = "", var modelId: String = "",
        var usage: ContextUsage? = null,
        var question: UserQuestion? = null, var queued: List<ApiChatMessage> = emptyList(), var followLatestRequest: Long = 0,
        var usageTotals: UsageTotals = UsageTotals(), var estimatedUsage: ContextUsage? = null, var workspaceId: String = "default"
    )
    private val cache = mutableMapOf<String, SessionUi>()
    private var activeId = ""
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private val _sessions = MutableStateFlow<List<AiChatManager.SessionInfo>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private var sessionCollector: Job? = null
    private var loading = storage != null
    private var storageHealthy = true
    private var error: String? = null
    private var catalog = ProviderCatalog()
    private var saveJob: Job? = null
    private var saveDirty = false
    private var requireCatalog = false
    private val definitions by lazy { tools.getToolDefinitions() + com.kernelai.app.ai.InteractiveTools.askUser }
    init {
        ensureManager()
        if (storage != null) viewModelScope.launch {
            try {
                val archive = storage.load()
                if (archive.sessions.isNotEmpty()) {
                    manager?.deleteSession(activeId); cache.clear()
                    archive.sessions.forEach { saved ->
                        val partial = saved.messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.isStreaming && it.phase != AssistantPhase.THINKING }?.content
                        manager?.restoreSession(saved.id, saved.title, saved.createdAt, saved.history, partial)
                        manager?.restoreQueued(saved.id, saved.queued)
                        cache[saved.id] = SessionUi(messages = saved.messages.map { message ->
                            message.copy(isStreaming = false, retry = null, phase = if (message.isStreaming) AssistantPhase.CANCELLED else message.phase,
                                toolCalls = message.toolCalls?.map { call -> if (call.status in listOf(ToolCallStatus.RUNNING, ToolCallStatus.PENDING)) call.copy(status = ToolCallStatus.CANCELLED) else call })
                        }, input = saved.draft, attachments = saved.attachments, providerId = saved.providerId, modelId = saved.modelId, usage = saved.usage, queued = saved.queued,
                            usageTotals = saved.usageTotals, workspaceId = saved.workspaceId)
                    }
                    activeId = archive.activeId.takeIf { it in cache } ?: cache.keys.first()
                    manager?.switchSession(activeId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                storageHealthy = false; error = "本地会话读取失败，原文件保留：${e.message}"
            } finally { loading = false; applySessionConfig(); publish() }
        }
    }
    private fun clientConfig() = ClientConfig(config.baseUrl, config.apiKey, config.model, config.maxTokens, config.temperature.toFloat(),
        contextWindow = config.contextWindow, supportsImages = config.supportsImages, maxRetries = config.maxRetries, streamUsage = config.streamUsage)
    private fun ensureManager(): AiChatManager {
        manager?.let { return it }
        val next = AiChatManager(OpenAiClient(clientConfig()), tools, clientConfig(), workspaceScope = if (storage == null) null else { session -> cache[session]?.workspaceId })
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
        val provider = catalog.providers.firstOrNull { it.id == state.providerId }
        val label = provider?.models?.firstOrNull { it.id == state.modelId }?.name ?: config.model
        val history = manager?.requestHistory(activeId).orEmpty().toMutableList()
        val partial = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        if (state.streaming && partial != null && (partial.content.isNotEmpty() || partial.reasoning.isNotEmpty())) history.add(ApiChatMessage("assistant", partial.content, reasoningContent = partial.reasoning))
        if (state.input.isNotBlank() || state.attachments.isNotEmpty()) history.add(ApiChatMessage("user", state.input, attachments = state.attachments))
        val context = com.kernelai.app.ai.ContextBudget.breakdown(history, definitions, config.maxTokens)
        _uiState.value = ChatUiState(activeId, state.messages.takeLast(state.visibleCount), state.input,
            state.streaming, state.messages.size > state.visibleCount, loading, state.attachments, state.usage, error, label, config.contextWindow, state.question, state.queued, state.followLatestRequest,
            state.usageTotals, state.estimatedUsage, state.workspaceId, !state.streaming && state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.phase == AssistantPhase.FAILED, context)
        if (storage != null) {
            val assistant = state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            val phase = when {
                state.question != null -> "等待你的回答"
                assistant?.phase == AssistantPhase.EXECUTING_TOOL -> assistant.toolCalls?.firstOrNull { it.status == ToolCallStatus.RUNNING }?.let { com.kernelai.app.ui.screen.chat.toolPurpose(it) } ?: "正在执行工具"
                assistant?.phase == AssistantPhase.PREPARING_TOOL -> "正在准备工具调用"
                assistant?.phase == AssistantPhase.THINKING -> "正在思考或等待重试"
                state.streaming -> "正在生成回复"
                assistant?.phase == AssistantPhase.FAILED -> "查看对话中的错误详情"
                else -> "点按返回对话"
            }
            val title = manager?.sessionsFlow?.value?.firstOrNull { it.id == activeId }?.title ?: "轻微 AI"
            runCatching { KernelAiApplication.instance.reportAgentTask(com.kernelai.app.ai.AgentTask(state.streaming, title, phase,
                state.question != null, when (assistant?.phase) { AssistantPhase.FAILED -> "failed"; AssistantPhase.CANCELLED -> "cancelled"; else -> "done" })) }
                .onFailure { error = "后台任务服务未能启动：${it.message}"; _uiState.value = _uiState.value.copy(error = error) }
        }
        scheduleSave()
    }
    fun updateCatalog(value: ProviderCatalog, managed: Boolean = false) { requireCatalog = managed; catalog = value; applySessionConfig() }
    fun selectModel(providerId: String, modelId: String) {
        cache[activeId]?.let { it.providerId = providerId; it.modelId = modelId }
        applySessionConfig(); publish()
    }
    fun selectWorkspace(id: String) {
        val state = cache[activeId] ?: return
        if (state.streaming) return
        state.workspaceId = id; publish()
    }
    private fun applySessionConfig() {
        val state = cache[activeId] ?: return
        val selected = catalog.copy(selectedProvider = state.providerId, selectedModel = state.modelId).activeConfig()
            ?: catalog.activeConfig() ?: return
        state.providerId = selected.providerId; state.modelId = selected.model
        updateAiConfig(selected)
    }
    fun renameSession(id: String, title: String) { manager?.renameSession(id, title); publish() }
    fun addAttachments(values: List<Attachment>, sessionId: String = activeId) {
        val state = cache[sessionId] ?: return
        if (state.attachments.size + values.size > 4) { reportError("每条消息最多 4 个附件"); return }
        state.attachments += values; publish()
    }
    fun removeAttachment(id: String) { cache[activeId]?.let { it.attachments = it.attachments.filterNot { a -> a.id == id } }; publish() }
    fun reportError(value: String?) { error = value; publish() }
    private fun snapshot(): ChatArchive {
        val info = manager?.sessionsFlow?.value.orEmpty().associateBy { it.id }
        return ChatArchive(activeId = activeId, sessions = cache.map { (id, value) ->
            SavedChat(id, info[id]?.title ?: "新会话", info[id]?.createdAt ?: System.currentTimeMillis(), manager?.getMessages(id).orEmpty(),
                value.messages, value.input, value.attachments, value.providerId, value.modelId, value.usage, value.queued, value.usageTotals, value.workspaceId)
        })
    }
    private fun scheduleSave() {
        if (loading || storage == null || !storageHealthy) return
        saveDirty = true
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            while (saveDirty) {
                delay(300)
                saveDirty = false
                val archive = snapshot()
                try { storage.save(archive) }
                catch (e: Exception) { if (e is CancellationException) throw e; error = "会话保存失败：${e.message}"; _uiState.value = _uiState.value.copy(error = error) }
            }
        }
    }

    fun updateAiConfig(value: AiConfig) {
        if (config == value) return
        stopStreaming()
        config = value
        manager?.updateConfig(clientConfig())
        connectionChanged(false)
        publish()
    }
    fun updateInputText(text: String) { cache[activeId]?.input = text; publish() }
    fun retryLastRequest() {
        val state = cache[activeId] ?: return
        if (state.streaming || !_uiState.value.canRetry) return
        error = null; state.streaming = true; state.followLatestRequest++; publish()
        ensureManager().retryLastRequest(activeId)
    }
    fun sendMessage() {
        val state = cache[activeId] ?: return
        val text = state.input.trim()
        if (loading) return
        if (requireCatalog && catalog.providers.none { p -> p.id == state.providerId && p.models.any { it.id == state.modelId } }) {
            reportError("请先添加供应商并选择模型"); return
        }
        if (state.question != null) { if (ensureManager().answer(activeId, text)) { state.input = ""; state.followLatestRequest++; publish() }; return }
        if (text.isEmpty() && state.attachments.isEmpty()) {
            if (!state.streaming && state.queued.isNotEmpty()) { state.streaming = true; state.followLatestRequest++; ensureManager().resumeQueued(activeId); publish() }
            return
        }
        if (state.attachments.any { it.isImage } && !config.supportsImages) { reportError("当前模型未启用图像支持，请选择图像模型或在模型配置中启用"); return }
        val attachments = state.attachments
        state.followLatestRequest++
        state.attachments = emptyList()
        error = null
        state.input = ""
        if (state.streaming) {
            runCatching { ensureManager().steer(text, attachments, activeId) }.onFailure { state.input = text; state.attachments = attachments; error = it.message }
            publish(); return
        }
        state.streaming = true
        publish()
        ensureManager().sendMessage(text, activeId, attachments)
    }
    fun answerQuestion(text: String) { if (manager?.answer(activeId, text) == true) cache[activeId]?.let { it.question = null; it.followLatestRequest++ }; publish() }
    fun interruptAndSend() {
        val state = cache[activeId] ?: return
        val text = state.input.trim(); val attachments = state.attachments
        if (text.isBlank() && attachments.isEmpty()) return
        if (attachments.any { it.isImage } && !config.supportsImages) { reportError("当前模型未启用图像支持"); return }
        state.followLatestRequest++
        stopStreaming(); state.question = null; state.input = ""; state.attachments = emptyList(); state.streaming = true
        ensureManager().sendMessage(text, activeId, attachments); publish()
    }
    fun stopStreaming() {
        manager?.cancelGeneration()
        cache[activeId]?.let { if (it.streaming) finishAssistant(it, AssistantPhase.CANCELLED); it.streaming = false; it.question = null }
        publish()
    }
    fun newSession() {
        stopStreaming()
        activeId = ensureManager().createSession()
        cache[activeId] = SessionUi()
        applySessionConfig()
        publish()
    }
    fun switchSession(id: String) {
        if (id == activeId || id !in cache) return
        stopStreaming()
        if (ensureManager().switchSession(id)) { activeId = id; applySessionConfig(); publish() }
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
            applySessionConfig()
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
        update(state, state.assistantId) { it.copy(isStreaming = false, retry = null, phase = phase ?: it.phase) }
    }
    private fun handleEvent(state: SessionUi, event: ChatEvent) {
        when (event) {
            is ChatEvent.QuestionAsked -> state.question = event.question
            ChatEvent.QuestionClosed -> state.question = null
            is ChatEvent.QueueUpdated -> state.queued = event.messages
            is ChatEvent.UsageUpdated -> if (event.usage.estimated) state.estimatedUsage = event.usage else state.usage = event.usage
            is ChatEvent.UsageCommitted -> state.usageTotals = state.usageTotals.plus(event.usage)
            is ChatEvent.Retrying -> update(state, state.assistantId) { it.copy(retry = RetryNotice(event.attempt, event.maximum,
                System.currentTimeMillis() + event.delayMs, event.failure?.message ?: "网络暂时不可用"), phase = AssistantPhase.THINKING) }
            is ChatEvent.MessageAdded -> when (event.message.role) {
                "user" -> append(state, ChatMessage(role = MessageRole.USER, content = event.message.content.orEmpty(), attachments = event.message.attachments))
                "assistant" -> {
                    connectionChanged(true)
                    if (state.assistantId == null) {
                        state.assistantId = UUID.randomUUID().toString()
                        append(state, ChatMessage(id = state.assistantId!!, role = MessageRole.ASSISTANT, content = ""))
                    }
                    update(state, state.assistantId) { it.copy(content = event.message.content.orEmpty(), reasoning = event.message.reasoningContent.orEmpty(), retry = null,
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
                update(state, state.assistantId) { it.copy(content = it.content + event.delta, retry = null,
                    phase = if (it.phase == AssistantPhase.SUMMARIZING) it.phase else AssistantPhase.RESPONDING) }
            }
            is ChatEvent.StreamingReasoning -> {
                connectionChanged(true)
                update(state, state.assistantId) { it.copy(reasoning = it.reasoning + event.delta, retry = null,
                    phase = if (it.content.isEmpty()) AssistantPhase.THINKING else it.phase) }
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
                val failure = event.failure ?: com.kernelai.app.ai.AiFailure("client", "请求未完成", com.kernelai.app.ai.AiFailures.scrub(event.message), "检查详情后可重试本轮；已完成工具的结果保留。")
                if (state.assistantId == null) append(state, ChatMessage(role = MessageRole.ASSISTANT, content = "", failure = failure, phase = AssistantPhase.FAILED))
                else update(state, state.assistantId) { it.copy(failure = failure, retry = null, isStreaming = false, phase = AssistantPhase.FAILED,
                    toolCalls = it.toolCalls?.map { call -> if (call.status == ToolCallStatus.PENDING) call.copy(status = ToolCallStatus.CANCELLED) else call }) }
                state.streaming = false
            }
            ChatEvent.GenerationComplete, ChatEvent.GenerationCancelled -> {
                state.question = null
                finishAssistant(state, if (event == ChatEvent.GenerationComplete) AssistantPhase.COMPLETE else AssistantPhase.CANCELLED)
                state.messages = state.messages.map { message -> message.copy(toolCalls = message.toolCalls?.map {
                    if (it.status == ToolCallStatus.PENDING || it.status == ToolCallStatus.RUNNING) it.copy(status = ToolCallStatus.CANCELLED) else it
                }) }
                state.streaming = false
            }
            ChatEvent.SessionCleared -> { state.assistantId = null; state.messages = emptyList() }
        }
    }
    override fun onCleared() {
        if (storage != null && storageHealthy && !loading) {
            val archive = snapshot()
            runCatching { KernelAiApplication.instance.persistArchive(storage!!, archive) }
        }
        collector?.cancel(); sessionCollector?.cancel(); manager?.destroy(); super.onCleared() }
}
