package com.kernelai.app.ui.screen.debugger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.mcp.DebugTools
import com.kernelai.app.ui.components.*
import com.kernelai.app.ui.navigation.LocalAppNavigator
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** All controls operate on the same session that the AI and external MCP clients see. */
@Composable
internal fun DebuggerScreen(tools: DebugTools = KernelAiApplication.instance.debugTools) {
    val navigator = LocalAppNavigator.current
    val state by tools.state.collectAsStateWithLifecycle()
    val id = state?.get("session_id")?.jsonPrimitive?.contentOrNull
    val phase = state?.get("state")?.jsonPrimitive?.contentOrNull ?: "detached"
    val active = id != null && phase !in setOf("detached", "exited", "target_changed", "connection_lost", "idle_timeout", "attach_failed", "service_closed", "unavailable")
    val stopped = active && phase == "stopped"
    var pid by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var tid by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("4") }
    var bpType by rememberSaveable { mutableIntStateOf(0) }
    var typeMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pretty = remember { Json { prettyPrint = true } }
    suspend fun execute(name: String, args: JsonObject): JsonObject {
        val value = Json.parseToJsonElement(tools.executeTool(name, args)).jsonObject
        check(value["is_error"] != JsonPrimitive(true)) { value["message"]?.jsonPrimitive?.contentOrNull ?: value.toString() }
        return value
    }
    fun invoke(name: String, extra: JsonObjectBuilder.() -> Unit = {}) {
        if (busy) return
        val captured = id
        val args = buildJsonObject { captured?.let { put("session_id", it) }; if (tid.isNotBlank()) put("thread_id", tid); extra() }
        busy = true; error = null
        scope.launch {
            try {
                val value = execute(name, args)
                if (captured == null || captured == tools.state.value?.get("session_id")?.jsonPrimitive?.contentOrNull) {
                    result = pretty.encodeToString(JsonElement.serializer(), value)
                }
                if (name != "debug_status" && captured != null) execute("debug_status", buildJsonObject { put("session_id", captured) })
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(id, active) {
        if (id == null || !active) return@LaunchedEffect
        while (isActive) {
            delay(700)
            if (!busy) try { execute("debug_status", buildJsonObject { put("session_id", id); put("after_event", ((tools.state.value?.get("last_event")?.jsonPrimitive?.longOrNull ?: 10) - 10).coerceAtLeast(0)) }) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
        }
    }
    LaunchedEffect(id) { address = ""; tid = ""; result = ""; if (bpType <= 1) size = if (state?.get("architecture") == JsonPrimitive("arm64")) "4" else "1" }
    LaunchedEffect(active) { if (!active) { address = ""; tid = ""; if (phase in setOf("exited", "target_changed", "connection_lost", "unavailable")) result = "" } }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { TextButton({ navigator.pop() }) { Text("返回") }; Text("动态调试", fontSize = 24.sp) }
        Text("Root · 内置 LLDB · 线程暂停与单步 · 无需 KO", fontSize = 12.sp)
        GlassCard { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (phase in setOf("detached", "service_closed", "idle_timeout") && state?.get("cleanup_confirmed") == JsonPrimitive(false)) "连接已关闭，清理尚未确认" else stateLabel(phase), Modifier.testTag("debug-state"), color = MiuixTheme.colorScheme.primary)
            state?.get("pid")?.jsonPrimitive?.contentOrNull?.let { Text("目标 PID $it · ${state?.get("architecture")?.jsonPrimitive?.contentOrNull.orEmpty()}", fontSize = 12.sp) }
            TextField(pid, { pid = it.filter(Char::isDigit) }, Modifier.fillMaxWidth().testTag("debug-pid"), label = "目标 PID", singleLine = true, enabled = !active && !busy)
            Row {
                TextButton({ invoke("debug_capabilities") }, enabled = !busy) { Text("检查后端") }
                TextButton({
                    val number = pid.toIntOrNull() ?: return@TextButton
                    busy = true; error = null
                    scope.launch {
                        try {
                            val target = execute("debug_target_status", buildJsonObject { put("pid", number) })
                            check(target["running"] == JsonPrimitive(true) && target["process_identity"] is JsonPrimitive) { "目标不存在或无法核实身份" }
                            val attached = execute("debug_attach", buildJsonObject { put("pid", number); put("expected_identity", target["process_identity"]!!) })
                            result = pretty.encodeToString(JsonElement.serializer(), attached)
                        } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
                        finally { busy = false }
                    }
                }, enabled = !active && !busy && (pid.toIntOrNull() ?: 0) > 1) { Text("附加并暂停") }
            }
            Text("附加会暂停目标全部线程；结束时脱离，闲置两分钟也会自动脱离。不能暂停承载聊天与 MCP 的宿主自身。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row {
                TextButton({ invoke("debug_pause") }, enabled = active && !stopped && !busy) { Text("暂停") }
                TextButton({ invoke("debug_continue") }, enabled = stopped && !busy) { Text("继续") }
                TextButton({ invoke("debug_step") }, enabled = stopped && !busy) { Text("单步") }
                TextButton({ invoke("debug_detach") }, enabled = active && !busy) { Text("脱离") }
            }
        } }
        GlassCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(tid, { tid = it.take(64) }, Modifier.fillMaxWidth(), label = "线程 ID（十六进制，留空用停止线程）", singleLine = true, enabled = stopped && !busy)
            Row {
                TextButton({ invoke("debug_threads") }, enabled = stopped && !busy) { Text("线程") }
                TextButton({ invoke("debug_registers") }, enabled = stopped && !busy) { Text("寄存器") }
            }
            TextField(address, { address = it }, Modifier.fillMaxWidth().testTag("debug-address"), label = "当前会话地址（0x）", singleLine = true, enabled = stopped && !busy)
            Row {
                TextButton({ invoke("debug_memory_read") { put("address", address); put("size", 128) } }, enabled = stopped && !busy && address.isNotBlank()) { Text("读取内存") }
                TextButton({ invoke("debug_disassemble") { put("address", address); put("count", 16) } }, enabled = stopped && !busy && address.isNotBlank() && state?.get("architecture") == JsonPrimitive("arm64")) { Text("反汇编") }
            }
            val names = listOf("软件执行", "硬件执行", "写监视", "读监视", "读写监视")
            Row {
                TextButton({ typeMenu = true }, enabled = stopped && !busy) { Text(names[bpType]) }
                TextField(size, { size = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = "断点字节数", singleLine = true, enabled = stopped && !busy)
                TextButton({ invoke("debug_breakpoint_set") { put("address", address); put("type", bpType); put("size", size.toInt()) } }, enabled = stopped && !busy && address.isNotBlank() && size.toIntOrNull() in listOf(1, 2, 4, 8)) { Text("设断点") }
            }
            DropdownMenu(typeMenu, { typeMenu = false }) { names.forEachIndexed { index, name -> DropdownMenuItem({ Text(name) }, { bpType = index; typeMenu = false }) } }
            state?.get("breakpoints")?.jsonArray.orEmpty().forEach { entry -> val bp = entry.jsonObject
                Row(Modifier.fillMaxWidth()) {
                    Text("${bp["address"]!!.jsonPrimitive.content} · ${bp["size"]} B", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    TextButton({ invoke("debug_breakpoint_remove") { put("breakpoint_id", bp["id"]!!) } }, enabled = stopped && !busy) { Text("移除") }
                }
            }
        } }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        GlassCard { SelectionContainer { Text(result.ifBlank { "操作结果会显示在这里；内容来自实际调试会话。" }, Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(14.dp).testTag("debug-result"), fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
        state?.get("events")?.jsonArray?.takeLast(10)?.let { events -> GlassCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近调试事件 · 最多显示 10 项")
            events.forEach { event -> Text("#${event.jsonObject["id"]} · ${event.jsonObject["type"]?.jsonPrimitive?.content.orEmpty()}", fontSize = 12.sp) }
        } } }
    }
}

private fun stateLabel(value: String) = when (value) {
    "stopped" -> "已暂停全部线程"; "running" -> "运行中"; "stepping" -> "正在单步"; "pausing" -> "正在暂停"
    "exited" -> "目标已退出"; "target_changed" -> "目标身份已改变"; "idle_timeout" -> "闲置超时，已请求脱离"
    "connection_lost", "unavailable" -> "连接中断，需核实清理状态"; "attach_failed" -> "附加失败"; else -> "未附加 / 已脱离"
}
