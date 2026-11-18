package com.kernelai.app.mcp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the explicitly enabled external MCP host alive without a second APK. */
class McpHostService : Service() {
    private val commands = kotlinx.coroutines.sync.Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enable = intent?.action != ACTION_DISABLE
        if (enable) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mcp-host", "MCP 外部访问", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, McpHostService::class.java).setAction(ACTION_DISABLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "mcp-host").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("轻微 MCP · 外部访问已开启").setContentText("其他客户端可凭令牌调用设备工具").setContentIntent(open)
                .setOngoing(true).addAction(Notification.Action.Builder(null, "关闭外部访问", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(2201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(2201, notification)
        }
        scope.launch {
            commands.withLock {
            try {
                val runtime = KernelAiApplication.instance.mcpRuntime
                val port = runtime.port.takeIf { it > 0 } ?: 8080
                runtime.start(port, expose = enable)
                _error.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "MCP 切换失败：${e.message}"
                KernelAiApplication.instance.mcpRuntime.stop()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            } finally { _busy.value = false; if (!enable) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        _busy.value = false
        val runtime = KernelAiApplication.instance.mcpRuntime
        if (runtime.state.value.exposed) Thread({ runtime.stop() }, "mcp-host-stop").apply { isDaemon = true; start() }
        super.onDestroy()
    }
    companion object {
        private const val ACTION_DISABLE = "com.kernelai.app.MCP_DISABLE_EXTERNAL"
        private val _busy = MutableStateFlow(false)
        val busy = _busy.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()
        fun setAccess(context: Context, enabled: Boolean) {
            _busy.value = true
            val intent = Intent(context, McpHostService::class.java).apply { if (!enabled) action = ACTION_DISABLE }
            try { if (enabled) ContextCompat.startForegroundService(context, intent) else context.startService(intent) }
            catch (e: Exception) { _busy.value = false; throw e }
        }
    }
}
