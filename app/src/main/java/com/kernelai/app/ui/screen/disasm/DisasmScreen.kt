package com.kernelai.app.ui.screen.disasm

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
import com.kernelai.app.ui.components.*
import com.kernelai.app.ui.navigation.LocalAppNavigator
import com.kernelai.app.ui.screen.memory.DeviceMemoryPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DisasmScreen() {
    var live by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!live, { live = false }, { Text("静态 ELF") })
            FilterChip(live, { live = true }, { Text("运行中指令") })
        }
        if (live) DeviceMemoryPanel(instructions = true) else StaticAnalysisPanel()
    }
}

@Composable
private fun StaticAnalysisPanel() {
    val app = KernelAiApplication.instance
    val workspaces by app.workspaceRepository.workspaces.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    val scope = rememberCoroutineScope()
    var workspace by rememberSaveable { mutableStateOf("default") }
    var path by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("0x0") }
    var fromVirtual by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("sections") }
    var query by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var workspaceMenu by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    fun invalidate() { revision++; hash = null; result = null; error = null }
    LaunchedEffect(Unit) { app.workspaceRepository.load() }
    fun invoke(tool: String, next: Int = 0) {
        if (busy) return
        val current = revision
        val arguments = buildJsonObject {
            put("workspace_id", workspace); put("path", path)
            hash?.let { put("expected_sha256", it) }
            if (tool == "workspace_binary_query") { put("kind", kind); put("query", query); put("offset", next); put("limit", 100) }
            if (tool in listOf("workspace_binary_address", "workspace_binary_disassemble")) {
                put("value", value); put("from", if (fromVirtual) "virtual_address" else "file_offset"); put("count", 64)
            }
        }
        busy = true; error = null
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { Json.parseToJsonElement(app.binaryTools.executeTool(tool, arguments)).jsonObject }
                if (current == revision) {
                    result = parsed
                    if (parsed["is_error"] == JsonPrimitive(true)) error = parsed["message"]?.jsonPrimitive?.content
                    else hash = parsed["sha256"]?.jsonPrimitive?.content
                }
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; if (current == revision) error = failure.message }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { TextButton({ navigator.pop() }) { Text("返回") }; Text("ELF 静态分析", fontSize = 23.sp) }
        Text("只读分析工作区文件 · ELF64 / ARM64 · 最大 64 MiB", fontSize = 12.sp)
        Row {
            TextButton({ workspaceMenu = true }, enabled = !busy) { Text(workspaces.firstOrNull { it.id == workspace }?.name ?: "选择工作区") }
            TextButton({ navigator.push(com.kernelai.app.ui.navigation.Route.Workspace) }) { Text("管理文件夹") }
        }
        DropdownMenu(workspaceMenu, { workspaceMenu = false }) {
            workspaces.filter { it.enabled }.forEach { w -> DropdownMenuItem({ Text(w.name) }, { workspace = w.id; workspaceMenu = false; invalidate() }) }
        }
        TextField(path, { path = it; invalidate() }, Modifier.fillMaxWidth().testTag("binary-path"), label = "ELF 相对路径，例如 lib/libsample.so", singleLine = true, enabled = !busy)
        Row {
            TextButton({ hash = null; invoke("workspace_binary_info") }, enabled = !busy && path.isNotBlank()) { Text("解析文件") }
            TextButton({ kindMenu = true }, enabled = !busy) { Text("查询：$kind") }
            TextButton({ invoke("workspace_binary_query") }, enabled = !busy && path.isNotBlank()) { Text("查询") }
        }
        DropdownMenu(kindMenu, { kindMenu = false }) {
            listOf("sections", "segments", "symbols", "imports", "exports", "functions", "strings").forEach { k ->
                DropdownMenuItem({ Text(k) }, { kind = k; kindMenu = false; result = null })
            }
        }
        TextField(query, { query = it.take(256); result = null }, label = "名称 / 字符串筛选", singleLine = true, enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(value, { value = it }, Modifier.weight(1f).testTag("binary-address"), label = if (fromVirtual) "ELF 虚拟地址" else "文件偏移", singleLine = true, enabled = !busy)
            TextButton({ fromVirtual = !fromVirtual }, enabled = !busy) { Text(if (fromVirtual) "切换偏移" else "切换地址") }
        }
        Row {
            TextButton({ invoke("workspace_binary_address") }, enabled = !busy && path.isNotBlank()) { Text("转换地址") }
            TextButton({ invoke("workspace_binary_disassemble") }, enabled = !busy && path.isNotBlank()) { Text("反汇编") }
            result?.get("next_offset")?.jsonPrimitive?.intOrNull?.let { next -> TextButton({ invoke("workspace_binary_query", next) }, enabled = !busy) { Text("下一页") } }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        GlassCard(Modifier.heightIn(min = 240.dp, max = 560.dp).fillMaxWidth()) { SelectionContainer {
            Text(result?.let { Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), it) } ?: "选择已授权且开启 AI 访问的文件夹，填写 ELF 文件路径后解析。", Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("binary-result"), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        } }
    }
}
