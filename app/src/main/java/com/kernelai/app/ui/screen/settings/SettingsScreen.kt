package com.kernelai.app.ui.screen.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kernelai.app.BuildConfig
import com.kernelai.app.data.model.*
import com.kernelai.app.ui.components.TextButton
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(scrollState: ScrollState = rememberScrollState(), connectionStatus: ConnectionStatus = ConnectionStatus(),
    appSettings: AppSettings = AppSettings(), onManageModels: () -> Unit = {}, onOpenAudit: () -> Unit = {}, onThemeModeChange: (ThemeMode) -> Unit = {},
    onReconnectDriver: () -> Unit = {}, onToggleMcp: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型与供应商", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("添加多个 OpenAI 兼容供应商，获取模型列表，配置上下文和图像能力。", fontSize = 13.sp)
            TextButton(onClick = onManageModels) { Text("管理供应商与模型 →") }
        } }
        Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设备与内置 MCP 宿主", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("内核驱动 · ${if (connectionStatus.driverConnected) "已连接" else "未连接"}")
            TextButton(onClick = onReconnectDriver) { Text("重新连接驱动") }
            HorizontalDivider()
            Text("内置 MCP · ${if (connectionStatus.mcpServerRunning) "运行中，端口 ${connectionStatus.mcpPort}" else "未启动"}")
            TextButton(onClick = onToggleMcp) { Text(if (connectionStatus.mcpServerRunning) "停止 MCP" else "启动 MCP") }
            connectionStatus.errorMessage?.let { Text(it, color = MiuixTheme.colorScheme.error) }
        } }
        McpAccessPanel(onOpenAudit)
        Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("外观", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Miuix · 玻璃悬浮组件", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { onThemeModeChange(mode) }.padding(vertical = 12.dp)) {
                    Text(mode.displayName, Modifier.weight(1f))
                    if (mode == appSettings.themeMode) Text("✓", color = MiuixTheme.colorScheme.primary)
                }
            }
        } }
        Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("轻微 MCP · ${BuildConfig.VERSION_NAME}")
            Text("会话在本机保存 · MCP Streamable HTTP 2025-03-26", fontSize = 12.sp)
        } }
    }
}
