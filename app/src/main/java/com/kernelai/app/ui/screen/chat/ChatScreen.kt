package com.kernelai.app.ui.screen.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Forum
import com.kernelai.app.data.ProviderCatalog
import com.kernelai.app.data.AttachmentStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Stop
import com.kernelai.app.ui.components.AlertDialog
import com.kernelai.app.ui.components.DropdownMenu
import com.kernelai.app.ui.components.DropdownMenuItem
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.components.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kernelai.app.data.model.ChatMessage
import com.kernelai.app.data.model.ConnectionStatus
import com.kernelai.app.data.model.MessageRole
import com.kernelai.app.data.model.ToolCallInfo
import com.kernelai.app.data.model.ToolCallStatus
import com.kernelai.app.ui.LocalUiMode
import com.kernelai.app.ui.UiMode
import com.kernelai.app.ui.viewmodel.ChatViewModel
import com.kernelai.app.ui.viewmodel.ChatUiState
import com.kernelai.app.ai.AiChatManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import top.yukonga.miuix.kmp.theme.MiuixTheme

val ChatAnchorMessage = SemanticsPropertyKey<String>("ChatAnchorMessage")
val ChatAnchorOffset = SemanticsPropertyKey<Int>("ChatAnchorOffset")
val ChatIsScrolling = SemanticsPropertyKey<Boolean>("ChatIsScrolling")
val ChatAtLatest = SemanticsPropertyKey<Boolean>("ChatAtLatest")
val ChatFollowsLatest = SemanticsPropertyKey<Boolean>("ChatFollowsLatest")

/**
 * AI 聊天界面 - 支持消息气泡、Markdown 渲染、工具调用指示器、连接状态
 */
