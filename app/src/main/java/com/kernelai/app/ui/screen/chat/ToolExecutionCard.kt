package com.kernelai.app.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        AssistantPhase.RESPONDING -> "正在生成回复…"
        AssistantPhase.PREPARING_TOOL -> "正在准备工具调用…"
        AssistantPhase.EXECUTING_TOOL -> "正在执行工具…"
        AssistantPhase.SUMMARIZING -> "正在根据执行结果整理回复…"
        AssistantPhase.CANCELLED -> "已停止生成"
        AssistantPhase.FAILED -> "请求失败"
        AssistantPhase.COMPLETE -> if (!message.toolCalls.isNullOrEmpty()) "工具执行结束" else return
        null -> return
    }
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.isStreaming) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        Text(label, fontSize = 12.sp, color = color.copy(alpha = .8f))
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
    Column(Modifier.fillMaxWidth().background(color.copy(alpha = .06f), RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (call.status == ToolCallStatus.RUNNING) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(call.name.ifBlank { "正在生成工具名称…" }, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(status + durationText, color = color.copy(alpha = .75f), fontSize = 11.sp)
            }
            Text(if (expanded) "收起详情 ▴" else "展开详情 ▾", color = color, fontSize = 11.sp)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
