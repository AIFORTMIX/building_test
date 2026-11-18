package com.kernelai.app

import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.ai.AgentTaskService
import com.kernelai.app.data.*
import com.kernelai.app.ui.MainActivity
import com.kernelai.app.ui.viewmodel.ChatViewModel
import com.kernelai.app.ui.viewmodel.ModelsViewModel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundAgentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as KernelAiApplication
    private val api = MockWebServer()
    private var vm: ChatViewModel? = null
    private var original: ProviderCatalog? = null
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20000
        while (!condition()) { if (SystemClock.uptimeMillis() > until) throw AssertionError("Timed out waiting for background task state"); SystemClock.sleep(30) }
    }
    @Before fun start() {
        api.start()
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS").use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }
    @After fun stop() {
        main { vm?.stopStreaming(); original?.let { vm?.updateCatalog(it, managed = true) } }
        await { !AgentTaskService.running.value }
        app.getSystemService(NotificationManager::class.java).cancelAll()
        api.shutdown()
    }
    private fun configure(scenario: ActivityScenario<MainActivity>): ChatViewModel {
        lateinit var models: ModelsViewModel
        scenario.onActivity { models = ViewModelProvider(it)[ModelsViewModel::class.java]; vm = app.agentViewModel }
        await { !models.state.value.loading && vm?.uiState?.value?.loading == false }
        original = models.state.value.catalog
        val provider = ProviderConfig(name = "Background fixture", baseUrl = api.url("/v1").toString().trimEnd('/'), models = listOf(ModelConfig("background-fixture")))
        main { vm!!.newSession(); vm!!.updateCatalog(ProviderCatalog(listOf(provider), provider.id, "background-fixture"), managed = true) }
        return vm!!
    }
    private fun response(delaySeconds: Long) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"后台继续生成，完成后保留正文\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        .setBodyDelay(delaySeconds, TimeUnit.SECONDS)
    @Test fun activityCanBeDestroyedWhileForegroundTaskFinishesAndConversationSurvives() {
        api.enqueue(response(3))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = configure(scenario)
        main { model.updateInputText("验证后台完成"); model.sendMessage() }
        await { AgentTaskService.running.value && app.agentTask.value.active }
        val notifications = app.getSystemService(NotificationManager::class.java)
        await { notifications.activeNotifications.any { it.id == AgentTaskService.NOTIFICATION_ID } }
        scenario.close()
        await { !model.isStreaming.value && !AgentTaskService.running.value }
        Assert.assertTrue(model.messages.value.any { it.content == "后台继续生成，完成后保留正文" })
        Assert.assertTrue(notifications.activeNotifications.any { it.notification.extras.getCharSequence("android.title")?.toString() == "AI 回复已完成" })
        ActivityScenario.launch(MainActivity::class.java).use { reopened ->
            reopened.onActivity { Assert.assertSame(model, app.agentViewModel) }
        }
        Assert.assertEquals(1, api.requestCount)
    }
    @Test fun notificationStopActionCancelsTheBackgroundRequest() {
        api.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = configure(scenario)
        main { model.updateInputText("验证通知停止"); model.sendMessage() }
        await { AgentTaskService.running.value && app.agentTask.value.active && api.requestCount == 1 }
        val notifications = app.getSystemService(NotificationManager::class.java)
        await { notifications.activeNotifications.any { it.id == AgentTaskService.NOTIFICATION_ID } }
        scenario.close()
        val ongoing = notifications.activeNotifications.first { it.id == AgentTaskService.NOTIFICATION_ID }
        ongoing.notification.actions.first { it.title.toString() == "停止任务" }.actionIntent.send()
        await { !model.isStreaming.value && !AgentTaskService.running.value }
        Assert.assertFalse(app.agentTask.value.active)
        Assert.assertFalse(model.messages.value.any { it.content == "后台继续生成，完成后保留正文" })
    }
}
