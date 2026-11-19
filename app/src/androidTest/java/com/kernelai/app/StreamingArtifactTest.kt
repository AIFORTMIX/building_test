package com.kernelai.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.data.model.AiConfig
import com.kernelai.app.data.model.ConnectionStatus
import com.kernelai.app.mcp.McpToolExecutor
import com.kernelai.app.ui.screen.chat.ChatAtLatest
import com.kernelai.app.ui.screen.chat.ChatScreen
import com.kernelai.app.ui.theme.KernelAiTheme
import com.kernelai.app.ui.viewmodel.ChatViewModel
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import java.util.concurrent.TimeUnit

class StreamingArtifactTest {
    @get:Rule val ui = createComposeRule()
    @Test fun finalArtifactLayoutStaysAtLatestAndImageBudgetSurvivesReparsing() {
        val server = MockWebServer(); server.start()
        val store = ViewModelStore()
        val image = previewPng()
        val video = InstrumentationRegistry.getInstrumentation().context.assets.open("preview-video.mp4").use { it.readBytes() }
        val finalUrl = server.url("/final.txt").toString()
        val parts = listOf("# 流式媒体回复\n\n", "![保持图片预览](${server.url("/image.png")})\n\n", "前面的图片不能因后续文字而退化为文字入口。\n\n",
            "[视频卡片](${server.url("/video.mp4")})\n\n") + (1..5).map { "[文本产物 $it](${server.url("/file$it.txt")})\n\n" } + "[最后一张产物]($finalUrl)\n\n"
        val events = parts.joinToString("") { "data: " + buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", it) } } } } + "\n\n" } + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/image.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(image))
                "/video.mp4" -> MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(video))
                else -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events).throttleBody(160, 90, TimeUnit.MILLISECONDS)
            }
        }
        lateinit var vm: ChatViewModel
        val tools = object : McpToolExecutor {
            override fun getToolDefinitions() = emptyList<McpToolExecutor.ToolDefinition>()
            override suspend fun executeTool(name: String, arguments: JsonObject) = error("No device calls")
        }
        ui.runOnUiThread { vm = ChatViewModel(tools, storage = null) {}; store.put("stream-artifacts", vm) }
        val config = AiConfig(server.url("/v1").toString().trimEnd('/'), model = "fixture")
        try {
            ui.setContent { KernelAiTheme { ChatScreen(ConnectionStatus(), config, vm) } }
            ui.onNodeWithTag("chat-input").performTextInput("生成包含媒体和产物的回复")
            ui.onNodeWithContentDescription("发送").performClick()
            ui.waitUntil(20_000) { !vm.isStreaming.value && vm.messages.value.lastOrNull()?.content?.contains("最后一张产物") == true }
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("artifact-$finalUrl").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(10_000) { ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatAtLatest] }
            ui.onNodeWithTag("artifact-$finalUrl").assertIsDisplayed()
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("inline-image").fetchSemanticsNodes().any { it.config[SemanticsProperties.StateDescription] == "已加载" } }
            ui.onNodeWithText("图片：保持图片预览 · 点按预览").assertDoesNotExist()
        } finally { ui.runOnUiThread { store.clear() }; server.shutdown() }
    }
}
