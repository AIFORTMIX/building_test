package com.kernelai.app.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Refresh
import com.kernelai.app.ai.AiFailure
import com.kernelai.app.data.model.*
import com.kernelai.app.ui.components.TextButton
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Displays only reasoning actually returned by the configured provider. */
@Composable
internal fun ReasoningPanel(message: ChatMessage) {
    if (message.reasoning.isBlank()) return
    val active = message.isStreaming && message.phase == AssistantPhase.THINKING
    var expanded by rememberSaveable(message.id) { mutableStateOf(active) }
    LaunchedEffect(message.isStreaming) { if (!message.isStreaming) expanded = false }
    val color = MiuixTheme.colorScheme.onBackground.copy(alpha = .65f)
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("reasoning-${message.id}")) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = .13f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            }
            Text(if (active) "思考中" else "思考", color = color, fontSize = 13.sp,
                modifier = Modifier.padding(start = 9.dp).then(executionPulse(active)))
            if (!expanded) Text(" · " + message.reasoning.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = color.copy(alpha = .8f), fontSize = 12.sp)
            else Spacer(Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "收起思考" else "展开思考", tint = color, modifier = Modifier.size(20.dp))
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
            .padding(start = 12.dp).background(color.copy(alpha = .035f), RoundedCornerShape(12.dp)).padding(12.dp)) {
            MarkdownMessage(message.reasoning, active, color)
        }
    }
}

@Composable
internal fun RetryRow(notice: RetryNotice) {
    var now by remember(notice.nextAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(notice.nextAt) { while (now < notice.nextAt) { delay(250); now = System.currentTimeMillis() } }
    val seconds = ((notice.nextAt - now).coerceAtLeast(0) + 999) / 1000
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("provider-retry"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Refresh, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(19.dp))
        Text("请求失败，正在重试（${notice.attempt}/${notice.maximum}）" + if (seconds > 0) " · ${seconds}s" else " · 连接中",
            Modifier.padding(start = 9.dp).then(executionPulse(true)), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
    }
}

@Composable
internal fun ProviderErrorCard(failure: AiFailure, onRetry: (() -> Unit)?) {
    var detail by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(MiuixTheme.colorScheme.error.copy(alpha = .07f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(failure.title + failure.status?.let { " · HTTP $it" }.orEmpty(), color = MiuixTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(failure.suggestion, fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackground)
        if (failure.partialOutput) Text("已保留收到的内容；没有自动重新生成或执行未完成工具。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (failure.attempts > 1) Text("已请求 ${failure.attempts} 次", fontSize = 11.sp)
        Row { onRetry?.let { TextButton(onClick = it, modifier = Modifier.testTag("retry-last-request")) { Text("重试本轮") } }
            TextButton(onClick = { detail = !detail }) { Text(if (detail) "收起错误详情" else "错误详情") } }
        if (detail) Text(listOfNotNull(failure.detail, failure.code?.let { "错误代码：$it" }, failure.requestId?.let { "请求 ID：$it" }).joinToString("\n"),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackground)
    }
}
