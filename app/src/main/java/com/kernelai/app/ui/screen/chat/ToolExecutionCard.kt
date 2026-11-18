package com.kernelai.app.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.kernelai.app.ui.components.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import com.kernelai.app.ui.components.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.data.model.*
import com.kernelai.app.mcp.McpToolDefinitions
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.util.Locale

private val detailJson = Json { prettyPrint = true }

@Composable
fun ExecutionStatus(message: ChatMessage, color: Color) {
    val label = when (message.phase) {
        AssistantPhase.THINKING -> "思考中…"
        AssistantPhase.RESPONDING -> if (message.content.isNotBlank()) return else "正在生成回复…"
        AssistantPhase.PREPARING_TOOL -> "正在准备工具调用…"
        AssistantPhase.EXECUTING_TOOL -> "正在执行工具…"
        AssistantPhase.SUMMARIZING -> if (message.content.isNotBlank()) return else "正在根据执行结果整理回复…"
        AssistantPhase.CANCELLED -> "已停止生成"
        AssistantPhase.FAILED -> "请求失败"
        AssistantPhase.COMPLETE -> return
        null -> return
    }
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.isStreaming) CircularProgressIndicator(Modifier.size(14.dp), size = 14.dp, strokeWidth = 2.dp, color = color)
        Text(label, fontSize = 12.sp, color = color.copy(alpha = .8f), modifier = executionPulse(message.isStreaming))
    }
}

@Composable
fun ToolExecutionGroup(calls: List<ToolCallInfo>, color: Color, labelPrefix: String? = null, groupId: String = calls.first().id) {
    var expanded by rememberSaveable(groupId) { mutableStateOf(false) }
    val active = calls.any { it.status == ToolCallStatus.RUNNING || it.status == ToolCallStatus.PENDING }
    val failed = calls.any { it.status == ToolCallStatus.FAILED }
    val cancelled = calls.any { it.status == ToolCallStatus.CANCELLED }
    val label = when {
        active -> "正在执行工具"
        failed -> "部分工具执行失败"
        cancelled -> "工具执行已停止"
        else -> "工具执行完成"
    }
    val status = when { active -> ToolCallStatus.RUNNING; failed -> ToolCallStatus.FAILED; cancelled -> ToolCallStatus.CANCELLED; else -> ToolCallStatus.COMPLETED }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().toolOutline(color).clickable { expanded = !expanded }
            .semantics { heading(); stateDescription = if (expanded) "已展开" else "已折叠" }
            .padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolStateIcon(status)
            Spacer(Modifier.width(10.dp))
            Text("${labelPrefix ?: label} · ${calls.size} 项", color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f).then(executionPulse(active)))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "折叠工具列表" else "展开工具列表", tint = color.copy(alpha = .45f))
        }
        if (expanded) calls.forEach { ToolExecutionCard(it, color) }
    }
}

/** One compact run, then model-request batches, then per-tool parameters and receipts. */
@Composable
internal fun ToolExecutionRun(messages: List<ChatMessage>, color: Color, generationComplete: Boolean = false) {
    val calls = messages.flatMap { it.toolCalls.orEmpty() }
    if (calls.isEmpty()) return
    var expanded by rememberSaveable(messages.first().id) { mutableStateOf(false) }
    LaunchedEffect(generationComplete) { if (generationComplete) expanded = false }
    val waitingForModel = messages.any { it.isStreaming && it.toolCalls.isNullOrEmpty() }
    val active = waitingForModel || calls.any { it.status in listOf(ToolCallStatus.PENDING, ToolCallStatus.RUNNING) }
    val failed = calls.any { it.status == ToolCallStatus.FAILED }
    val cancelled = calls.any { it.status == ToolCallStatus.CANCELLED }
    val status = when { active -> ToolCallStatus.RUNNING; failed -> ToolCallStatus.FAILED; cancelled -> ToolCallStatus.CANCELLED; else -> ToolCallStatus.COMPLETED }
    Column(Modifier.fillMaxWidth().testTag("tool-run-${messages.first().id}").toolOutline(color)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolStateIcon(status)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(when { active -> "正在执行 · ${calls.size} 项工具"; failed -> "执行过程 · ${calls.size} 项，有失败"; cancelled -> "执行过程 · 已停止"; else -> "执行过程 · ${calls.size} 项工具" },
                    color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = executionPulse(active))
                if (active) Text(if (waitingForModel) "正在整理执行结果…" else toolPurpose(calls.firstOrNull { it.status == ToolCallStatus.RUNNING } ?: calls.last()),
                    color = color.copy(alpha = .58f), fontSize = 11.sp, maxLines = 1)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "收起执行过程" else "展开执行过程", tint = color.copy(alpha = .5f), modifier = Modifier.size(20.dp))
        }
        if (expanded) Column(Modifier.padding(start = 18.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            messages.filter { !it.toolCalls.isNullOrEmpty() }.forEachIndexed { index, message -> key(message.id) {
                if (message.content.isNotBlank()) MarkdownMessage(message.content, false, color)
                ToolExecutionGroup(message.toolCalls.orEmpty(), color, "第 ${index + 1} 批", message.id)
            } }
        }
    }
}

