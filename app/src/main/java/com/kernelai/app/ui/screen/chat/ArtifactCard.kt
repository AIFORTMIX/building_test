package com.kernelai.app.ui.screen.chat

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.kernelai.app.ui.components.TextButton
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
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class ArtifactLink(val title: String, val url: String, val extension: String)

internal object ArtifactLinks {
    private val extensions = setOf("html", "htm", "svg", "png", "jpg", "jpeg", "webp", "gif", "apk", "aab", "zip", "7z", "tar", "gz", "pdf", "docx", "xlsx", "pptx", "mp4", "mp3", "wav", "json", "csv", "txt", "md", "ko", "so", "bin", "dex", "xml", "yaml", "yml", "c", "cpp", "h", "kt", "java", "py", "js", "ts", "xz", "webm")
    fun from(blocks: List<MdBlock>): List<ArtifactLink> {
        val result = linkedMapOf<String, ArtifactLink>()
        fun add(url: String?, label: List<MdInline>, image: Boolean = false) {
            if (result.size < 16 && url != null) runCatching {
                val uri = URI(url)
                if (uri.scheme?.lowercase() in setOf("https", "http") && uri.host != null) {
                    val title = label.filterIsInstance<MdInline.Text>().joinToString("") { it.value }.ifBlank { uri.path.substringAfterLast('/').ifBlank { "产物" } }.take(160)
                    val extension = uri.path.substringAfterLast('.', "").lowercase().takeIf { it in extensions }
                        ?: title.substringAfterLast('.', "").lowercase().takeIf { it in extensions } ?: if (image) "image" else ""
                    if (extension.isNotEmpty()) result[url] = ArtifactLink(title, url, extension)
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
    var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<File?>(ArtifactFiles.cached(context.filesDir, artifact).takeIf { it.isFile && it.length() > 0 }) }
    val imageArtifact = artifact.extension in setOf("image", "png", "jpg", "jpeg", "webp", "gif")
    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(artifact.extension) ?: "application/octet-stream"
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开产物")) }
            .onFailure { status = "没有可打开此文件的应用，可通过文件管理器查看" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(artifact.extension.uppercase() + "  ·  产物", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
            Text(artifact.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(runCatching { URI(artifact.url).host }.getOrDefault(""), fontSize = 12.sp)
            status?.let { Text(it, fontSize = 12.sp) }
            if (imageArtifact) saved?.let { file ->
                val attachment = remember(file.absolutePath) { com.kernelai.app.data.model.Attachment(id = file.name, name = artifact.title, mime = "image/*", size = file.length(), localPath = file.absolutePath) }
                AttachmentTile(attachment)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(artifact.url))) }.onFailure { status = "链接无法打开" }
                }) { Text("打开链接") }
                TextButton(enabled = !busy, onClick = {
                    saved?.let { if (!imageArtifact) share(it); return@TextButton }
                    busy = true
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) { ArtifactFiles.download(context.filesDir, artifact) }
                            saved = file; status = "已保存 · ${file.length() / 1024} KiB"; if (!imageArtifact) share(file)
                        } catch (e: Exception) { if (e is CancellationException) throw e; status = "下载失败：${e.message}" }
                        finally { busy = false }
                    }
                }) { Text(if (imageArtifact) { if (saved == null) "加载图像预览" else "图像已缓存" } else if (saved == null) "下载并打开" else "打开已下载产物") }
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
                if (extension == "html" || extension == "svg") TextButton(onClick = { preview = true }) { Text("预览") }
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
    if (preview) WindowDialog(show = true, title = "产物预览", onDismissRequest = { preview = false }) {
        Column {
            Text("本地预览 · 网络与文件访问关闭", fontSize = 12.sp)
            Row { Text("交互脚本", Modifier.weight(1f)); Switch(scripts, { scripts = it }) }
            key(scripts) {
                AndroidView(modifier = Modifier.fillMaxWidth().height(440.dp), factory = { viewContext -> WebView(viewContext).apply {
                    settings.javaScriptEnabled = scripts
                    settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
                    settings.domStorageEnabled = false; settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true }
                    loadDataWithBaseURL("https://artifact.invalid/", code.text, if (extension == "svg") "image/svg+xml" else "text/html", "UTF-8", null)
                } }, onRelease = { it.stopLoading(); it.destroy() })
            }
            TextButton(onClick = { preview = false }) { Text("关闭") }
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
    suspend fun download(root: File, artifact: ArtifactLink): File {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        val call = http.newCall(Request.Builder().url(artifact.url).build())
        val directory = File(root, "artifacts").apply { mkdirs() }
        val destination = cached(root, artifact)
        if (destination.isFile && destination.length() > 0) return destination
        val file = File(directory, "partial-${java.util.UUID.randomUUID()}.tmp")
        val maximum = if (artifact.extension in setOf("image", "png", "jpg", "jpeg", "webp", "gif")) 16L * 1024 * 1024 else 256L * 1024 * 1024
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
                        size += count; require(size <= maximum) { "文件超过 ${maximum / 1024 / 1024} MiB 上限" }; output.write(buffer, 0, count)
                    }
                } }
                require(size > 0) { "文件为空" }
                java.nio.file.Files.move(file.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return destination
            }
        } catch (e: Exception) { file.delete(); throw e }
        finally { call.cancel(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