@Composable
fun ChatScreen(
    connectionStatus: ConnectionStatus,
    aiConfig: com.kernelai.app.data.model.AiConfig = com.kernelai.app.data.model.AiConfig(),
    viewModel: ChatViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modelCatalog: ProviderCatalog = ProviderCatalog(),
    requireModel: Boolean = false,
    onManageModels: () -> Unit = {},
    onOpenPage: (Int) -> Unit = {},
    onOpenUtility: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val savedLists = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = { focusManager.clearFocus(force = true); keyboard?.hide(); Unit }
    LaunchedEffect(aiConfig, modelCatalog) {
        if (modelCatalog.activeConfig() == null) viewModel.updateAiConfig(aiConfig)
        viewModel.updateCatalog(modelCatalog, requireModel)
    }
    var showSessions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val attachmentStore = remember(context) { AttachmentStore(context) }
    var importingSession by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            try {
                require(uris.size <= 4) { "每条消息最多 4 个附件" }
                val values = uris.map { attachmentStore.import(it) }
                viewModel.addAttachments(values, importingSession)
            } catch (error: Exception) { if (error is CancellationException) throw error; viewModel.reportError("附件添加失败：${error.message}") }
            finally { importing = false }
        }
    }
    if (showSessions) ConversationList(sessions, state.sessionId, { showSessions = false },
        { dismissKeyboard(); viewModel.switchSession(it) }, { dismissKeyboard(); viewModel.newSession() }, viewModel::renameSession, viewModel::deleteSession)
    if (showModels) ModelPicker(modelCatalog, { showModels = false }, viewModel::selectModel, onManageModels)
    // Release saved scroll state for deleted conversations.
    var previousIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(sessions) {
        val current = sessions.map { it.id }.toSet()
        (previousIds - current).forEach { savedLists.removeState(it) }
        previousIds = current
    }
    val background = chatBackground()
    val blurEnabled = glassSupported()
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(116.dp) }
    Column(Modifier.fillMaxSize().background(background).padding(contentPadding).imePadding()) {
        ConversationBar(sessions, state.sessionId,
            { dismissKeyboard(); viewModel.switchSession(it) },
            { dismissKeyboard(); viewModel.newSession() },
            { dismissKeyboard(); viewModel.clearChat() }, onOpenPage, onOpenUtility, { showSessions = true })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxHeight < 260.dp
            val extrasLimit = (maxHeight - if (compact) 150.dp else 230.dp).coerceAtLeast(0.dp)
            Box(Modifier.fillMaxSize().then(if (blurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)) {
                if (state.sessionId.isNotEmpty()) savedLists.SaveableStateProvider(state.sessionId) {
                    ConversationMessages(state, viewModel::loadOlderMessages, composerHeight)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                .padding(horizontal = 14.dp).padding(top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = extrasLimit).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.loading) Text("正在恢复本地对话…", color = chatForeground(), fontSize = 12.sp)
                state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().background(chatBackground()).padding(8.dp)) }
                if (importing) Text("正在处理附件…", color = chatForeground(), fontSize = 12.sp)
                state.question?.let { question ->
                    GlassSurface(backdrop, Modifier.fillMaxWidth(), RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("AI 需要你补充信息", fontWeight = FontWeight.Bold)
                            Text(question.question, fontSize = 14.sp)
                            question.options.forEach { answer -> TextButton(onClick = { viewModel.answerQuestion(answer) }) { Text(answer, fontSize = 13.sp) } }
                            Text("也可以在输入框中回答，或中断后发送新的要求。", fontSize = 11.sp)
                        }
                    }
                }
                if (state.queued.isNotEmpty()) TextButton(onClick = { if (!state.isStreaming) viewModel.sendMessage() }) {
                    Text(if (state.isStreaming) "${state.queued.size} 条插话将在下一步处理" else "发送 ${state.queued.size} 条待处理插话", fontSize = 12.sp)
                }
                if (state.isStreaming && state.inputText.isNotBlank()) TextButton(onClick = viewModel::interruptAndSend) { Text("中断并发送", fontSize = 12.sp) }
                if (state.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.attachments.forEach { attachment -> Box(Modifier.width(260.dp)) { AttachmentTile(attachment, { viewModel.removeAttachment(attachment.id) }) } }
                }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { dismissKeyboard(); showModels = true }, modifier = Modifier.weight(1f)) { Text(state.modelName.ifBlank { "选择模型" } + " ⌄", maxLines = 1, fontSize = 12.sp, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    val usage = state.usage
                    Text(if (usage == null) "上下文 0 / ${state.contextWindow}" else
                        "${if (usage.estimated) "估算" else "上次实测"} ${usage.promptTokens} / ${state.contextWindow}",
                        color = chatForeground().copy(alpha = .55f), fontSize = 10.sp)
                }
                ChatInputBar(state.inputText, viewModel::updateInputText, { dismissKeyboard(); viewModel.sendMessage() },
                    viewModel::stopStreaming, state.isStreaming, !state.loading && !importing && (!requireModel || modelCatalog.providers.any { it.models.isNotEmpty() }), backdrop,
                    { showAttachMenu = true }, state.attachments.isNotEmpty() || state.queued.isNotEmpty(), state.question != null, compact)
                DropdownMenu(showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                    DropdownMenuItem(text = { Text("添加图片") }, onClick = { showAttachMenu = false; importingSession = state.sessionId; picker.launch(arrayOf("image/*")) })
                    DropdownMenuItem(text = { Text("添加文本文件") }, onClick = { showAttachMenu = false; importingSession = state.sessionId; picker.launch(arrayOf("*/*")) })
                }
                if (!compact) ConnectionStatusBar(connectionStatus)
            }
        }
    }
}

