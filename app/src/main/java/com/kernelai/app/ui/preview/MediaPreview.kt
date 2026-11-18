package com.kernelai.app.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kernelai.app.ui.components.TextButton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object MediaEngine {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    val thumbnails = Semaphore(1)
    private var active: WeakReference<ExoPlayer>? = null
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun player(context: Context, source: String): ExoPlayer {
        val data = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(1000, 5000, 500, 1000)
            .setTargetBufferBytes(8 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build()
        return ExoPlayer.Builder(context).setLoadControl(load).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build().apply {
            setMediaItem(MediaItem.fromUri(source)); playWhenReady = false
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        }
    }
    fun activate(player: ExoPlayer) { active?.get()?.takeIf { it !== player }?.let { it.pause(); it.stop() }; active = WeakReference(player) }
}

@Composable
internal fun VideoThumbnail(source: String, title: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var player by remember(source) { mutableStateOf<ExoPlayer?>(null) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
        val cached = File(context.cacheDir, "video-first-$hash.jpg")
        bitmap = withContext(Dispatchers.IO) { if (cached.isFile) android.graphics.BitmapFactory.decodeFile(cached.path) else null }
        if (bitmap != null) return@LaunchedEffect
        MediaEngine.thumbnails.withPermit { withContext(Dispatchers.Main.immediate) {
            val engine = MediaEngine.player(context, source)
            val first = CompletableDeferred<Unit>()
            engine.volume = 0f
            engine.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { first.complete(Unit) }
                override fun onPlayerError(error: PlaybackException) { first.completeExceptionally(error) }
            })
            try {
                player = engine; engine.prepare()
                withTimeout(15000) { first.await() }; delay(40)
                val size = engine.videoSize
                val scale = minOf(640f / size.width.coerceAtLeast(1), 640f / size.height.coerceAtLeast(1), 1f)
                val frame = texture?.getBitmap((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
                    ?: error("未获得视频首帧")
                bitmap = frame
                withContext(Dispatchers.IO) { cached.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "首帧加载失败，点按尝试播放" }
            finally { player = null; engine.release() }
        } }
    }
    Box(modifier.fillMaxWidth().height(190.dp).background(Color(0xFF16181D)).clickable(onClick = onOpen).testTag("video-preview"), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), "视频首帧：$title", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else AndroidView(modifier = Modifier.fillMaxSize(), factory = { TextureView(it).also { view -> texture = view } }, update = { texture = it; player?.setVideoTextureView(it) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▶", color = Color.White, fontSize = 30.sp)
            Text(error ?: "点按播放", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MediaPlayerPanel(source: String, audioOnly: Boolean, autoplay: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(source) { MediaEngine.player(context, source) }
    var playing by remember { mutableStateOf(false) }; var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0) }; var duration by remember { mutableLongStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }; var prepared by remember { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    fun play() { error = null; if (!prepared || player.playbackState == Player.STATE_IDLE) { player.prepare(); prepared = true }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); MediaEngine.activate(player); player.play() }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { playing = value }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING; duration = player.duration.coerceAtLeast(0) }
            override fun onVideoSizeChanged(size: VideoSize) { if (size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height }
            override fun onPlayerError(failure: PlaybackException) { error = "播放失败：${failure.errorCodeName}"; buffering = false }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { player.pause(); player.stop(); prepared = false } }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, lifecycle) { withContext(Dispatchers.Main.immediate) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) {
        position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0); delay(250)
    } } } }
    LaunchedEffect(autoplay) { if (autoplay) play() }
    Column(modifier.fillMaxWidth().testTag("media-player").semantics {
        stateDescription = "${if (playing) "播放中" else "已暂停"} · ${mediaTime(position)} / ${mediaTime(duration)}"
    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!audioOnly) Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { TextureView(it).also(player::setVideoTextureView) }, update = { view ->
                view.post { if (view.width > 0 && view.height > 0 && ratio > 0) {
                    val boxRatio = view.width.toFloat() / view.height
                    view.setTransform(Matrix().apply { setScale(if (ratio < boxRatio) ratio / boxRatio else 1f,
                        if (ratio > boxRatio) boxRatio / ratio else 1f, view.width / 2f, view.height / 2f) })
                } }
            })
        }
        if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.error) }
        Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { if (duration > 0) { player.seekTo((it * duration).toLong()); position = player.currentPosition } }, enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().testTag("media-seek"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (playing) player.pause() else play() }, modifier = Modifier.testTag("media-play")) { Text(if (playing) "暂停" else "播放") }
            TextButton(onClick = { player.pause(); player.seekTo(0); position = 0 }) { Text("停止") }
            Text("${mediaTime(position)} / ${mediaTime(duration)}", Modifier.weight(1f), fontSize = 11.sp)
        }
    }
}

private fun mediaTime(ms: Long): String = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
