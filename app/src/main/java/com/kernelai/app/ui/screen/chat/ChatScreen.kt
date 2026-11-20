package com.kernelai.app.ui.screen.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Folder
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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
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
    viewModel: ChatViewModel = com.kernelai.app.KernelAiApplication.instance.agentViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modelCatalog: ProviderCatalog = ProviderCatalog(),
    requireModel: Boolean = false,
    onManageModels: () -> Unit = {},
    onOpenPage: (Int) -> Unit = {},
    onOpenUtility: (String) -> Unit = {},
    glassBackdrop: LayerBackdrop? = null,
    navigationInset: androidx.compose.ui.unit.Dp = 0.dp,
    modelLoading: Boolean = false
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val savedLists = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = { focusManager.clearFocus(force = true); keyboard?.hide(); Unit }
    LaunchedEffect(aiConfig, modelCatalog, modelLoading) {
        if (requireModel && modelLoading) return@LaunchedEffect
        if (modelCatalog.activeConfig() == null) viewModel.updateAiConfig(aiConfig)
        viewModel.updateCatalog(modelCatalog, requireModel)
    }
    var showSessions by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showWorkspaces by remember { mutableStateOf(false) }
    val workspaces by com.kernelai.app.KernelAiApplication.instance.workspaceRepository.workspaces.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { runCatching { com.kernelai.app.KernelAiApplication.instance.workspaceRepository.load() }
        .onFailure { viewModel.reportError("工作区加载失败：${it.message}") } }
    var showAttachMenu by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingNotificationSend by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingNotificationSend) { pendingNotificationSend = false; dismissKeyboard(); viewModel.sendMessage() }
    }
    val sendMessage = {
        val preferences = context.getSharedPreferences("agent-notifications", android.content.Context.MODE_PRIVATE)
        val needsPermission = requireModel && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission && !preferences.getBoolean("asked", false)) {
            preferences.edit().putBoolean("asked", true).apply(); pendingNotificationSend = true
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else { dismissKeyboard(); viewModel.sendMessage() }
    }
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
    var previousIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(sessions) {
        val current = sessions.map { it.id }.toSet()
        (previousIds - current).forEach { savedLists.removeState(it) }
        previousIds = current
    }
    val background = chatBackground()
    val ownBackdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    val backdrop = glassBackdrop ?: ownBackdrop
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val bottomInset = if (keyboardVisible) 0.dp else maxOf(navigationInset,
        with(density) { WindowInsets.navigationBars.getBottom(this).toDp() })
    var composerHeight by remember { mutableStateOf(170.dp) }
    var headerHeight by remember { mutableStateOf(72.dp) }
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
            val compact = maxHeight < 330.dp
            val extrasLimit = (maxHeight - headerHeight - bottomInset - if (compact) 120.dp else 200.dp).coerceAtLeast(0.dp)
            Box(Modifier.fillMaxSize()) {
                if (state.sessionId.isNotEmpty()) savedLists.SaveableStateProvider(state.sessionId) {
                    ConversationMessages(state, viewModel::loadOlderMessages, composerHeight, headerHeight, backdrop, viewModel::retryLastRequest)
                }
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
                GlassSurface(backdrop, Modifier.fillMaxWidth(), RectangleShape) {
                Box(Modifier.padding(top = topInset)) {
                ConversationBar(sessions, state.sessionId,
                    { dismissKeyboard(); viewModel.newSession() },
                    { dismissKeyboard(); viewModel.clearChat() }, onOpenPage, onOpenUtility,
                    { dismissKeyboard(); showSessions = true }, onManageModels)
                }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                .padding(horizontal = 16.dp).padding(top = 10.dp, bottom = bottomInset + 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasExtras = state.loading || state.error != null || importing || state.question != null ||
                    state.queued.isNotEmpty() || (state.isStreaming && state.inputText.isNotBlank()) || state.attachments.isNotEmpty()
                if (hasExtras && extrasLimit > 0.dp) GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.heightIn(max = extrasLimit).verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.loading) Text("正在恢复本地对话…", fontSize = 12.sp)
                        state.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp, maxLines = 3) }
                        if (importing) Text("正在处理附件…", fontSize = 12.sp)
                        state.question?.let { question ->
                            Text("AI 需要你补充信息", fontWeight = FontWeight.Bold)
                            Text(question.question, fontSize = 14.sp)
                            question.options.forEach { answer -> TextButton(onClick = { viewModel.answerQuestion(answer) }) { Text(answer, fontSize = 13.sp) } }
                            Text("也可以在输入框中回答，或中断后发送新的要求。", fontSize = 11.sp)
                        }
                        if (state.queued.isNotEmpty()) TextButton(onClick = { if (!state.isStreaming) viewModel.sendMessage() }) {
                            Text(if (state.isStreaming) "${state.queued.size} 条插话将在下一步处理" else "发送 ${state.queued.size} 条待处理插话", fontSize = 12.sp)
                        }
                        if (state.isStreaming && state.inputText.isNotBlank()) TextButton(onClick = viewModel::interruptAndSend) { Text("中断并发送", fontSize = 12.sp) }
                        if (state.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.attachments.forEach { attachment -> Box(Modifier.width(240.dp)) {
                                AttachmentTile(attachment, { viewModel.removeAttachment(attachment.id) }, floating = true)
                            } }
                        }
                    }
                }
                GlassSurface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(GlassStyle.Panel)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            ModelSelector(state.modelName, { dismissKeyboard(); showModels = true }, Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            UsageIndicator(state)
                        }
                        if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { dismissKeyboard(); showWorkspaces = true }, enabled = !state.isStreaming, modifier = Modifier.testTag("workspace-selector")) {
                                Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(workspaces.firstOrNull { it.id == state.workspaceId }?.let { it.name + if (it.enabled) "" else " · 已停用" } ?: "选择工作区", fontSize = 11.sp, maxLines = 1)
                            }
                            Spacer(Modifier.weight(1f))
                            Text("本会话文件范围", fontSize = 10.sp, color = chatForeground().copy(alpha = .55f))
                        }
                        ChatInputBar(state.inputText, viewModel::updateInputText, sendMessage,
                            viewModel::stopStreaming, state.isStreaming,
                            !state.loading && !importing && (!requireModel || modelCatalog.providers.any { it.models.isNotEmpty() }), backdrop,
                            { showAttachMenu = true }, state.attachments.isNotEmpty() || state.queued.isNotEmpty(), state.question != null, compact, framed = false)
                        if (!compact) Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) { ConnectionStatusBar(connectionStatus) }
                    }
                }
                DropdownMenu(showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                    DropdownMenuItem(text = { Text("添加图片") }, onClick = { showAttachMenu = false; importingSession = state.sessionId; picker.launch(arrayOf("image/*")) })
                    DropdownMenuItem(text = { Text("添加文本文件") }, onClick = { showAttachMenu = false; importingSession = state.sessionId; picker.launch(arrayOf("*/*")) })
                }
            }
        }
        if (showSessions) ConversationList(sessions, state.sessionId, { showSessions = false },
            { dismissKeyboard(); viewModel.switchSession(it) }, { dismissKeyboard(); viewModel.newSession() }, viewModel::renameSession, viewModel::deleteSession)
        if (showModels) ModelPicker(modelCatalog, { showModels = false }, viewModel::selectModel, onManageModels)
        if (showWorkspaces) GlassDialog(true, onDismissRequest = { showWorkspaces = false }, title = "会话工作区") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI 的文件操作仅限此会话选择的工作区。生成期间不能切换。", fontSize = 12.sp)
                workspaces.filter { it.enabled }.forEach { workspace ->
                    TextButton(onClick = { viewModel.selectWorkspace(workspace.id); showWorkspaces = false }) {
                        Text((if (state.workspaceId == workspace.id) "● " else "") + workspace.name)
                    }
                }
                TextButton(onClick = { showWorkspaces = false; onOpenUtility("工作区") }) { Text("管理工作区与文件") }
            }
        }
    }
}