@Composable
private fun ConversationBar(sessions: List<AiChatManager.SessionInfo>, activeId: String,
    onSelect: (String) -> Unit, onNew: () -> Unit, onClear: () -> Unit,
    onOpenPage: (Int) -> Unit, onOpenUtility: (String) -> Unit, onSessions: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var navigationExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { navigationExpanded = true }, modifier = Modifier.background(chatForeground().copy(alpha = .05f), CircleShape)) {
                Icon(Icons.Rounded.Menu, "打开导航", tint = chatForeground())
            }
            DropdownMenu(navigationExpanded, onDismissRequest = { navigationExpanded = false }) {
                listOf("进程", "断点", "设置").forEachIndexed { index, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { navigationExpanded = false; onOpenPage(index + 1) })
                }
                listOf("内存查看", "反汇编", "日志").forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { navigationExpanded = false; onOpenUtility(name) })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }) {
                Text(sessions.firstOrNull { it.id == activeId }?.title ?: "新会话", maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = chatForeground())
                Icon(Icons.Rounded.ExpandMore, null, tint = chatForeground().copy(alpha = .45f), modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                sessions.forEach { session -> DropdownMenuItem(
                    text = { Text((if (session.id == activeId) "✓ " else "") + session.title, maxLines = 1) },
                    onClick = { expanded = false; onSelect(session.id) }) }
            }
        }
        IconButton(onClick = onSessions) { Icon(Icons.Rounded.Forum, "对话列表", tint = chatForeground()) }
        TextButton(onClick = onNew) { Text("新对话") }
        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.Delete, "删除当前会话") }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("删除当前会话？") }, text = { Text("当前会话的消息和草稿将被删除。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun ConversationMessages(state: ChatUiState, onLoadOlder: () -> Unit, bottomPadding: androidx.compose.ui.unit.Dp) {
    val visibleMessages = remember(state.messages) { state.messages.filterNot { it.role == MessageRole.TOOL } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex =
        (visibleMessages.lastIndex + if (state.hasOlderMessages) 1 else 0).coerceAtLeast(0))
    var following by rememberSaveable { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    // Resolve the restored viewport before laying it out. Raw Markdown has a different
    // height and must not replace a saved long-message anchor for one frame.
    var restoredContentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val anchor = (listState.firstVisibleItemIndex - if (state.hasOlderMessages) 1 else 0).coerceAtLeast(0)
        val viewport = visibleMessages.drop(anchor).take(2)
        withContext(Dispatchers.Default) {
            viewport.filter { it.role == MessageRole.ASSISTANT && !it.isStreaming }.forEach { MarkdownDocument.parse(it.content) }
        }
        restoredContentReady = true
    }
    if (!restoredContentReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), size = 24.dp)
        }
        return
    }
    val latest by rememberUpdatedState(state)
    val latestDragged by rememberUpdatedState(dragged)
    suspend fun scrollToBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        listState.scrollToItem(last)
        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
            val extra = it.offset + it.size - (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding)
            if (extra > 0) listState.scrollBy(extra.toFloat())
        }
    }
    LaunchedEffect(listState) {
        // Observe the events directly: a short drag can begin and end between two
        // compositions, so observing only collectIsDraggedAsState can miss it.
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    LaunchedEffect(listState, following) {
        var previousTail: Pair<String?, Int>? = null
        var wasGenerating = false
        var settleUntil = 0L
        snapshotFlow {
            val tail = latest.messages.lastOrNull()
            val layout = listState.layoutInfo
            listOf(tail?.id, tail?.content?.length, tail?.toolCalls, layout.totalItemsCount,
                layout.visibleItemsInfo.lastOrNull()?.size, layout.viewportEndOffset, layout.afterContentPadding, latest.isStreaming)
        }
            .conflate().collect {
                // layoutInfo can emit inside a measure pass. Suspend before scrolling so
                // scrollToItem cannot force a nested measure while that pass is active.
                delay(80)
                val tail = latest.messages.lastOrNull()
                val signature = tail?.id to (tail?.content?.length ?: 0)
                if (signature != previousTail || (wasGenerating && !latest.isStreaming)) settleUntil = System.nanoTime() + 600_000_000L
                previousTail = signature; wasGenerating = latest.isStreaming
                if (following && (latest.isStreaming || System.nanoTime() < settleUntil) && !latestDragged && !listState.isScrollInProgress) scrollToBottom()
            }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("chat-messages").semantics {
            val index = listState.firstVisibleItemIndex - if (state.hasOlderMessages) 1 else 0
            this[ChatAnchorMessage] = visibleMessages.getOrNull(index)?.id ?: "load-older"
            this[ChatAnchorOffset] = listState.firstVisibleItemScrollOffset
            this[ChatIsScrolling] = listState.isScrollInProgress
            this[ChatFollowsLatest] = following
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            this[ChatAtLatest] = last != null && last.index == layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset - layout.afterContentPadding + 4
        }, state = listState,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasOlderMessages) item(key = "load-older") {
                TextButton(onClick = { following = false; onLoadOlder() }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
            }
            if (state.messages.isEmpty()) item(key = "empty") { EmptyChatPlaceholder() }
            items(visibleMessages, key = { it.id }, contentType = { it.role }) { message -> ChatMessageBubble(message) }
        }
        if (!following && state.messages.isNotEmpty()) TextButton(
            onClick = { following = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = bottomPadding + 8.dp)
                .background(chatBackground().copy(alpha = .96f), CircleShape)
        ) { Text("回到最新") }
    }
}

/**
 * 连接状态指示器
 */
@Composable
fun ConnectionStatusBar(status: ConnectionStatus) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusIndicator("驱动", status.driverConnected, Modifier.weight(1f))
        StatusIndicator("MCP", status.mcpServerRunning, Modifier.weight(1f))
        StatusIndicator("AI", status.aiConnected, Modifier.weight(1f))
    }
}

