package com.kernelai.app.ui.screen.chat

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kernelai.app.ui.LocalUiMode
import com.kernelai.app.ui.UiMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal object GlassStyle {
    val Panel = 24.dp
    val Capsule = 32.dp
    const val BlurRadius = 22f
    const val TintAlpha = .56f
}

@Composable
internal fun chatBackground(): Color = MiuixTheme.colorScheme.background

@Composable
internal fun chatForeground(): Color = MiuixTheme.colorScheme.onBackground

@Composable
internal fun glassSupported(): Boolean {
    val view = LocalView.current
    val lowRam = view.context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
    return Build.VERSION.SDK_INT >= 33 && view.isHardwareAccelerated && !lowRam
}

/** Capture and blur only the sibling backdrop. The input, icons and labels stay sharp. */
@Composable
internal fun GlassSurface(
    backdrop: LayerBackdrop? = LocalGlassBackdrop.current,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassStyle.Capsule),
    windowBlur: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = chatBackground().luminance() < .4f
    val supported = glassSupported() && backdrop != null
    val tint = if (dark) Color(0xFF24262D) else Color(0xFFF4F5F8)
    val blur = if (supported) Modifier.textureBlur(backdrop = backdrop, shape = shape, blurRadius = GlassStyle.BlurRadius) else Modifier
    Box(modifier.shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = .08f), spotColor = Color.Black.copy(alpha = .10f))
        .then(blur).clip(shape)
        .background(tint.copy(alpha = if (supported || windowBlur) GlassStyle.TintAlpha else .96f))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .26f else .95f),
            Color.White.copy(alpha = if (dark) .06f else .35f))), shape), content = content)
}
