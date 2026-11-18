package com.kernelai.app

import android.app.Application
import kotlinx.coroutines.launch
import com.kernelai.app.driver.DriverDevice
import com.kernelai.app.driver.RootDriverDevice
import com.kernelai.app.mcp.McpRuntime
import com.kernelai.app.mcp.McpHttpToolExecutor
import com.kernelai.app.mcp.McpToolExecutorImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KernelAiApplication : Application() {
    val chatStore by lazy { com.kernelai.app.data.ChatStore(java.io.File(filesDir, "conversations/chats.json")) }
    val providerStore by lazy { com.kernelai.app.data.ProviderStore(java.io.File(filesDir, "providers.enc")) }
    lateinit var deviceNode: DriverDevice
        private set
    lateinit var mcpRuntime: McpRuntime
        private set
    lateinit var aiToolExecutor: McpHttpToolExecutor
        private set
    private val _aiConnected = MutableStateFlow(false)
    val aiConnected = _aiConnected.asStateFlow()
    val isDriverConnected: Boolean get() = deviceNode.isOpen

    override fun onCreate() {
        super.onCreate()
        instance = this
        deviceNode = RootDriverDevice(this)
        mcpRuntime = McpRuntime(McpToolExecutorImpl(deviceNode), com.kernelai.app.mcp.McpAuditTrail(java.io.File(filesDir, "mcp-audit.json")))
        aiToolExecutor = McpHttpToolExecutor(mcpRuntime::endpoint)
    }
    private val storageScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun persistArchive(store: com.kernelai.app.data.ChatStore, archive: com.kernelai.app.data.ChatArchive) {
        storageScope.launch { runCatching { store.save(archive) } }
    }
    fun connectDriver(): Boolean = deviceNode.open()
    fun disconnectDriver() = deviceNode.close()
    fun setAiConnected(connected: Boolean) { _aiConnected.value = connected }
    override fun onTerminate() {
        aiToolExecutor.close()
        mcpRuntime.stop()
        disconnectDriver()
        super.onTerminate()
    }
    companion object {
        lateinit var instance: KernelAiApplication
            private set
    }
}
