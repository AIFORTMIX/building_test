package com.kernelai.app.ui.screen.chat

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
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
val ChatFollowsLatest = SemanticsPropertyKey<Boolean>("ChatFollowsLatest")

/**
 * AI 聊天界面 - 支持消息气泡、Markdown 渲染、工具调用指示器、连接状态
 */
@Composable
fun ChatScreen(
    connectionStatus: ConnectionStatus,
    aiConfig: com.kernelai.app.data.model.AiConfig = com.kernelai.app.data.model.AiConfig(),
    viewModel: ChatViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val savedLists = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = { focusManager.clearFocus(force = true); keyboard?.hide(); Unit }
    LaunchedEffect(aiConfig) { viewModel.updateAiConfig(aiConfig) }
    // Release saved scroll state for deleted conversations.
    var previousIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(sessions) {
        val current = sessions.map { it.id }.toSet()
        (previousIds - current).forEach { savedLists.removeState(it) }
        previousIds = current
    }
    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        ConnectionStatusBar(connectionStatus)
        ConversationBar(sessions, state.sessionId,
            { dismissKeyboard(); viewModel.switchSession(it) },
            { dismissKeyboard(); viewModel.newSession() },
            { dismissKeyboard(); viewModel.clearChat() })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.sessionId.isNotEmpty()) savedLists.SaveableStateProvider(state.sessionId) {
                ConversationMessages(state, viewModel::loadOlderMessages)
            }
        }
        ChatInputBar(state.inputText, viewModel::updateInputText, { dismissKeyboard(); viewModel.sendMessage() },
            viewModel::stopStreaming, state.isStreaming, true)
    }
}

@Composable
private fun ConversationBar(sessions: List<AiChatManager.SessionInfo>, activeId: String,
    onSelect: (String) -> Unit, onNew: () -> Unit, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }) {
                Text(sessions.firstOrNull { it.id == activeId }?.title ?: "新会话", maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                sessions.forEach { session -> DropdownMenuItem(
                    text = { Text((if (session.id == activeId) "✓ " else "") + session.title, maxLines = 1) },
                    onClick = { expanded = false; onSelect(session.id) }) }
            }
        }
        TextButton(onClick = onNew) { Text("新对话") }
        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.Delete, "删除当前会话") }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("删除当前会话？") }, text = { Text("当前会话的消息和草稿将被删除。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun ConversationMessages(state: ChatUiState, onLoadOlder: () -> Unit) {
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
            CircularProgressIndicator(Modifier.size(24.dp))
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
            val extra = it.offset + it.size - listState.layoutInfo.viewportEndOffset
            if (extra > 0) listState.scrollBy(extra.toFloat())
        }
    }
    LaunchedEffect(listState) {
        // Observe the events directly: a short drag can begin and end between two
        // compositions, so observing only collectIsDraggedAsState can miss it.
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) following = false }
    }
    LaunchedEffect(listState, following) {
        snapshotFlow {
            val tail = latest.messages.lastOrNull()
            val layout = listState.layoutInfo
            listOf(tail?.id, tail?.content?.length, tail?.toolCalls, layout.totalItemsCount,
                layout.visibleItemsInfo.lastOrNull()?.size, layout.viewportEndOffset)
        }
            .conflate().collect {
                if (following && !latestDragged && !listState.isScrollInProgress) scrollToBottom()
                delay(80)
            }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("chat-messages").semantics {
            val index = listState.firstVisibleItemIndex - if (state.hasOlderMessages) 1 else 0
            this[ChatAnchorMessage] = visibleMessages.getOrNull(index)?.id ?: "load-older"
            this[ChatAnchorOffset] = listState.firstVisibleItemScrollOffset
            this[ChatIsScrolling] = listState.isScrollInProgress
            this[ChatFollowsLatest] = following
        }, state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasOlderMessages) item(key = "load-older") {
                TextButton(onClick = { following = false; onLoadOlder() }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
            }
            if (state.messages.isEmpty()) item(key = "empty") { EmptyChatPlaceholder() }
            items(visibleMessages, key = { it.id }, contentType = { it.role }) { message -> ChatMessageBubble(message) }
        }
        if (!following && state.messages.isNotEmpty()) TextButton(
            onClick = { following = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) { Text("回到最新") }
    }
}

/**
 * 连接状态指示器
 */
@Composable
fun ConnectionStatusBar(status: ConnectionStatus) {
    val uiMode = LocalUiMode.current
    val bgColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant
        UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(
                label = "驱动",
                connected = status.driverConnected
            )
            StatusIndicator(
                label = "MCP",
                connected = status.mcpServerRunning
            )
            StatusIndicator(
                label = "AI",
                connected = status.aiConnected
            )
        }
    }
}

@Composable
fun StatusIndicator(label: String, connected: Boolean) {
    val color = if (connected) Color(0xFF4CAF50) else Color(0xFFF44336)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Circle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$label ${if (connected) "已连接" else "未连接"}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
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
        isUser -> when (uiMode) {
            UiMode.Miuix -> MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
            UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
        }
        isTool -> when (uiMode) {
            UiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer
        }
        else -> when (uiMode) {
            UiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant
            UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
        }
    }

    val contentColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.onSurface
        UiMode.Material -> when {
            isUser -> androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
            isTool -> androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer
            else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
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
                if (!isUser) {
                    ExecutionStatus(message, contentColor)
                    message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { calls.forEach { ToolExecutionCard(it, contentColor) } }
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
    isEnabled: Boolean
) {
    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
        UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.surface
    }
    val inputBgColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.onSurface
        UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    }
    val hintColor = when (uiMode) {
        UiMode.Miuix -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        UiMode.Material -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = surfaceColor,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 输入框
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = inputBgColor,
                modifier = Modifier.weight(1f)
            ) {
                if (text.isEmpty()) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "输入消息...",
                            color = hintColor,
                            fontSize = 14.sp
                        )
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat-input")
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(textColor),
                    maxLines = 4,
                    enabled = isEnabled
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 发送/停止按钮
            if (isStreaming) {
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "停止",
                        tint = Color(0xFFF44336)
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank() && isEnabled
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "发送",
                        tint = if (text.isNotBlank()) Color(0xFF2196F3) else Color.Gray
                    )
                }
            }
        }
    }
}
