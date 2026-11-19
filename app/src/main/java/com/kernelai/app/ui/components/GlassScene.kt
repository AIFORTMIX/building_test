package com.kernelai.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kernelai.app.ui.screen.chat.GlassSurface
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Content cards sample a separate background, never their own recursively blurred contents. */
internal val LocalPanelBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun GlassScene(content: @Composable () -> Unit) {
    val background = MiuixTheme.colorScheme.background
    val primary = MiuixTheme.colorScheme.primary
    val dark = background.luminance() < .4f
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            drawRect(background)
            drawRect(Brush.radialGradient(listOf(primary.copy(alpha = if (dark) .15f else .09f), Color.Transparent), Offset(size.width * .15f, size.height * .16f), size.maxDimension * .65f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF817AC9).copy(alpha = if (dark) .09f else .05f), Color.Transparent), Offset(size.width, size.height * .88f), size.maxDimension * .55f))
        }
        CompositionLocalProvider(LocalPanelBackdrop provides backdrop) { content() }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(LocalPanelBackdrop.current, modifier, RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth(), content = content) }
}

@Composable
fun FrostedSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(18.dp), color: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit) {
    GlassSurface(LocalPanelBackdrop.current, modifier, shape) {
        Box(Modifier.background(color.copy(alpha = minOf(color.alpha, .10f)), shape), content = content)
    }
}
