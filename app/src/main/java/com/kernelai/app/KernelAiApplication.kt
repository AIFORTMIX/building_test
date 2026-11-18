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

class KernelAiApplication : Application(), androidx.lifecycle.ViewModelStoreOwner {
    override val viewModelStore = androidx.lifecycle.ViewModelStore()
    val agentViewModel: com.kernelai.app.ui.viewmodel.ChatViewModel
        get() = androidx.lifecycle.ViewModelProvider(viewModelStore, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[com.kernelai.app.ui.viewmodel.ChatViewModel::class.java]
    private val _agentTask = MutableStateFlow(com.kernelai.app.ai.AgentTask())
    val agentTask = _agentTask.asStateFlow()
    var uiVisible = false; private set
    val chatStore by lazy { com.kernelai.app.data.ChatStore(java.io.File(filesDir, "conversations/chats.json")) }
    val providerStore by lazy { com.kernelai.app.data.ProviderStore(java.io.File(filesDir, "providers.enc")) }
    val workspaceRepository by lazy { com.kernelai.app.data.WorkspaceRepository(this) }
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
        mcpRuntime = McpRuntime(com.kernelai.app.mcp.CompositeToolExecutor(McpToolExecutorImpl(deviceNode), com.kernelai.app.mcp.WorkspaceTools(workspaceRepository)), com.kernelai.app.mcp.McpAuditTrail(java.io.File(filesDir, "mcp-audit.json")))
        aiToolExecutor = McpHttpToolExecutor(mcpRuntime::endpoint, com.kernelai.app.mcp.WorkspaceTools(workspaceRepository).getToolDefinitions())
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++; uiVisible = started > 0 }
            override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0); uiVisible = started > 0 }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
    fun reportAgentTask(task: com.kernelai.app.ai.AgentTask) {
        val previous = _agentTask.value
        _agentTask.value = task
        if (task.active && !previous.active) {
            try { androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, com.kernelai.app.ai.AgentTaskService::class.java)) }
            catch (error: Exception) { _agentTask.value = task.copy(active = false); throw error }
        }
    }
    private val storageScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    fun persistArchive(store: com.kernelai.app.data.ChatStore, archive: com.kernelai.app.data.ChatArchive) {
        storageScope.launch { runCatching { store.save(archive) } }
    }
    fun connectDriver(): Boolean = deviceNode.open()
    fun disconnectDriver() = deviceNode.close()
    fun setAiConnected(connected: Boolean) { _aiConnected.value = connected }
    override fun onTerminate() {
        viewModelStore.clear()
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
