package com.kernelai.app.ui.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.ui.screen.chat.ArtifactFiles
import com.kernelai.app.ui.screen.chat.ArtifactLink
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

private val imageRequests = Semaphore(2)

internal fun decodePreview(file: File, limit: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 200_000_000L) { "图像尺寸无效或过大" }
    options.inSampleSize = 1
    while (options.outWidth / options.inSampleSize > limit || options.outHeight / options.inSampleSize > limit) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.path, options)
}

/** Fixed preview geometry prevents an arriving image from shifting the conversation. */
@Composable
internal fun RemoteImagePreview(url: String, title: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var file by remember(url) { mutableStateOf<File?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        try { PreviewSource.http(url); imageRequests.withPermit {
            val downloaded = withContext(Dispatchers.IO) { ArtifactFiles.download(context.filesDir, ArtifactLink(title, url, "image")) }
            file = downloaded
            bitmap = withContext(Dispatchers.IO) { decodePreview(downloaded, 768) } ?: error("未能解码图像")
        } } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "预览失败，点按查看详情" }
    }
    Box(modifier.fillMaxWidth().height(190.dp).background(MiuixTheme.colorScheme.onBackground.copy(alpha = .035f))
        .testTag("inline-image").semantics { contentDescription = "放大图片：$title"; stateDescription = if (bitmap != null) "已加载" else if (error != null) "加载失败" else "加载中" }
        .clickable {
            val saved = file
            if (saved != null && bitmap != null) ArtifactPreview.open(context, saved.path, title, PreviewKind.IMAGE, local = true)
            else ArtifactPreview.open(context, url, title, PreviewKind.IMAGE)
        }, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(error ?: "正在加载图片…", fontSize = 12.sp)
    }
}
