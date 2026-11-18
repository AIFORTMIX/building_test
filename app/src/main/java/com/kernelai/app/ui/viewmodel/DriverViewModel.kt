package com.kernelai.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.data.model.ConnectionStatus
import com.kernelai.app.data.model.AppSettings
import com.kernelai.app.data.model.AiConfig
import com.kernelai.app.data.model.DriverSettings
import com.kernelai.app.data.model.ThemeMode
import com.kernelai.app.data.model.UiStyle
import com.kernelai.app.ui.UiMode
import com.kernelai.app.ui.theme.KernelAiColorMode
import com.kernelai.app.ui.theme.KernelAiThemeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 驱动 ViewModel - 管理驱动连接状态、MCP 服务器状态、全局驱动操作
 */
class DriverViewModel : ViewModel() {

    private val _connectionStatus = MutableStateFlow(ConnectionStatus())
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing: StateFlow<Boolean> = _isInitializing.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private var initialized = false
    init {
        viewModelScope.launch { KernelAiApplication.instance.mcpRuntime.state.collect { state ->
            _connectionStatus.value = _connectionStatus.value.copy(mcpServerRunning = state.running, mcpPort = state.port.takeIf { it > 0 } ?: 8080)
        } }
        viewModelScope.launch {
            KernelAiApplication.instance.aiConnected.collect { connected ->
                _connectionStatus.value = _connectionStatus.value.copy(aiConnected = connected)
            }
        }
    }

    /**
     * 初始化驱动连接和 MCP 服务器
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            _isInitializing.value = true
            addLog(LogEntry.LogLevel.INFO, LogCategory.SYSTEM, "正在初始化轻微 MCP...")

            try {
                // 加载设置
                loadSettings(context)

                val driverSettings = _appSettings.value.driverSettings
                _connectionStatus.value = _connectionStatus.value.copy(mcpPort = driverSettings.mcpPort)
                if (driverSettings.autoStartMcp) startMcpServer()
                if (driverSettings.autoConnect) connectDriver()

                addLog(LogEntry.LogLevel.INFO, LogCategory.AI, "AI 工具调用已连接本机 MCP 端点")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog(LogEntry.LogLevel.ERROR, LogCategory.SYSTEM, "初始化失败: ${e.message}")
                _connectionStatus.value = _connectionStatus.value.copy(
                    errorMessage = e.message
                )
            } finally {
                _isInitializing.value = false
            }
        }
    }

    /**
     * 重新连接驱动
     */
    fun reconnectDriver() {
        viewModelScope.launch { connectDriver() }
    }

    fun toggleMcpServer() {
        viewModelScope.launch {
            val runtime = KernelAiApplication.instance.mcpRuntime
            if (runtime.isRunning) {
                withContext(Dispatchers.IO) { runtime.stop() }
                KernelAiApplication.instance.stopService(android.content.Intent(KernelAiApplication.instance, com.kernelai.app.mcp.McpHostService::class.java))
                _connectionStatus.value = _connectionStatus.value.copy(mcpServerRunning = false)
                addLog(LogEntry.LogLevel.INFO, LogCategory.MCP, "MCP 服务已停止")
            } else startMcpServer()
        }
    }

    /**
     * 更新 AI 配置
     */
    /**
     * 更新主题设置
     */
    fun updateThemeMode(mode: ThemeMode) {
        KernelAiThemeController.saveColorMode(KernelAiApplication.instance, KernelAiColorMode.fromThemeMode(mode))
        _appSettings.value = _appSettings.value.copy(themeMode = mode)
    }

    /**
     * 更新 UI 风格
     */
    fun updateUiStyle(style: UiStyle) {
        KernelAiThemeController.saveUiMode(KernelAiApplication.instance, UiMode.fromValue(style.value))
        _appSettings.value = _appSettings.value.copy(uiStyle = style)
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logEntries.value = emptyList()
    }

    /**
     * 添加日志条目
     */
    fun addLog(entry: LogEntry) {
        _logEntries.value = (_logEntries.value + entry).takeLast(500)
    }

    private fun addLog(level: LogEntry.LogLevel, category: LogCategory, message: String) {
        addLog(LogEntry(level = level, category = category, message = message))
    }

    private suspend fun connectDriver() {
        addLog(LogEntry.LogLevel.INFO, LogCategory.DRIVER, "正在打开驱动设备节点")
        val connected = withContext(Dispatchers.IO) {
            KernelAiApplication.instance.connectDriver()
        }
        _connectionStatus.value = _connectionStatus.value.copy(driverConnected = connected)
        if (connected) {
            addLog(LogEntry.LogLevel.INFO, LogCategory.DRIVER, "驱动设备节点已打开")
        } else {
            addLog(LogEntry.LogLevel.WARN, LogCategory.DRIVER, KernelAiApplication.instance.deviceNode.lastError ?: "无法打开驱动设备节点")
        }
    }

    private suspend fun startMcpServer() {
        try {
            val runtime = KernelAiApplication.instance.mcpRuntime
            withContext(Dispatchers.IO) {
                try { runtime.start(_appSettings.value.driverSettings.mcpPort) }
                catch (error: Exception) {
                    if (generateSequence<Throwable>(error) { it.cause }.none { it is java.net.BindException }) throw error
                    runtime.start(0)
                }
            }
            _connectionStatus.value = _connectionStatus.value.copy(mcpServerRunning = runtime.isRunning, mcpPort = runtime.port)
            addLog(LogEntry.LogLevel.INFO, LogCategory.MCP, "本机 MCP 服务已启动，端口 ${runtime.port}")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _connectionStatus.value = _connectionStatus.value.copy(mcpServerRunning = false, errorMessage = error.message)
            addLog(LogEntry.LogLevel.ERROR, LogCategory.MCP, "MCP 启动失败: ${error.message}")
        }
    }

    private fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("kernel_ai_settings", Context.MODE_PRIVATE)
        _appSettings.value = AppSettings(
            aiConfig = AiConfig(
                baseUrl = prefs.getString("ai_base_url", "http://localhost:11434/v1") ?: "http://localhost:11434/v1",
                apiKey = prefs.getString("ai_api_key", "") ?: "",
                model = prefs.getString("ai_model", "qwen2.5:7b") ?: "qwen2.5:7b",
            ),
            themeMode = ThemeMode.fromValue(prefs.getInt("color_mode", 0)),
            uiStyle = UiStyle.fromValue(prefs.getString("ui_mode", "miuix") ?: "miuix"),
            driverSettings = DriverSettings(
                autoConnect = prefs.getBoolean("auto_connect", true),
                mcpPort = prefs.getInt("mcp_port", 8080),
                autoStartMcp = prefs.getBoolean("auto_start_mcp", true),
            )
        )
    }

    /**
     * 日志条目
     */
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val category: LogCategory,
        val message: String
    ) {
        enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }
    }

    enum class LogCategory(val displayName: String) {
        DRIVER("驱动"),
        MCP("MCP"),
        AI("AI"),
        SYSTEM("系统")
    }
}
