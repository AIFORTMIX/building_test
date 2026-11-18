package com.kernelai.app.ui.screen.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DeviceMemoryPanel(instructions: Boolean = false) {
    val navigator = LocalAppNavigator.current
    var target by rememberSaveable { mutableStateOf("") }; var address by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf(if (instructions) "16" else "128") }; var pattern by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val binding = rememberProcessBinding(target) { revision++; address = ""; result = "" }
    val scope = rememberCoroutineScope()
    fun invoke(search: Boolean) {
        if (busy) return
        val lease = binding.target ?: return
        val operationRevision = revision
        busy = true
        scope.launch {
            try {
                val tools = KernelAiApplication.instance.aiToolExecutor
                val name = if (search) "memory_search" else if (instructions) "disassemble_at" else "memory_inspect"
                val args = buildJsonObject {
                    put("pid", lease.pid); put("expected_identity", lease.identity)
                    if (search) { put("pattern", pattern); put("max_results", 64); if (address.isNotBlank()) put("start_addr", address) }
                    else { put("address", address); put(if (instructions) "count" else "size", size.toInt()) }
                }
                val raw = tools.executeTool(name, args)
                val parsed = Json.parseToJsonElement(raw).jsonObject
                if (parsed["requires_rebind"]?.jsonPrimitive?.booleanOrNull == true) binding.invalidate(parsed["message"]?.jsonPrimitive?.contentOrNull ?: "目标已改变")
                if (operationRevision == revision) result = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), parsed)
            } catch (e: Exception) { if (e is CancellationException) throw e; if (operationRevision == revision) result = "查询失败：${e.message}" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row { TextButton(onClick = { navigator.pop() }) { Text("返回") }; Text(if (instructions) "指令字节" else "内存查看", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Text(if (instructions) "读取真实 ARM64 指令字节，未内置反汇编器；可交给 AI 进一步分析。" else "直接查询驱动；空地址、失效 PID 和部分读取会明确报告。", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(target, { target = it }, Modifier.weight(1f), label = "PID 或完整包名", singleLine = true)
            TextButton(onClick = { binding.bind(target) }, enabled = target.isNotBlank() && !binding.busy && !busy) { Text("绑定目标") }
        }
        Text(binding.status, fontSize = 12.sp, color = if (binding.target == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.primary)
        TextField(address, { address = it }, label = "当前绑定进程的地址（0x）", singleLine = true, enabled = binding.target != null)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(size, { size = it.filter(Char::isDigit) }, Modifier.weight(1f), label = if (instructions) "指令数（1–256）" else "字节数（1–1024）", singleLine = true)
            TextButton(onClick = { invoke(false) }, enabled = !busy && binding.target != null && address.isNotBlank()) { Text("读取") }
        }
        if (!instructions) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(pattern, { pattern = it }, Modifier.weight(1f), label = "精确十六进制字节模式", singleLine = true)
            TextButton(onClick = { invoke(true) }, enabled = !busy && binding.target != null && pattern.isNotBlank()) { Text("搜索") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Card(Modifier.weight(1f).fillMaxWidth()) { SelectionContainer {
            Text(result.ifBlank { "查询结果会显示在这里" }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
        } }
    }
}
