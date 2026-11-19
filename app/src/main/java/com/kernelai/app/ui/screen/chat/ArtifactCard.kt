package com.kernelai.app.ui.screen.chat

import com.kernelai.app.ui.components.GlassCard as Card

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.kernelai.app.ui.components.TextButton
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kernelai.app.ui.screen.chat.GlassDialog as WindowDialog
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class ArtifactLink(val title: String, val url: String, val extension: String, val inlineImage: Boolean = false)

internal object ArtifactLinks {
    private val extensions = setOf("html", "htm", "svg", "png", "jpg", "jpeg", "webp", "gif", "apk", "aab", "zip", "7z", "tar", "gz", "pdf", "docx", "xlsx", "pptx", "mp4", "mp3", "wav", "json", "csv", "txt", "md", "ko", "so", "bin", "dex", "xml", "yaml", "yml", "c", "cpp", "h", "kt", "java", "py", "js", "ts", "xz", "webm", "mkv", "mov", "m4a", "aac", "ogg", "opus", "flac", "amr", "log", "css", "toml", "ini")
    fun from(blocks: List<MdBlock>): List<ArtifactLink> {
        val result = linkedMapOf<String, ArtifactLink>()
        fun add(url: String?, label: List<MdInline>, image: Boolean = false) {
            if (result.size < 16 && url != null) runCatching {
                val uri = URI(url)
                if (uri.scheme?.lowercase() in setOf("https", "http") && uri.host != null) {
                    val title = label.filterIsInstance<MdInline.Text>().joinToString("") { it.value }.ifBlank { uri.path.substringAfterLast('/').ifBlank { "产物" } }.take(160)
                    val extension = uri.path.substringAfterLast('.', "").lowercase().takeIf { it in extensions }
                        ?: title.substringAfterLast('.', "").lowercase().takeIf { it in extensions } ?: if (image) "image" else ""
                    if (extension.isNotEmpty()) result[url + if (image) "#inline-image" else "#artifact"] = ArtifactLink(title, url, extension, image)
                }
            }
        }
        fun inline(items: List<MdInline>) { items.forEach { item -> when (item) {
            is MdInline.Link -> { add(item.destination, item.label); inline(item.label) }
            is MdInline.Image -> add(item.destination, item.alt, true)
            is MdInline.Styled -> inline(item.children)
            else -> Unit
        } } }
        fun scan(values: List<MdBlock>) { values.forEach { block -> when (block) {
            is MdBlock.Paragraph -> inline(block.text)
            is MdBlock.Quote -> scan(block.blocks)
            is MdBlock.ListBlock -> block.items.forEach { scan(it.blocks) }
            is MdBlock.Table -> block.rows.forEach { row -> row.forEach { inline(it.text) } }
            else -> Unit
        } } }
        scan(blocks)
        return result.values.toList()
    }

}

@Composable
internal fun RemoteArtifactCard(artifact: ArtifactLink) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = com.kernelai.app.ui.preview.PreviewSource.kind(artifact.url, artifact.extension)
    val previewable = kind != com.kernelai.app.ui.preview.PreviewKind.WEB || artifact.extension in setOf("html", "htm", "svg")
    var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<File?>(ArtifactFiles.cached(context.filesDir, artifact).takeIf { it.isFile && it.length() > 0 }) }
    fun open() = com.kernelai.app.ui.preview.ArtifactPreview.open(context, artifact.url, artifact.title, kind)
    Card(Modifier.fillMaxWidth().testTag("artifact-${artifact.url}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(artifact.extension.uppercase() + "  ·  产物", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
            Text(artifact.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            when (kind) {
                com.kernelai.app.ui.preview.PreviewKind.IMAGE -> com.kernelai.app.ui.preview.RemoteImagePreview(artifact.url, artifact.title)
                com.kernelai.app.ui.preview.PreviewKind.VIDEO -> com.kernelai.app.ui.preview.VideoThumbnail(artifact.url, artifact.title, onOpen = ::open)
                com.kernelai.app.ui.preview.PreviewKind.AUDIO -> com.kernelai.app.ui.preview.MediaPlayerPanel(artifact.url, audioOnly = true)
                else -> Text(runCatching { URI(artifact.url).host }.getOrDefault(""), fontSize = 12.sp)
            }
            status?.let { Text(it, fontSize = 12.sp) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                if (previewable) TextButton(onClick = ::open) { Text(when (kind) {
                    com.kernelai.app.ui.preview.PreviewKind.IMAGE -> "放大预览"
                    com.kernelai.app.ui.preview.PreviewKind.VIDEO, com.kernelai.app.ui.preview.PreviewKind.AUDIO -> "全屏播放"
                    com.kernelai.app.ui.preview.PreviewKind.MARKDOWN -> "全屏 Markdown"
                    com.kernelai.app.ui.preview.PreviewKind.TEXT -> "查看文本"
                    else -> "App 内打开"
                }) }
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = saved ?: ArtifactFiles.download(context.filesDir, artifact).also { saved = it }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(artifact.extension) ?: "application/octet-stream")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开产物"))
                        } catch (failure: Exception) { if (failure is CancellationException) throw failure; status = "打开失败：${failure.message}" }
                        finally { busy = false }
                    }
                }) { Text(if (saved == null) "下载 / 其他应用" else "其他应用打开") }
            }
        }
    }
}

