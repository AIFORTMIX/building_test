package com.kernelai.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

/** Small composition helpers built entirely from Miuix controls. */
@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier, enabled, minHeight = 36.dp, minWidth = 0.dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(color = Color.Transparent, contentColor = MiuixTheme.colorScheme.primary), content = content)
}

@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier, strokeWidth: Dp = 2.dp, color: Color = MiuixTheme.colorScheme.primary, size: Dp = 20.dp) {
    top.yukonga.miuix.kmp.basic.CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth, size = size,
        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color))
}

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    top.yukonga.miuix.kmp.basic.Checkbox(if (checked) ToggleableState.On else ToggleableState.Off,
        onClick = onCheckedChange?.let { { it(!checked) } }, modifier = modifier)
}

@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick, modifier.semantics { this.selected = selected; role = Role.Checkbox }, shape = CircleShape,
        color = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = .13f) else MiuixTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { label() }
    }
}

@Composable
fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    WindowListPopup(show = expanded, onDismissRequest = onDismissRequest, enableWindowDim = false) {
        ListPopupColumn(content)
    }
}

@Composable
fun DropdownMenuItem(text: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(onClick, Modifier.fillMaxWidth(), color = Color.Transparent) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 15.dp)) { text() }
    }
}

@Composable
fun AlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit) {
    WindowDialog(show = true, onDismissRequest = onDismissRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            title(); text()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton(); confirmButton() }
        }
    }
}

@Composable
fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, singleLine: Boolean = false, isError: Boolean = false, readOnly: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null, visualTransformation: VisualTransformation = VisualTransformation.None) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label()
        TextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = singleLine, readOnly = readOnly, visualTransformation = visualTransformation)
        if (isError) supportingText?.invoke()
    }
}
