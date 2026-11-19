package com.kernelai.app.ui.screen.settings

import com.kernelai.app.ui.components.GlassCard as Card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.PowerManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.mcp.*
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.screen.chat.rememberChatCopy
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kernelai.app.ui.screen.chat.GlassDialog as WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun McpAccessPanel(onOpenAudit: () -> Unit) {
    val runtime = KernelAiApplication.instance.mcpRuntime
    val state by runtime.state.collectAsStateWithLifecycle()
    val entries by runtime.audit.entries.collectAsStateWithLifecycle()
    val error by McpHostService.error.collectAsStateWithLifecycle()
    val busy by McpHostService.busy.collectAsStateWithLifecycle()
    val running by McpHostService.running.collectAsStateWithLifecycle()
    val protectionOn by McpHostService.protection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) exempt = power.isIgnoringBatteryOptimizations(context.packageName) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val copy = rememberChatCopy()
    var switching by remember { mutableStateOf(false) }
    var uiError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state, error) { switching = false }
    Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text("允许外部客户端接入", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Switch(state.exposed, { enabled ->
                switching = true
                runCatching { McpHostService.setAccess(context, enabled) }.onFailure { uiError = it.message; switching = false }
            }, enabled = !switching && !busy, modifier = Modifier.testTag("mcp-exposure-switch"))
        }
        Text("默认关闭。开启后监听设备 IPv4 网络接口，持令牌的客户端可调用设备工具。后台恢复保留端口和令牌；关闭后旧令牌失效。HTTP 无传输加密，请在可信网络使用。", fontSize = 12.sp)
        if (state.exposed) {
            Text(if (running && protectionOn) "后台服务运行中 · 已保持 CPU 唤醒" else "后台服务正在恢复", fontSize = 12.sp)
            Text(if (exempt) "电池优化：已允许后台运行" else "锁屏深度休眠可能暂停网络，请允许不受电池优化限制，并在系统设置允许自启动。", fontSize = 12.sp)
            if (!exempt) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                    .recoverCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { uiError = it.message }
            }) { Text("允许后台网络运行") }
            runtime.externalUrls().forEach { url -> TextButton(onClick = { copy(url) }) { Text(url, fontSize = 12.sp) } }
            TextButton(onClick = { runCatching { copy("Bearer " + runtime.externalAccessToken()) } }) { Text("复制 Authorization 访问令牌") }
            Text("连接方式：Streamable HTTP · POST /mcp · Authorization: Bearer <令牌>", fontSize = 11.sp)
        }
        if (switching) LinearProgressIndicator(Modifier.fillMaxWidth())
        (uiError ?: error)?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        TextButton(onClick = onOpenAudit) { Text("第三方调用记录 · ${entries.count { it.external }} 条") }
    } }
}

@Composable
fun McpAuditScreen(onBack: () -> Unit) {
    val audit = KernelAiApplication.instance.mcpRuntime.audit
    val entries by audit.entries.collectAsStateWithLifecycle()
    val auditError by audit.error.collectAsStateWithLifecycle()
    var onlyExternal by remember { mutableStateOf(true) }; var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<McpAuditEntry?>(null) }; var confirmClear by remember { mutableStateOf(false) }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row { TextButton(onClick = onBack) { Text("返回") }; Text("MCP 调用记录", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold); TextButton(onClick = { confirmClear = true }) { Text("清空") } }
        auditError?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        Text("本地保存最近 1000 条 · 客户端名称由对方自报，地址来自连接", fontSize = 11.sp)
        Row { Text("仅第三方调用", Modifier.weight(1f)); Switch(onlyExternal, { onlyExternal = it }) }
        TextField(query, { query = it }, label = "搜索客户端、工具、操作或状态", singleLine = true)
        val visible = entries.filter { (!onlyExternal || it.external) && (it.client + it.remoteAddress + it.tool + it.purpose + it.status).contains(query, true) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty()) item { Text("尚无符合条件的记录", Modifier.padding(18.dp)) }
            items(visible, key = { it.id }) { entry ->
                Card(Modifier.clickable { detail = entry }) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.purpose.ifBlank { entry.tool.ifBlank { entry.method } }, fontWeight = FontWeight.Medium)
                    Text("${formatter.format(Date(entry.time))} · ${entry.durationMs} ms · ${entry.status}", fontSize = 11.sp)
                    Text("${entry.client} · ${entry.remoteAddress}", fontSize = 12.sp)
                } }
            }
        }
    }
    detail?.let { entry -> WindowDialog(show = true, title = "调用详情", onDismissRequest = { detail = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("时间：${formatter.format(Date(entry.time))}\n客户端（自报）：${entry.client}\n地址：${entry.remoteAddress}\n方法：${entry.method}\n工具：${entry.tool}\n目的：${entry.purpose}\n请求 ID：${entry.requestId}\n耗时：${entry.durationMs} ms\n状态：${entry.status}", fontSize = 13.sp)
            Text("参数摘要：${entry.arguments}", fontSize = 12.sp)
            TextButton(onClick = { detail = null }) { Text("关闭") }
        }
    } }
    if (confirmClear) WindowDialog(show = true, title = "清空调用记录？", onDismissRequest = { confirmClear = false }) {
        Row { TextButton(onClick = { confirmClear = false }) { Text("取消") }; TextButton(onClick = { audit.clear(); confirmClear = false }) { Text("清空") } }
    }
}
