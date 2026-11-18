package com.kernelai.app.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.data.WorkspaceEntry
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.components.OutlinedTextField
import com.kernelai.app.ui.preview.ArtifactPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WorkspaceScreen(close: () -> Unit) {
    val repository = KernelAiApplication.instance.workspaceRepository
    val workspaces by repository.workspaces.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var selected by remember { mutableStateOf("default") }; var path by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }; var more by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }; var loading by remember { mutableStateOf(false) }
    fun operation(block: suspend () -> Unit) { scope.launch { try { error = null; block(); revision++ } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message } } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) operation { repository.add(name.ifBlank { "系统文件夹" }, uri); name = ""; selected = repository.workspaces.value.last().id; path = "" } }
    LaunchedEffect(Unit) { try { repository.load() } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message } }
    LaunchedEffect(selected, path, revision, workspaces) {
        loading = true; entries = emptyList()
        try { val result = repository.entries(selected, path); entries = result.first; more = result.second }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
        finally { loading = false }
    }
    BackHandler { if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") else close() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row { TextButton(onClick = close) { Text("返回") }; Text("工作区", Modifier.padding(8.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold) } }
        item { Text("AI 只能访问已开启的工作区。默认目录保存在应用内；系统文件夹需要你主动选择授权。覆盖文件前会校验内容版本。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        items(workspaces, key = { it.id }) { workspace ->
            Card { Row(Modifier.fillMaxWidth().clickable { selected = workspace.id; path = ""; error = null }.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text((if (workspace.id == selected) "● " else "") + workspace.name, fontWeight = FontWeight.Medium)
                    Text(if (workspace.treeUri == null) "应用内文件夹" else "系统授权文件夹", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                Text("AI", Modifier.padding(horizontal = 8.dp), fontSize = 12.sp); Switch(workspace.enabled, { enabled -> operation { repository.enable(workspace.id, enabled) } })
            } }
        }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, label = { Text("新工作区名称") }, modifier = Modifier.fillMaxWidth())
            Row { TextButton(onClick = { operation { repository.add(name.ifBlank { "新工作区" }); name = ""; selected = repository.workspaces.value.last().id; path = "" } }) { Text("创建应用内目录") }
                TextButton(onClick = { folder.launch(null) }) { Text("选择系统文件夹") } }
        } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") }) { Text(if (path.isEmpty()) "根目录" else "↑ ${path.takeLast(70)}") }
            TextButton(onClick = { error = null; revision++ }) { Text("刷新") }
        } }
        error?.let { item { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 13.sp) } }
        if (loading) item { CircularProgressIndicator() }
        else if (entries.isEmpty()) item { Text("文件夹为空。可在对话中让 AI 创建文件。", Modifier.padding(16.dp), fontSize = 13.sp) }
        items(entries.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name }), key = { it.name }) { entry ->
            Card { Column(Modifier.fillMaxWidth().clickable {
                val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                if (entry.directory) { path = child; error = null }
                else operation { val file = repository.preview(selected, child); ArtifactPreview.open(context, file.absolutePath, entry.name, local = true) }
            }.padding(16.dp)) { Text((if (entry.directory) "▸ " else "") + entry.name); if (!entry.directory) Text("${entry.size} 字节 · 点击预览文本", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
        }
        if (more) item { Text("仅列出前 500 项，请使用更小的目录。", fontSize = 12.sp) }
    }
}