@Composable
private fun ConversationBar(sessions: List<AiChatManager.SessionInfo>, activeId: String,
    onNew: () -> Unit, onClear: () -> Unit, onOpenPage: (Int) -> Unit,
    onOpenUtility: (String) -> Unit, onSessions: () -> Unit, onManageModels: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSessions, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.Forum, "对话列表", tint = chatForeground(), modifier = Modifier.size(22.dp)) }
            TextButton(onClick = onSessions, modifier = Modifier.weight(1f)) {
                Text(sessions.firstOrNull { it.id == activeId }?.title ?: "新会话", maxLines = 1, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = chatForeground())
                Icon(Icons.Rounded.ExpandMore, null, tint = chatForeground().copy(alpha = .45f), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onNew, modifier = Modifier.size(40.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(15.dp))) {
                Icon(Icons.Rounded.Add, "新对话", tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.MoreHoriz, "对话操作", tint = chatForeground(), modifier = Modifier.size(23.dp))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(text = { Text("模型与供应商") }, onClick = { expanded = false; onManageModels() })
                    listOf("进程", "断点", "设置").forEachIndexed { index, name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onOpenPage(index + 1) })
                    }
                    listOf("工作区", "内存查看", "反汇编", "动态调试", "日志").forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onOpenUtility(name) })
                    }
                    DropdownMenuItem(text = { Text("删除当前会话", color = MiuixTheme.colorScheme.error) }, onClick = { expanded = false; confirmClear = true })
                }
            }
        }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("删除当前会话？") }, text = { Text("当前会话的消息和草稿将被删除。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun ModelSelector(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    Box(modifier.padding(vertical = 5.dp)) {
        Row(Modifier.heightIn(min = 36.dp).widthIn(max = 260.dp).clip(CircleShape)
            .background(primary.copy(alpha = .085f)).border(.7.dp, primary.copy(alpha = .12f), CircleShape)
            .clickable(onClick = onClick).testTag("model-selector").padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = primary, modifier = Modifier.size(15.dp))
            Text(name.ifBlank { "选择模型" }, color = primary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Rounded.ExpandMore, "选择模型", tint = primary.copy(alpha = .7f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ConversationMessages(state: ChatUiState, onLoadOlder: () -> Unit, bottomPadding: androidx.compose.ui.unit.Dp,
    topPadding: androidx.compose.ui.unit.Dp, backdrop: LayerBackdrop, onRetry: () -> Unit) {
    val entries = remember(state.messages) { conversationEntries(state.messages) }
    val visibleMessages = entries.map { it.messages.first() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex =
        (visibleMessages.lastIndex + if (state.hasOlderMessages) 1 else 0).coerceAtLeast(0))
    var following by rememberSaveable { mutableStateOf(true) }
    var consumedFollow by rememberSaveable { androidx.compose.runtime.mutableLongStateOf(state.followLatestRequest) }
    LaunchedEffect(state.followLatestRequest) {
        if (consumedFollow != state.followLatestRequest) { consumedFollow = state.followLatestRequest; following = true }
    }
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
        Box(Modifier.fillMaxSize().then(if (glassSupported()) Modifier.layerBackdrop(backdrop) else Modifier), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), size = 24.dp)
        }
        return
    }
    val latest by rememberUpdatedState(state)
    val latestDragged by rememberUpdatedState(dragged)
    var rendering by remember { mutableStateOf(false) }
    suspend fun scrollToBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == last }) listState.scrollToItem(last)
        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.takeIf { it.index == last }?.let {
            val extra = it.offset + it.size - (listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding)
            if (extra != 0) listState.scrollBy(extra.toFloat())
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
                layout.visibleItemsInfo.lastOrNull()?.let { Triple(it.index, it.size, it.offset) }, layout.viewportEndOffset, layout.afterContentPadding, latest.isStreaming, rendering)
        }
            .conflate().collect {
                // layoutInfo can emit inside a measure pass. Suspend before scrolling so
                // scrollToItem cannot force a nested measure while that pass is active.
                delay(80)
                // Keep the latest edge anchored through final Markdown/artifact layout as well.
                // User drags revoke following; completed output must not rely on a time window.
                if (following && !latestDragged && !listState.isScrollInProgress) scrollToBottom()
            }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().then(if (glassSupported()) Modifier.layerBackdrop(backdrop) else Modifier).testTag("chat-messages").semantics {
            val index = listState.firstVisibleItemIndex - if (state.hasOlderMessages) 1 else 0
            this[ChatAnchorMessage] = entries.getOrNull(index)?.id ?: "load-older"
            this[ChatAnchorOffset] = listState.firstVisibleItemScrollOffset
            this[ChatIsScrolling] = listState.isScrollInProgress
            this[ChatFollowsLatest] = following
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            this[ChatAtLatest] = last != null && last.index == layout.totalItemsCount - 1 &&
                last.offset + last.size <= layout.viewportEndOffset - layout.afterContentPadding + 4
        }, state = listState,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topPadding + 12.dp, bottom = bottomPadding + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasOlderMessages) item(key = "load-older") {
                TextButton(onClick = { following = false; onLoadOlder() }, modifier = Modifier.fillMaxWidth()) { Text("加载更早的消息") }
            }
            if (state.messages.isEmpty()) item(key = "empty") { EmptyChatPlaceholder() }
            items(entries, key = { it.id }, contentType = { if (it.execution) "execution" else it.messages.first().role }) { entry ->
                if (entry.execution) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ToolExecutionRun(entry.messages, chatForeground(), generationComplete = !state.isStreaming)
                } else ChatMessageBubble(entry.messages.first(), onRetry = if (state.canRetry && entry.messages.first().id == state.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id) onRetry else null, onRendering = { active ->
                    if (entry.id == entries.lastOrNull()?.id) rendering = active
                })
            }
        }
        if (!following && state.messages.isNotEmpty()) GlassSurface(backdrop,
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = bottomPadding + 8.dp), CircleShape) {
            IconButton(onClick = { following = true }, modifier = Modifier.size(44.dp).testTag("jump-to-latest")) {
                Icon(Icons.Rounded.ArrowDownward, "回到最新", tint = chatForeground(), modifier = Modifier.size(22.dp))
            }
        }
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
fun ChatMessageBubble(message: ChatMessage, onRetry: (() -> Unit)? = null, onRendering: (Boolean) -> Unit = {}) {
    var rendering by remember(message.id) { mutableStateOf(false) }
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
                    ReasoningPanel(message)
                    message.retry?.let { RetryRow(it) }
                    ExecutionStatus(message, contentColor)
                    message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                        ToolExecutionGroup(calls, contentColor)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                message.failure?.let { ProviderErrorCard(it, onRetry) }
                if (message.content.isNotEmpty()) {
                    if (isUser || isTool) {
                        SelectionContainer { Text(message.content, color = contentColor, fontSize = 14.sp, lineHeight = 22.sp,
                            fontFamily = if (isTool) FontFamily.Monospace else FontFamily.Default) }
                    } else key(message.id) {
                        MarkdownMessage(message.content, message.isStreaming, contentColor, onRendering = {
                            rendering = it; onRendering(it)
                        })
                    }

                }

                // 流式指示器
                if (!isUser && message.content.isNotEmpty()) {
                    StreamingCursor(message.isStreaming || rendering)
                }
            }
        }
    }
}

/**
 * 流式光标动画
 */
@Composable
fun StreamingCursor(active: Boolean = true) {
    if (!active || !com.kernelai.app.ui.theme.LocalUiMotionEnabled.current) {
        Spacer(Modifier.height(18.dp)); return
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "stream cursor")
    val alpha by transition.animateFloat(.2f, .85f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "cursor opacity")
    Box(Modifier.height(18.dp).padding(top = 3.dp)) {
        Box(Modifier.size(3.dp, 12.dp).graphicsLayer { this.alpha = alpha }.background(MiuixTheme.colorScheme.primary, CircleShape))
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
    compact: Boolean = false,
    framed: Boolean = true
) {
    val textColor = chatForeground()
    val input: @Composable () -> Unit = {
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
    if (framed) GlassSurface(backdrop, Modifier.fillMaxWidth()) { input() } else input()
}
