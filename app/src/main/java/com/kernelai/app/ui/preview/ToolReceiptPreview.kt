package com.kernelai.app.ui.preview

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ui.components.TextButton
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import java.io.File

/** Every receipt page remains accessible, including the tail of multi-MiB results. */
@Composable
internal fun ToolReceiptPreview(file: File, modifier: Modifier, close: () -> Unit) {
    val store = KernelAiApplication.instance.toolResultStore
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offset by remember(file) { mutableIntStateOf(0) }
    var page by remember(file) { mutableStateOf<JsonObject?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { try {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("目标文件无法写入") }
        } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "导出失败：${failure.message}" } }
    }
    LaunchedEffect(file, offset) {
        page = null
        try { page = withContext(Dispatchers.IO) { store.page(file.nameWithoutExtension, offset) } }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message }
    }
    Column(modifier.fillMaxWidth().testTag("full-tool-receipt")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { offset = maxOf(0, offset - 12000) }, enabled = offset > 0) { Text("上一段") }
            TextButton(onClick = { page?.get("next_offset")?.jsonPrimitive?.intOrNull?.let { offset = it } }, enabled = page?.get("has_more") == JsonPrimitive(true)) { Text("下一段") }
            TextButton(onClick = { export.launch("工具完整结果-${file.name}") }) { Text("导出完整原文") }
        }
        TextButton(onClick = { confirmRemove = true }) { Text("删除此归档", fontSize = 12.sp) }
        page?.let { value ->
            Text("字符 ${value["offset"]}–${value["next_offset"]} / ${value["total_chars"]} · 原文件完整保留", Modifier.padding(12.dp), fontSize = 11.sp)
            SelectionContainer(Modifier.weight(1f)) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                item { Text(value["text"]!!.jsonPrimitive.content, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp) }
            } }
        } ?: CircularProgressIndicator()
        error?.let { Text(it, Modifier.padding(12.dp), fontSize = 12.sp) }
    }
    if (confirmRemove) com.kernelai.app.ui.screen.chat.GlassDialog(true, { confirmRemove = false }, title = "删除完整结果？") {
        Column {
            Text("请先导出需要保留的原文。聊天中的分页摘要保留，删除后 AI 和界面都不能继续读取此归档。", fontSize = 13.sp)
            Row {
                TextButton(onClick = { confirmRemove = false }) { Text("取消") }
                TextButton(onClick = { scope.launch {
                    try { withContext(Dispatchers.IO) { store.remove(file.nameWithoutExtension) }; close() }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message; confirmRemove = false }
                } }) { Text("删除") }
            }
        }
    }
}
