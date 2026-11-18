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

@Composable
internal fun executionPulse(active: Boolean): Modifier {
    if (!active) return Modifier
    val transition = rememberInfiniteTransition(label = "tool execution")
    val alpha by transition.animateFloat(.45f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "execution text glow")
    return Modifier.graphicsLayer { this.alpha = alpha }
}

/** Only new spans fade; established text keeps its opacity, geometry and selection. */
@Composable
internal fun streamingText(text: AnnotatedString, streaming: Boolean, color: Color): AnnotatedString {
    if (!streaming) return text
    data class Run(val start: Int, val end: Int, val born: Long)
    var previous by remember { mutableStateOf("") }
    var runs by remember { mutableStateOf(emptyList<Run>()) }
    var time by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(text.text) {
        val now = System.nanoTime()
        var common = 0
        while (common < minOf(previous.length, text.length) && previous[common] == text.text[common]) common++
        runs = runs.filter { now - it.born < 240_000_000L && it.end <= common }.takeLast(24) +
            if (text.length > common) listOf(Run(maxOf(common, text.length - 800), text.length, now)) else emptyList()
        previous = text.text
        while (isActive && runs.any { time - it.born < 240_000_000L }) withFrameNanos { time = System.nanoTime() }
        runs = emptyList()
    }
    return buildAnnotatedString {
        append(text)
        runs.forEach { run ->
            if (run.end <= length) addStyle(SpanStyle(color = color.copy(alpha = ((time - run.born) / 240_000_000f).coerceIn(.15f, 1f))), run.start, run.end)
        }
    }
}