@Composable
fun StatusIndicator(label: String, connected: Boolean, modifier: Modifier = Modifier) {
    val color = if (connected) Color(0xFF387FF2) else chatForeground().copy(alpha = .50f)
    Row(modifier.background(color.copy(alpha = .09f), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Circle, null, tint = color, modifier = Modifier.size(6.dp))
        Spacer(Modifier.width(5.dp))
        Text("$label ${if (connected) "已连接" else "未连接"}", color = color, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Medium)
    }
}

/**
 * 空聊天占位符
 */
@Composable
fun EmptyChatPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "轻微 MCP",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "内核调试 AI 助手",
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "你可以问我关于进程分析、内存查看、\n断点设置等内核调试问题",
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/**
 * 聊天消息气泡
 */
@Composable
@Suppress("DEPRECATION")
fun ChatMessageBubble(message: ChatMessage) {
    val copyText = rememberChatCopy()
    val isUser = message.role == MessageRole.USER
    val isTool = message.role == MessageRole.TOOL

    val uiMode = LocalUiMode.current
    val bubbleColor = when {
        isUser -> MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
        isTool -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    val contentColor = MiuixTheme.colorScheme.onSurface
    val alignment = when {
        isUser -> Alignment.End
        else -> Alignment.Start
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // 消息内容
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bubbleColor,
            modifier = if (isUser) Modifier.widthIn(max = 360.dp) else Modifier.fillMaxWidth().widthIn(max = 840.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                message.attachments.forEach { AttachmentTile(it) }
                if (!isUser) {
                    ExecutionStatus(message, contentColor)
                    message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                        ToolExecutionGroup(calls, contentColor)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (message.content.isNotEmpty()) {
                    if (isUser || isTool) {
                        SelectionContainer { Text(message.content, color = contentColor, fontSize = 14.sp, lineHeight = 22.sp,
                            fontFamily = if (isTool) FontFamily.Monospace else FontFamily.Default) }
                    } else key(message.id) {
                        MarkdownMessage(message.content, message.isStreaming, contentColor)
                    }
                    if (!message.isStreaming && message.content.isNotEmpty()) {
                        TextButton(onClick = { copyText(message.content) }) {
                            Text("复制消息", color = contentColor.copy(alpha = .7f), fontSize = 11.sp)
                        }
                    }
                }

                // 流式指示器
                if (message.isStreaming && message.content.isNotEmpty()) {
                    StreamingCursor()
                }
            }
        }
    }
}

/**
 * 流式光标动画
 */
@Composable
fun StreamingCursor() {
    var visible by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 16.dp)
                .background(Color.Gray)
        )
    }
}

/**
 * 聊天输入栏
 */
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    isEnabled: Boolean,
    backdrop: LayerBackdrop? = null,
    onAddAttachment: () -> Unit = {},
    hasAttachments: Boolean = false,
    answeringQuestion: Boolean = false,
    compact: Boolean = false
) {
    val textColor = chatForeground()
    GlassSurface(backdrop, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAddAttachment, enabled = isEnabled) { Icon(Icons.Rounded.Add, "添加附件", tint = textColor.copy(alpha = .6f)) }
            BasicTextField(value = text, onValueChange = onTextChange,
                modifier = Modifier.weight(1f).testTag("chat-input").padding(vertical = 9.dp, horizontal = 4.dp),
                textStyle = TextStyle(color = textColor, fontSize = 15.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Color(0xFF387FF2)), maxLines = if (compact) 2 else 4, enabled = isEnabled,
                decorationBox = { inner -> Box {
                    if (text.isEmpty()) Text("问问 AI，分析设备与内存…", color = textColor.copy(alpha = .45f), fontSize = 15.sp)
                    inner()
                } })
            Spacer(Modifier.width(6.dp))
            val stopButton = isStreaming && text.isBlank() && !hasAttachments
            val active = isStreaming || ((text.isNotBlank() || hasAttachments) && isEnabled)
            IconButton(onClick = if (stopButton) onStop else onSend, enabled = active,
                modifier = Modifier.size(44.dp).background(if (active) textColor else textColor.copy(alpha = .06f), CircleShape)) {
                Icon(if (stopButton) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    if (stopButton) "停止" else if (answeringQuestion) "发送回答" else if (isStreaming) "插话" else "发送", tint = if (active) chatBackground() else textColor.copy(alpha = .28f), modifier = Modifier.size(21.dp))
            }
        }
    }
}
