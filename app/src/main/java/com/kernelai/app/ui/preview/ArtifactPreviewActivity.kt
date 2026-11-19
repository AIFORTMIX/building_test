package com.kernelai.app.ui.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.screen.chat.*
import com.kernelai.app.ui.theme.KernelAiTheme
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

internal object ArtifactPreview {
    fun open(context: Context, source: String, title: String, kind: PreviewKind = PreviewSource.kind(source), local: Boolean = false) {
        if (local) PreviewSource.local(context.filesDir, source) else PreviewSource.http(source)
        val resolvedKind = if (local && kind == PreviewKind.WEB && File(source).extension.lowercase() in setOf("html", "htm", "svg")) PreviewKind.LOCAL_HTML else kind
        context.startActivity(Intent(context, ArtifactPreviewActivity::class.java).putExtra("source", source)
            .putExtra("title", title.take(160)).putExtra("kind", resolvedKind.name).putExtra("local", local))
    }
}

class ArtifactPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val source = intent.getStringExtra("source").orEmpty()
        val local = intent.getBooleanExtra("local", false)
        val kind = runCatching { PreviewKind.valueOf(intent.getStringExtra("kind").orEmpty()) }.getOrDefault(PreviewKind.WEB)
        val title = intent.getStringExtra("title").orEmpty().take(160)
        val valid = runCatching { if (local) PreviewSource.local(filesDir, source) else PreviewSource.http(source) }.isSuccess
        if (!valid) { finish(); return }
        setContent { KernelAiTheme { PreviewScreen(source, title, kind, local, ::finish) } }
    }
}

@Composable
private fun PreviewScreen(source: String, title: String, kind: PreviewKind, local: Boolean, close: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var back by remember { mutableStateOf(close) }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { back() }) { Text("返回") }
            Text(title.ifBlank { "产物预览" }, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (kind == PreviewKind.WEB && !local) {
            WebPreview(source, null, Modifier.weight(1f), onBack = { web -> back = { if (runCatching { web.canGoBack() }.getOrDefault(false)) web.goBack() else close() } })
        } else if (kind == PreviewKind.VIDEO || kind == PreviewKind.AUDIO) {
            MediaPreviewActivityContent(if (local) android.net.Uri.fromFile(File(source)).toString() else source, kind == PreviewKind.AUDIO, Modifier.weight(1f))
        } else {
            var file by remember(source) { mutableStateOf<File?>(if (local) File(source) else null) }
            var error by remember(source) { mutableStateOf<String?>(null) }
            LaunchedEffect(source) {
                if (!local) try {
                    val extension = when (kind) { PreviewKind.IMAGE -> "image"; PreviewKind.MARKDOWN -> "md"; else -> "txt" }
                    file = withContext(Dispatchers.IO) { ArtifactFiles.download(context.filesDir, ArtifactLink(title, source, extension),
                        if (kind == PreviewKind.IMAGE) 16L * 1024 * 1024 else 2L * 1024 * 1024) }
                } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "加载失败：${failure.message}" }
            }
            when {
                error != null -> Text(error!!, Modifier.padding(20.dp), color = MiuixTheme.colorScheme.error)
                file == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                kind == PreviewKind.IMAGE -> ZoomImage(file!!, Modifier.weight(1f))
                kind == PreviewKind.LOCAL_HTML -> WebPreview(null, file, Modifier.weight(1f))
                local && file!!.parentFile?.canonicalFile == File(context.filesDir, "artifacts/tool-results").canonicalFile -> ToolReceiptPreview(file!!, Modifier.weight(1f), close)
                else -> TextFilePreview(file!!, kind == PreviewKind.MARKDOWN, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MediaPreviewActivityContent(source: String, audio: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(if (audio) 20.dp else 0.dp), contentAlignment = Alignment.Center) {
        MediaPlayerPanel(source, audio, autoplay = true, modifier = if (audio) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
    }
}

@Composable
internal fun ZoomImage(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { decodePreview(file, 2048) }.getOrNull() } }
    var scale by remember { mutableFloatStateOf(1f) }; var x by remember { mutableFloatStateOf(0f) }; var y by remember { mutableFloatStateOf(0f) }
    Box(modifier.fillMaxWidth().testTag("fullscreen-image").clipToBounds()
        .pointerInput(file) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f)
            x = (x + pan.x).coerceIn(-size.width * (scale - 1), size.width * (scale - 1)); y = (y + pan.y).coerceIn(-size.height * (scale - 1), size.height * (scale - 1)) } }
        .pointerInput(file) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; x = 0f; y = 0f }) }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "全屏图片，可双指缩放", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y }, contentScale = ContentScale.Fit) }
            ?: Text("图像无法解码或正在加载", fontSize = 13.sp)
    }
}

