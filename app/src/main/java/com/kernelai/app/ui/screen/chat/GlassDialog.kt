package com.kernelai.app.ui.screen.chat

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import top.yukonga.miuix.kmp.basic.Text

/** Miuix content in a frosted window: dialogs cannot sample a sibling's window coordinates. */
@Composable
internal fun GlassDialog(show: Boolean, onDismissRequest: () -> Unit, title: String? = null,
    summary: String? = null, content: @Composable () -> Unit) {
    if (!show) return
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val blurSupported = Build.VERSION.SDK_INT >= 31 &&
            (view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true)
        DisposableEffect(window, blurSupported) {
            window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(.18f)
                if (Build.VERSION.SDK_INT >= 31 && blurSupported) {
                    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    attributes = attributes.apply { blurBehindRadius = 36 }
                }
            }
            onDispose { }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest),
            contentAlignment = Alignment.BottomCenter) {
            GlassSurface(null, Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(80.dp))
                .testTag("glass-dialog")
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
                RoundedCornerShape(GlassStyle.Panel), windowBlur = blurSupported) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    title?.let { Text(it, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                    summary?.let { Text(it, fontSize = 13.sp, color = chatForeground().copy(alpha = .7f)) }
                    content()
                }
            }
        }
    }
}
