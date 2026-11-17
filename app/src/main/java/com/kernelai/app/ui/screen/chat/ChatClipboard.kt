package com.kernelai.app.ui.screen.chat

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

/** Clipboard service rejection (for example Binder size limits) must not terminate the chat UI. */
@Suppress("DEPRECATION")
@Composable
fun rememberChatCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) { { value ->
        runCatching { clipboard.setText(AnnotatedString(value)) }.onFailure {
            Toast.makeText(context, "复制失败，文本可能超过系统剪贴板限制；请分段选择复制", Toast.LENGTH_LONG).show()
        }
        Unit
    } }
}
