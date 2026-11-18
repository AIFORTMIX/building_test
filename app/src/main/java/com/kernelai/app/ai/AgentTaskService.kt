package com.kernelai.app.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentTask(val active: Boolean = false, val title: String = "轻微 AI", val detail: String = "正在准备任务…",
    val waitingForUser: Boolean = false, val outcome: String = "done")

/** One user-started agent task survives Activity teardown; never restarts device actions after a process kill. */
class AgentTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as KernelAiApplication
    private var wake: PowerManager.WakeLock? = null
    private var completed = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI 后台任务", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "AI 回复完成与提问", NotificationManager.IMPORTANCE_DEFAULT))
        val first = notification(app.agentTask.value)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, first)
        _running.value = true
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:agent-task").apply { setReferenceCounted(false) }
        scope.launch {
            while (isActive) {
                updateWakeLock(app.agentTask.value)
                delay(60_000)
            }
        }
        scope.launch {
            app.agentTask.collect { task ->
                if (task.active) {
                    updateWakeLock(task)
                    manager.notify(NOTIFICATION_ID, notification(task))
                } else {
                    completed = true
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!app.uiVisible && task.outcome != "cancelled") manager.notify(DONE_ID, notification(task, done = true))
                    stopSelf()
                }
                delay(500)
            }
        }
    }
    private fun updateWakeLock(task: AgentTask) {
        val lock = wake ?: return
        if (task.active && !task.waitingForUser) lock.acquire(6 * 60_000L)
        else if (lock.isHeld) lock.release()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.agentViewModel.stopStreaming()
            completed = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(task: AgentTask, done: Boolean = false): Notification {
        val openIntent = Intent(this, MainActivity::class.java).putExtra(OPEN_CHAT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 31, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 32, Intent(this, AgentTaskService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when { done && task.outcome == "failed" -> "AI 任务未完成"; done -> "AI 回复已完成"; task.waitingForUser -> "AI 需要你补充信息"; else -> "AI 正在后台工作" }
        val builder = Notification.Builder(this, if (done) DONE_CHANNEL else CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title)
            .setContentText(task.title.take(80) + " · " + task.detail.take(100)).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .setOngoing(!done).setAutoCancel(done)
        if (Build.VERSION.SDK_INT >= 31 && !done) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (!done) builder.addAction(Notification.Action.Builder(null, "停止任务", stop).build())
        return builder.build()
    }
    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        _running.value = false
        if (!completed && app.agentTask.value.active) app.agentViewModel.stopStreaming()
        super.onDestroy()
    }
    companion object {
        const val OPEN_CHAT = "qingwei_open_active_chat"
        private const val ACTION_STOP = "com.kernelai.app.STOP_AGENT_TASK"
        private const val CHANNEL = "qingwei-agent-task"
        private const val DONE_CHANNEL = "qingwei-agent-result"
        const val NOTIFICATION_ID = 2301
        private const val DONE_ID = 2302
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
    }
}
