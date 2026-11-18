package com.kernelai.app.ui.screen.chat

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer

@Composable
internal fun executionPulse(active: Boolean): Modifier {
    if (!active || !com.kernelai.app.ui.theme.LocalUiMotionEnabled.current) return Modifier
    val transition = rememberInfiniteTransition(label = "tool execution")
    val alpha by transition.animateFloat(.45f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "execution text glow")
    return Modifier.graphicsLayer { this.alpha = alpha }
}

/** Smoothly drain network bursts. Completed history is shown immediately and never replays. */
@Composable
internal fun progressiveMarkdown(source: String, streaming: Boolean): PresentedMarkdown {
    val animate = com.kernelai.app.ui.theme.LocalUiMotionEnabled.current
    val animatedMessage = remember { streaming }
    var visible by remember { mutableStateOf(if (streaming && animate) "" else source) }
    val latestSource by rememberUpdatedState(source)
    val latestAnimate by rememberUpdatedState(animate)
    LaunchedEffect(Unit) {
        while (isActive) {
            val target = latestSource
            if (target == visible) {
                snapshotFlow { latestSource to latestAnimate }.first { it.first != visible || !it.second }
                if (!latestAnimate) { visible = latestSource; snapshotFlow { latestSource }.first { it != visible } }
            } else if (!animatedMessage || !latestAnimate || !target.startsWith(visible)) visible = target
            else {
                withFrameNanos { }
                val end = nextRevealEnd(visible.length, target)
                visible = target.substring(0, end)
                delay(24)
            }
        }
    }
    var settling by remember { mutableStateOf(streaming && animate) }
    val pending = streaming || visible != source
    LaunchedEffect(pending, animate) {
        if (pending && animate) settling = true
        else { if (animate) delay(320); settling = false }
    }
    return PresentedMarkdown(if (animate) visible else source, animate && (pending || settling))
}

internal data class PresentedMarkdown(val text: String, val active: Boolean)

/** Code-point-safe cut: an emoji never appears as a replacement character between frames. */
internal fun nextRevealEnd(previous: Int, target: String): Int {
    var end = minOf(target.length, previous + ((target.length - previous) / 24).coerceIn(2, 64))
    if (end < target.length && end > 0 && target[end - 1].isHighSurrogate() && target[end].isLowSurrogate()) end++
    return end
}

/** Animate drawing, not SpanStyle/layout: only the new glyph ranges blur and fade. */
@Composable
internal fun streamingInk(text: AnnotatedString, streaming: Boolean,
    layout: () -> androidx.compose.ui.text.TextLayoutResult?): Modifier {
    val motion = com.kernelai.app.ui.theme.LocalUiMotionEnabled.current
    val hardware = androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    if (!streaming || !motion) return Modifier
    data class Run(val start: Int, val end: Int, val born: Long)
    var previous by remember { mutableStateOf("") }
    var runs by remember { mutableStateOf(emptyList<Run>()) }
    var time by remember { mutableLongStateOf(System.nanoTime()) }
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    LaunchedEffect(text.text) {
        val now = System.nanoTime()
        var common = 0
        while (common < minOf(previous.length, text.length) && previous[common] == text.text[common]) common++
        runs = runs.filter { now - it.born < 320_000_000L && it.end <= common }.takeLast(24) +
            if (text.length > common) listOf(Run(maxOf(common, text.length - 800), text.length, now)) else emptyList()
        previous = text.text
        time = now
        while (isActive && runs.any { time - it.born < 320_000_000L }) withFrameNanos { time = System.nanoTime() }
        runs = emptyList()
    }
    return Modifier.drawWithContent {
        val measured = layout()
        val fresh = runs.filter { it.end <= text.length && time - it.born < 320_000_000L }
        // Do not allocate a filtered GPU texture for extremely tall paragraphs or software canvases.
        if (measured == null || measured.layoutInput.text.text != text.text || fresh.isEmpty() || !hardware || size.height > 4096 || size.width > 4096 || android.os.Build.VERSION.SDK_INT < 31) {
            drawContent()
        } else {
            val path = androidx.compose.ui.graphics.Path()
            fresh.forEach { path.addPath(measured.getPathForRange(it.start, it.end)) }
            val progress = ((time - fresh.last().born) / 320_000_000f).coerceIn(.04f, 1f)
            layer.record { this@drawWithContent.drawContent() }
            layer.alpha = progress
            val radius = (1f - progress) * 4.5f
            layer.renderEffect = if (radius > .1f) androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal) else null
            clipPath(path, androidx.compose.ui.graphics.ClipOp.Difference) { this@drawWithContent.drawContent() }
            clipPath(path) { drawLayer(layer) }
        }
    }
}
