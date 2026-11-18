package com.kernelai.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.data.model.*
import com.kernelai.app.mcp.McpToolExecutor
import com.kernelai.app.ui.LocalUiMode
import com.kernelai.app.ui.UiMode
import com.kernelai.app.ui.screen.chat.*
import com.kernelai.app.ui.viewmodel.ChatViewModel
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatRenderingTest {
    @get:Rule val ui = createComposeRule()
    private val store = ViewModelStore()
    private val api = MockWebServer()
    @Before fun start() { api.start() }
    @After fun stop() { ui.runOnUiThread { store.clear() }; api.shutdown() }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.getExternalFilesDir(null), "test-screenshots/$name.png")
        path.parentFile!!.mkdirs()
        path.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun markdownDisplaysNativeBlocksAndNeverFetchesImages() {
        val source = """
            # Markdown 格式预览

            **粗体**、*斜体*、~~删除线~~ 与 `inline_code`

            > 引用里的说明文字

            1. 有序列表
               - 嵌套子项
            2. 第二项

            - [x] 已完成
            - [ ] 未完成

            | 名称 | 状态 |
            | :--- | ---: |
            | 进程 | 正常 |

            ```kotlin
            val message = "中文 🙂"
            ```

            [危险链接](javascript:alert) ![远程图片](${api.url("/must-not-load.png")})

            <script>alert('literal')</script>
        """.trimIndent()
        ui.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            MarkdownMessage(source, false, Color(0xFF202124))
        } } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Markdown 格式预览").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Markdown 格式预览").assertIsDisplayed()
        screenshot("markdown-top")
        ui.onNodeWithText("正常").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("复制代码").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("<script>alert('literal')</script>").performScrollTo().assertIsDisplayed()
        screenshot("markdown-code-and-safe-html")
        Assert.assertEquals(0, api.requestCount)
    }
    @Test fun toolDetailsExpandParametersResultAndTiming() {
        val call = ToolCallInfo("call_ui", "process_list", "{\"max_count\":1}",
            "{\"pid\":4321,\"command\":\"demo\"}", ToolCallStatus.COMPLETED, durationMs = 1234)
        ui.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) { ToolExecutionCard(call, Color(0xFF202124)) } } }
        ui.onNodeWithText("展开详情 ▾").performClick()
        ui.onNodeWithText("调用参数").assertIsDisplayed()
        ui.onNodeWithText("返回结果").assertIsDisplayed()
        ui.onNodeWithText("完成 · 1.2s").assertIsDisplayed()
        ui.onNodeWithText("调用 ID：call_ui").assertIsDisplayed()
        screenshot("tool-execution-expanded")
        ui.onNodeWithText("收起详情 ▴").performClick()
        ui.onNodeWithText("返回结果").assertDoesNotExist()
    }
    @Test fun conversationSwitchRestoresContentAndDraft() {
        val tools = object : McpToolExecutor {
            override fun getToolDefinitions() = emptyList<McpToolExecutor.ToolDefinition>()
            override suspend fun executeTool(name: String, arguments: JsonObject): String = error("No tools expected")
        }
        lateinit var vm: ChatViewModel
        ui.runOnUiThread { vm = ChatViewModel(tools) {}; store.put("chat", vm) }
        val config = AiConfig(api.url("/v1").toString().trimEnd('/'), model = "fixture-model")
        api.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"## 会话一的回复\\n\\n**已加载**\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        ui.setContent { CompositionLocalProvider(LocalUiMode provides UiMode.Material) { MaterialTheme {
            ChatScreen(ConnectionStatus(), config, vm)
        } } }
        ui.onNodeWithTag("chat-input").performTextInput("第一段提问")
        ui.onNodeWithContentDescription("发送").performClick()
        ui.waitUntil(15_000) { !vm.isStreaming.value && vm.messages.value.any { it.content.contains("会话一的回复") } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("会话一的回复").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("chat-input").performTextInput("保留的草稿")
        ui.onNodeWithText("新对话").performClick()
        ui.onNodeWithText("轻微 MCP").assertIsDisplayed()
        ui.onNodeWithText("新会话").performClick()
        ui.onNodeWithText("第一段提问").performClick()
        ui.onNodeWithTag("chat-input").assertTextContains("保留的草稿")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("会话一的回复").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertEquals(1, api.requestCount)
        screenshot("conversation-restored")
    }
    @Test fun switchingBackKeepsReadingPositionInsteadOfJumpingToTheEnd() {
        val tools = object : McpToolExecutor {
            override fun getToolDefinitions() = emptyList<McpToolExecutor.ToolDefinition>()
            override suspend fun executeTool(name: String, arguments: JsonObject): String = error("No tools expected")
        }
        lateinit var vm: ChatViewModel
        ui.runOnUiThread { vm = ChatViewModel(tools) {}; store.put("scroll-chat", vm) }
        val content = (1..45).joinToString("\n\n") { "### 第 $it 节\n\n这是用于验证阅读位置恢复的长回复。" }
        val escaped = kotlinx.serialization.json.JsonPrimitive(content).toString()
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":$escaped},\"finish_reason\":\"stop\"}]}"))
        val config = AiConfig(api.url("/v1").toString().trimEnd('/'), model = "fixture-model")
        ui.setContent { CompositionLocalProvider(LocalUiMode provides UiMode.Material) { MaterialTheme { ChatScreen(ConnectionStatus(), config, vm) } } }
        ui.onNodeWithTag("chat-input").performTextInput("阅读位置测试")
        ui.onNodeWithContentDescription("发送").performClick()
        ui.waitUntil(15_000) { !vm.isStreaming.value && vm.messages.value.any { it.content.contains("第 45 节") } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("第 45 节").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("chat-messages").performScrollToIndex(0)
        ui.onNodeWithTag("chat-messages").performTouchInput { swipeUp() }
        ui.waitForIdle()
        val before = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config
        val anchorId = before[ChatAnchorMessage]
        val anchorOffset = before[ChatAnchorOffset]
        ui.onNodeWithText("新对话").performClick()
        // Exercise a cold renderer cache as well as the saved LazyListState.
        ui.runOnIdle { repeat(50) { MarkdownDocument.parse("缓存淘汰 $it") } }
        ui.onNodeWithText("新会话").performClick()
        ui.onNodeWithText("阅读位置测试").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("chat-messages").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
        val after = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config
        Assert.assertEquals(anchorId, after[ChatAnchorMessage])
        Assert.assertEquals(anchorOffset, after[ChatAnchorOffset])
        Assert.assertEquals(1, api.requestCount)
    }
}
