package com.kernelai.app

import android.app.Application
import com.kernelai.app.driver.DriverDevice
import com.kernelai.app.driver.RootDriverDevice
import com.kernelai.app.mcp.McpRuntime
import com.kernelai.app.mcp.McpHttpToolExecutor
import com.kernelai.app.mcp.McpToolExecutorImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class KernelAiApplication : Application() {
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
        mcpRuntime = McpRuntime(McpToolExecutorImpl(deviceNode))
        aiToolExecutor = McpHttpToolExecutor(mcpRuntime::endpoint)
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
