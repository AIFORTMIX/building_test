package com.kernelai.app.ui.screen.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.ai.AiChatManager
import com.kernelai.app.data.ProviderCatalog
import com.kernelai.app.data.model.Attachment
import com.kernelai.app.ui.components.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ConversationList(sessions: List<AiChatManager.SessionInfo>, activeId: String, onClose: () -> Unit,
    onSelect: (String) -> Unit, onNew: () -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<AiChatManager.SessionInfo?>(null) }
    var title by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    WindowDialog(show = true, title = "对话列表", onDismissRequest = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(query, { query = it }, label = "搜索对话", singleLine = true)
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sessions.filter { it.title.contains(query, true) }, key = { it.id }) { session ->
                    Column(Modifier.fillMaxWidth().clickable { onClose(); onSelect(session.id) }.padding(vertical = 8.dp)) {
                        Text((if (session.id == activeId) "● " else "") + session.title, maxLines = 2, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${session.messageCount} 条记录", Modifier.weight(1f), fontSize = 11.sp)
                            TextButton(onClick = { rename = session; title = session.title }) { Text("改名", fontSize = 12.sp) }
                            TextButton(onClick = { deleting = session.id }) { Text("删除", fontSize = 12.sp) }
                        }
                    }
                }
            }
            Row { TextButton(onClick = onClose) { Text("关闭") }; TextButton(onClick = { onClose(); onNew() }) { Text("新建对话") } }
        }
    }
    rename?.let { session -> WindowDialog(show = true, title = "重命名对话", onDismissRequest = { rename = null }) {
        Column { TextField(title, { title = it.take(80) }, singleLine = true)
            Row { TextButton(onClick = { rename = null }) { Text("取消") }; TextButton(onClick = { onRename(session.id, title); rename = null }, enabled = title.isNotBlank()) { Text("保存") } }
        }
    } }
    deleting?.let { id -> WindowDialog(show = true, title = "删除这段对话？", summary = "消息、工具记录和草稿将从本地会话列表移除。", onDismissRequest = { deleting = null }) {
        Row { TextButton(onClick = { deleting = null }) { Text("取消") }; TextButton(onClick = { onDelete(id); deleting = null }) { Text("删除") } }
    } }
}

@Composable
internal fun ModelPicker(catalog: ProviderCatalog, onClose: () -> Unit, onSelect: (String, String) -> Unit, onManage: () -> Unit) {
    WindowDialog(show = true, title = "选择模型", onDismissRequest = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.heightIn(max = 430.dp)) {
                catalog.providers.forEach { provider ->
                    item(key = provider.id) { Text(provider.name, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(provider.models, key = { provider.id + ":" + it.id }) { model ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(provider.id, model.id); onClose() }.padding(vertical = 12.dp)) {
                            Text(model.name)
                            Text("${model.contextWindow} tokens · ${if (model.supportsImages) "图像与文本" else "文本"}", fontSize = 11.sp)
                        }
                    }
                }
            }
            if (catalog.providers.all { it.models.isEmpty() }) Text("先添加供应商与模型。")
            Row { TextButton(onClick = onClose) { Text("关闭") }; TextButton(onClick = { onClose(); onManage() }) { Text("管理供应商") } }
        }
    }
}

@Composable
internal fun AttachmentTile(attachment: Attachment, onRemove: (() -> Unit)? = null) {
    var preview by remember { mutableStateOf(false) }
    Card {
        Row(Modifier.fillMaxWidth().clickable { preview = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (attachment.isImage) AttachmentImage(attachment, Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(attachment.name, maxLines = 1, fontSize = 13.sp)
                Text("${if (attachment.isImage) "图像" else "文本文件"} · ${(attachment.size + 1023) / 1024} KiB", fontSize = 10.sp)
            }
            onRemove?.let { TextButton(onClick = it) { Text("移除", fontSize = 11.sp) } }
        }
    }
    if (preview) WindowDialog(show = true, title = attachment.name, onDismissRequest = { preview = false }) {
        Column(Modifier.heightIn(max = 520.dp)) {
            if (attachment.isImage) AttachmentImage(attachment, Modifier.fillMaxWidth().heightIn(max = 430.dp), targetSize = 2048)
            else SelectionContainer { Text(attachment.textContent.orEmpty().take(20000), Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), fontSize = 13.sp) }
            TextButton(onClick = { preview = false }) { Text("关闭预览") }
        }
    }
}

@Composable
private fun AttachmentImage(attachment: Attachment, modifier: Modifier, targetSize: Int = 256) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, attachment.id, targetSize) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(attachment.localPath, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 200_000_000L)
                options.inJustDecodeBounds = false; options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > targetSize || options.outHeight / options.inSampleSize > targetSize) options.inSampleSize *= 2
                BitmapFactory.decodeFile(attachment.localPath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, attachment.name, modifier) } ?: Text("图像", modifier)
}
