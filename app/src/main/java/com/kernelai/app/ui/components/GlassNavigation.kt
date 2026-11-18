package com.kernelai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.ui.BottomNavDestination
import com.kernelai.app.ui.screen.chat.GlassSurface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GlassNavigation(backdrop: LayerBackdrop, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, vertical: Boolean = false) {
    GlassSurface(backdrop, modifier.testTag("app-navigation"), RoundedCornerShape(28.dp)) {
        val item: @Composable (Int, BottomNavDestination) -> Unit = { index, destination ->
            val active = selected == index
            val color = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground.copy(alpha = .58f)
            Column(Modifier.height(56.dp).widthIn(min = 64.dp)
                .background(if (active) color.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(22.dp))
                .testTag("nav-$index").semantics { this.selected = active; role = Role.Tab }
                .clickable { onSelect(index) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(destination.icon, destination.label, tint = color, modifier = Modifier.size(23.dp))
                Text(destination.label, color = color, fontSize = 10.sp)
            }
        }
        if (vertical) Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BottomNavDestination.entries.forEachIndexed { index, destination -> item(index, destination) }
        } else Row(Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BottomNavDestination.entries.forEachIndexed { index, destination -> item(index, destination) }
        }
    }
}
