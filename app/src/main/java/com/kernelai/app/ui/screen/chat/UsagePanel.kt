package com.kernelai.app.ui.screen.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.ui.components.GlassCard
import com.kernelai.app.ui.viewmodel.ChatUiState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

internal fun compactTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0).replace(".00M", "M")
    value >= 1000 -> String.format(Locale.ROOT, "%.1fK", value / 1000.0).replace(".0K", "K")
    else -> value.toString()
}

@Composable
internal fun UsageIndicator(state: ChatUiState) {
    var expanded by remember { mutableStateOf(false) }
    val fraction = (state.context.total.toFloat() / state.contextWindow.coerceAtLeast(1)).coerceIn(0f, 1f)
    val percent = ((state.context.total.toLong() * 100 + state.contextWindow.coerceAtLeast(1) / 2) / state.contextWindow.coerceAtLeast(1)).toInt()
    val color = if (percent >= 90) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    val track = MiuixTheme.colorScheme.onBackground.copy(alpha = .13f)
    Row(Modifier.clickable { expanded = true }.testTag("usage-indicator").padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(17.dp)) {
            drawArc(track, -90f, 360f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(color, -90f, fraction * 360f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("$percent%", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (expanded) GlassDialog(true, { expanded = false }, title = "用量") {
        Text("上下文", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        GlassCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("预计下次请求", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("$percent%", Modifier.weight(1f), fontSize = 29.sp, fontWeight = FontWeight.Medium)
                Text("${compactTokens(state.context.total.toLong())} / ${compactTokens(state.contextWindow.toLong())}", fontSize = 13.sp)
            }
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
            Text("系统 ${compactTokens(state.context.system.toLong())} · 工具 ${compactTokens(state.context.tools.toLong())} · 消息 ${compactTokens(state.context.messages.toLong())}", fontSize = 12.sp)
            Text("图像估算 ${compactTokens(state.context.images.toLong())} · 预留输出 ${compactTokens(state.context.reservedOutput.toLong())}", fontSize = 12.sp)
            Text("本地独立估算，含预留输出；分词方式和图像消耗以服务商实际返回为准。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } }
        Text("本对话", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        GlassCard { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(compactTokens(state.usageTotals.input), fontSize = 22.sp); Text("累计输入", fontSize = 11.sp) }
                Column { Text(compactTokens(state.usageTotals.output), fontSize = 22.sp); Text("输出", fontSize = 11.sp) }
                Column { Text(if (state.usageTotals.cacheReported) "${state.usageTotals.cached * 100 / state.usageTotals.cacheInput.coerceAtLeast(1)}%" else "未返回", fontSize = 22.sp); Text("缓存命中", fontSize = 11.sp) }
            }
            Text("已报告 ${state.usageTotals.requests} 轮用量 · 输入含缓存命中", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (!state.usageTotals.cacheReported) Text("服务商没有报告缓存数据，不推测缓存命中率。", fontSize = 11.sp)
            state.usage?.takeUnless { it.estimated }?.let { Text("上次实际：输入 ${compactTokens(it.promptTokens.toLong())} · 输出 ${compactTokens(it.completionTokens.toLong())}" + it.reasoningTokens?.let { n -> " · 其中思考 ${compactTokens(n.toLong())}" }.orEmpty(), fontSize = 11.sp) }
        } }
    }
}