private fun Modifier.toolOutline(color: Color) = background(color.copy(alpha = .015f), RoundedCornerShape(18.dp))
    .border(1.dp, color.copy(alpha = .07f), RoundedCornerShape(18.dp))

@Composable
private fun ToolStateIcon(status: ToolCallStatus) {
    val green = Color(0xFF20AE91)
    when (status) {
        ToolCallStatus.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.3.dp, color = green)
        ToolCallStatus.COMPLETED -> Box(Modifier.size(20.dp).background(green, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, "执行成功", tint = Color.White, modifier = Modifier.size(15.dp))
        }
        ToolCallStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, "执行失败", tint = Color(0xFFE45B64), modifier = Modifier.size(21.dp))
        ToolCallStatus.CANCELLED -> Icon(Icons.Rounded.Close, "已取消", tint = Color(0xFF999DA6), modifier = Modifier.size(20.dp))
        ToolCallStatus.PENDING -> Icon(Icons.Rounded.Schedule, "等待执行", tint = Color(0xFF999DA6), modifier = Modifier.size(20.dp))
    }
}

/** Shows observable execution records; model-private reasoning is neither requested nor fabricated. */
@Composable
fun ToolExecutionCard(call: ToolCallInfo, color: Color) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    var now by remember(call.startedAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.status, call.startedAtMillis) {
        while (call.status == ToolCallStatus.RUNNING && call.startedAtMillis != null) {
            now = System.currentTimeMillis(); delay(500)
        }
    }
    val status = when (call.status) {
        ToolCallStatus.PENDING -> "准备调用"
        ToolCallStatus.RUNNING -> "执行中"
        ToolCallStatus.COMPLETED -> "完成"
        ToolCallStatus.FAILED -> "失败"
        ToolCallStatus.CANCELLED -> "已取消等待 · 结果未确认"
    }
    val duration = call.durationMs ?: call.startedAtMillis?.takeIf { call.status == ToolCallStatus.RUNNING }?.let { (now - it).coerceAtLeast(0) }
    val durationText = duration?.let { " · " + if (it < 1000) "${it}ms" else String.format(Locale.ROOT, "%.1fs", it / 1000.0) }.orEmpty()
    val description = remember(call.name) { McpToolDefinitions.ALL_TOOLS.firstOrNull { it.name == call.name }?.description }
    Column(Modifier.fillMaxWidth().testTag("tool-${call.id}").toolOutline(color)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }
            .semantics { stateDescription = status + if (expanded) "，已展开" else "，已折叠" }
            .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolStateIcon(call.status)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(toolPurpose(call), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = executionPulse(call.status in listOf(ToolCallStatus.PENDING, ToolCallStatus.RUNNING)))
                Text(status + durationText, color = color.copy(alpha = .48f), fontSize = 10.sp)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                if (expanded) "收起 ${call.name} 详情" else "展开 ${call.name} 详情", tint = color.copy(alpha = .42f), modifier = Modifier.size(21.dp))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("工具：${call.name}", color = color, fontSize = 12.sp)
            description?.let { Text("执行提示：$it", color = color, fontSize = 12.sp, lineHeight = 19.sp) }
            Text("调用 ID：${call.id}", color = color.copy(alpha = .7f), fontSize = 11.sp)
            ExecutionDetail("调用参数", call.arguments.ifBlank { "（尚未接收到参数）" }, color, json = true)
            call.result?.let { ExecutionDetail(if (call.status == ToolCallStatus.FAILED) "错误详情" else "返回结果", it, color) }
            if (call.status == ToolCallStatus.CANCELLED) Text("已停止等待；已经提交的设备操作可能仍会完成。", color = color, fontSize = 12.sp)
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExecutionDetail(label: String, value: String, color: Color, json: Boolean = false) {
    val copyText = rememberChatCopy()
    var showMore by rememberSaveable(value.take(80)) { mutableStateOf(false) }
    val preview = remember(value, showMore, json) {
        val limit = if (showMore) 128_000 else 6_000
        val text = if (json && value.length <= 16_000) runCatching {
            detailJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), Json.parseToJsonElement(value))
        }.getOrDefault(value) else value
        text.take(limit) + if (text.length > limit) "\n…（显示已截断，可复制完整内容）" else ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton(onClick = { copyText(value) }) { Text("复制$label", fontSize = 11.sp, color = color) }
    }
    SelectionContainer {
        Text(preview, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().background(color.copy(alpha = .04f), RoundedCornerShape(4.dp))
                .horizontalScroll(rememberScrollState()).padding(8.dp))
    }
    if (value.length > 6_000) TextButton(onClick = { showMore = !showMore }) { Text(if (showMore) "收起长内容" else "展开更多内容", color = color) }
}
