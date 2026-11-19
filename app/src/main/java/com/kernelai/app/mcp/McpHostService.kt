package com.kernelai.app.mcp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
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
    private val preferences by lazy { getSharedPreferences("mcp-external-host", MODE_PRIVATE) }
    private val key by lazy { McpAccessKey(java.io.File(filesDir, "mcp-access.enc")) }
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var heartbeat: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enable = if (intent == null || intent.action == ACTION_RESTORE) requested(this) else intent.action != ACTION_DISABLE
        if (!enable) preferences.edit().putBoolean("enabled", false).commit()
        if (enable) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mcp-host", "MCP 外部访问", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, McpHostService::class.java).setAction(ACTION_DISABLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "mcp-host").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("轻微 MCP · 后台监听中").setContentText("外部连接已保持，可从通知关闭访问").setContentIntent(open)
                .setOngoing(true).addAction(Notification.Action.Builder(null, "关闭外部访问", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(2201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(2201, notification)
        }
        scope.launch {
            commands.withLock {
            try {
                val runtime = KernelAiApplication.instance.mcpRuntime
                val port = runtime.port.takeIf { it > 0 } ?: preferences.getInt("port", 8080)
                if (enable) {
                    runtime.start(port, expose = true, retainedExternalToken = key.loadOrCreate())
                    preferences.edit().putBoolean("enabled", true).putInt("port", runtime.port).commit()
                    _running.value = true
                    protectListener()
                    if (heartbeat?.isActive != true) heartbeat = scope.launch {
                        while (isActive && requested(this@McpHostService)) {
                            delay(60_000)
                            commands.withLock {
                                if (requested(this@McpHostService)) runCatching {
                                    if (!runtime.isRunning || !runtime.state.value.exposed) runtime.start(preferences.getInt("port", 8080), true, key.loadOrCreate())
                                    protectListener()
                                }.onFailure { _error.value = "MCP 后台监听恢复失败：${it.message}" }
                            }
                        }
                    }
                } else {
                    heartbeat?.cancel(); heartbeat = null; releaseProtection(); key.clear()
                    if (intent == null || intent.getBooleanExtra("stop_all", false)) runtime.stop() else runtime.start(port, expose = false)
                    _running.value = false
                }
                _error.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _error.value = "MCP 切换失败：${e.message}"
                KernelAiApplication.instance.mcpRuntime.stop()
                releaseProtection(); _running.value = false
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId)
            } finally { _busy.value = false; if (!enable) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId) } }
            }
        }
        return if (enable) START_STICKY else START_NOT_STICKY
    }
    private fun protectListener() {
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mcp-listener").apply { setReferenceCounted(false) }
        wake?.acquire(5 * 60_000L)
        _protected.value = wake?.isHeld == true
        runCatching {
            if (wifi == null) wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qingwei-mcp-wifi")?.apply { setReferenceCounted(false) }
            if (wifi?.isHeld == false) wifi?.acquire()
        }
    }
    private fun releaseProtection() {
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
        _protected.value = false
    }
    override fun onDestroy() {
        heartbeat?.cancel(); scope.cancel(); releaseProtection(); _running.value = false
        _busy.value = false
        // Explicit disable owns shutdown. An old Service.onDestroy must never stop
        // a listener already restored by a newer start command.
        super.onDestroy()
    }
    companion object {
        private const val ACTION_DISABLE = "com.kernelai.app.MCP_DISABLE_EXTERNAL"
        private const val ACTION_RESTORE = "com.kernelai.app.MCP_RESTORE_EXTERNAL"
        private val _busy = MutableStateFlow(false)
        val busy = _busy.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()
        private val _running = MutableStateFlow(false); val running = _running.asStateFlow()
        private val _protected = MutableStateFlow(false); val protection = _protected.asStateFlow()
        fun requested(context: Context) = context.getSharedPreferences("mcp-external-host", MODE_PRIVATE).getBoolean("enabled", false)
        fun restore(context: Context) {
            if (!requested(context)) return
            _busy.value = true
            try { ContextCompat.startForegroundService(context, Intent(context, McpHostService::class.java).setAction(ACTION_RESTORE)) }
            catch (error: Exception) { _busy.value = false; _error.value = "MCP 后台恢复失败：${error.message}"; throw error }
        }
        fun setAccess(context: Context, enabled: Boolean, stopAll: Boolean = false) {
            _busy.value = true
            val intent = Intent(context, McpHostService::class.java).putExtra("stop_all", stopAll).apply { if (!enabled) action = ACTION_DISABLE }
            try { if (enabled) ContextCompat.startForegroundService(context, intent) else context.startService(intent) }
            catch (e: Exception) { _busy.value = false; throw e }
        }
    }
}