@Composable
private fun TextFilePreview(file: File, markdown: Boolean, modifier: Modifier) {
    var source by remember { mutableStateOf<String?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    LaunchedEffect(file) { try { source = withContext(Dispatchers.IO) {
        val limit = if (markdown) MarkdownDocument.MAX_SOURCE_CHARS else 2 * 1024 * 1024
        val bytes = file.inputStream().use { stream -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() < 2 * 1024 * 1024) { val n = stream.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }; out.toByteArray() }
        val text = bytes.toString(Charsets.UTF_8); truncated = file.length() > bytes.size || text.length > limit
        require(!text.contains('\u0000')) { "该文件不是可读文本" }; text.take(limit)
    } } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message } }
    Column(modifier.fillMaxWidth()) {
        if (truncated) Text("内容较长，已限制预览范围；原文件保留。", Modifier.padding(12.dp), fontSize = 12.sp)
        when {
            error != null -> Text("预览失败：$error", Modifier.padding(20.dp))
            source == null -> CircularProgressIndicator()
            markdown -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("fullscreen-markdown")) {
                item { MarkdownMessage(source!!, false, MiuixTheme.colorScheme.onBackground) }
            }
            else -> SelectionContainer { LazyColumn(Modifier.fillMaxSize().testTag("fullscreen-text"), contentPadding = PaddingValues(16.dp)) {
                items(source!!.chunked(4096)) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp) }
            } }
        }
    }
}

@Composable
private fun WebPreview(url: String?, file: File?, modifier: Modifier, onBack: (WebView) -> Unit = {}) {
    var scripts by remember { mutableStateOf(url != null) }
    var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    var html by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) { if (file != null) try {
        html = withContext(Dispatchers.IO) {
            val bytes = file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (out.size() <= 2 * 1024 * 1024) { val n = input.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 + 1 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                require(out.size() <= 2 * 1024 * 1024) { "本地网页超过 2 MiB 预览上限" }; out.toByteArray()
            }
            bytes.toString(Charsets.UTF_8)
        }
    } catch (failure: Exception) { if (failure is CancellationException) throw failure; loading = false; error = "预览失败：${failure.message}" } }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (url == null) "本地网页 · 网络与文件访问关闭" else runCatching { java.net.URI(url).host }.getOrDefault("网页"), Modifier.weight(1f), fontSize = 11.sp)
            Text("交互脚本", fontSize = 11.sp); Switch(scripts, { scripts = it })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(12.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.error) }
        if (url != null || html != null) key(scripts) {
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth().testTag("in-app-web"), factory = { context -> WebView(context).apply {
                settings.javaScriptEnabled = scripts; settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.blockNetworkLoads = url == null; settings.domStorageEnabled = url != null
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; settings.safeBrowsingEnabled = true
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webChromeClient = object : WebChromeClient() { override fun onPermissionRequest(request: PermissionRequest) { request.deny() } }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                        url == null || runCatching { PreviewSource.http(request!!.url.toString()) }.isFailure
                    override fun onPageFinished(view: WebView?, address: String?) { loading = false }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) { if (request?.isForMainFrame == true) { loading = false; error = "网页加载失败：${failure?.description}" } }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean { view.destroy(); loading = false; error = "网页渲染已停止，请返回后重试"; return true }
                }
                onBack(this)
                if (url != null) loadUrl(url) else loadDataWithBaseURL("https://artifact.invalid/", html!!, if (file?.extension == "svg") "image/svg+xml" else "text/html", "UTF-8", null)
            } }, onRelease = { it.stopLoading(); it.destroy() })
        }
    }
}