@Composable
internal fun CodeArtifactCard(code: MdBlock.Code) {
    val language = code.language.lowercase()
    val extension = when (language) { "html", "htm" -> "html"; "javascript" -> "js"; "typescript" -> "ts"; "python" -> "py"; "markdown" -> "md"; else -> language.filter { it.isLetterOrDigit() }.take(12).ifBlank { "txt" } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf(false) }; var scripts by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${extension.uppercase()} · 代码产物", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
            Text("AI 产物.$extension", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("${code.text.toByteArray().size} 字节", fontSize = 12.sp)
            Row {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { ArtifactFiles.saveCode(context.filesDir, code.text, extension) }
                            val kind = if (extension in setOf("html", "svg")) com.kernelai.app.ui.preview.PreviewKind.LOCAL_HTML
                                else if (extension == "md") com.kernelai.app.ui.preview.PreviewKind.MARKDOWN else com.kernelai.app.ui.preview.PreviewKind.TEXT
                            com.kernelai.app.ui.preview.ArtifactPreview.open(context, file.path, "AI 产物.$extension", kind, local = true)
                        } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "预览失败：${failure.message}" }
                    }
                }) { Text("全屏预览") }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { ArtifactFiles.saveCode(context.filesDir, code.text, extension) }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "导出产物"))
                        } catch (e: Exception) { if (e is CancellationException) throw e; error = "导出失败：${e.message}" }
                    }
                }) { Text("导出 / 分享") }
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }

}

internal object ArtifactFiles {
    fun saveCode(root: File, code: String, extension: String): File {
        require(extension.matches(Regex("[a-z0-9]{1,12}")))
        val directory = File(root, "artifacts").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        return File(directory, "artifact-$hash.$extension").apply { if (!exists()) writeText(code) }
    }
    fun cached(root: File, artifact: ArtifactLink): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(artifact.url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return File(File(root, "artifacts"), "download-$hash.${artifact.extension}")
    }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    suspend fun download(root: File, artifact: ArtifactLink, maxBytes: Long? = null): File = kotlinx.coroutines.coroutineScope {
        com.kernelai.app.ui.preview.PreviewSource.http(artifact.url)
        val maximum = maxBytes ?: if (artifact.extension in com.kernelai.app.ui.preview.PreviewSource.images || artifact.extension == "svg") 16L * 1024 * 1024 else 256L * 1024 * 1024
        val directory = File(root, "artifacts").apply { mkdirs() }
        val destination = cached(root, artifact)
        if (destination.isFile && destination.length() > 0) {
            require(destination.length() <= maximum) { "文件超过预览上限，可保留原文件并选择其他打开方式" }
            return@coroutineScope destination
        }
        val call = http.newCall(Request.Builder().url(artifact.url).build())
        val file = File(directory, "partial-${java.util.UUID.randomUUID()}.tmp")
        val work = async(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("响应为空")
                    require(body.contentLength() <= maximum) { "文件超过 ${maximum / 1024 / 1024} MiB 上限" }
                    var size = 0L
                    body.byteStream().use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            size += count; require(size <= maximum) { "文件超过预览/下载上限" }; output.write(buffer, 0, count)
                        }
                    } }
                    require(size > 0) { "文件为空" }
                    java.nio.file.Files.move(file.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    destination
                }
            } finally { file.delete() }
        }
        try { work.await() } finally { call.cancel() }
    }
}
