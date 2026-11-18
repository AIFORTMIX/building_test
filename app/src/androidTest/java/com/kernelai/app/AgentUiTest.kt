package com.kernelai.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.data.model.*
import com.kernelai.app.mcp.*
import com.kernelai.app.ui.screen.chat.*
import com.kernelai.app.ui.screen.settings.McpAccessPanel
import com.kernelai.app.ui.theme.*
import com.kernelai.app.ui.viewmodel.ChatViewModel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AgentUiTest {
    @get:Rule val ui = createComposeRule()
    private val api = MockWebServer()
    private val store = ViewModelStore()
    private val emptyTools = object : McpToolExecutor {
        override fun getToolDefinitions() = emptyList<McpToolExecutor.ToolDefinition>()
        override suspend fun executeTool(name: String, arguments: JsonObject): String = error("No device calls expected")
    }
    @Before fun start() { api.start() }
    @After fun stop() {
        ui.runOnUiThread { store.clear() }
        api.shutdown()
    }
    private fun viewModel(): ChatViewModel {
        lateinit var vm: ChatViewModel
        ui.runOnUiThread { vm = ChatViewModel(emptyTools, storage = null) {}; store.put("agent", vm) }
        return vm
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "test-screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun config() = AiConfig(api.url("/v1").toString().trimEnd('/'), model = "fixture-model")
    @Test fun questionCardWaitsForAnswerAndResumesTheModel() {
        val vm = viewModel()
        val arguments = buildJsonObject { put("question", "需要分析哪个目标？"); putJsonArray("options") { add("自己的测试进程"); add("先看设备进程列表") } }
        val response = buildJsonObject { putJsonArray("choices") { addJsonObject {
            putJsonObject("message") { put("role", "assistant"); putJsonArray("tool_calls") { addJsonObject {
                put("id", "question-ui"); put("type", "function"); putJsonObject("function") { put("name", "ask_user"); put("arguments", arguments.toString()) }
            } } }; put("finish_reason", "tool_calls")
        } } }
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString()))
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"已按你的选择继续"},"finish_reason":"stop"}]}"""))
        val aiConfig = config()
        ui.setContent { KernelAiTheme { ChatScreen(ConnectionStatus(), aiConfig, vm) } }
        ui.onNodeWithTag("chat-input").performTextInput("先确认目标")
        ui.onNodeWithContentDescription("发送").performClick()
        ui.waitUntil(15000) { vm.uiState.value.question != null }
        ui.onNodeWithText("AI 需要你补充信息").assertIsDisplayed()
        screenshot("agent-question-card")
        ui.onNodeWithText("自己的测试进程").performClick()
        ui.waitUntil(15000) { !vm.isStreaming.value && vm.messages.value.any { it.content == "已按你的选择继续" } }
        Assert.assertNull(vm.uiState.value.question)
        Assert.assertEquals(2, api.requestCount)
    }
    @Test fun darkArtifactsPreviewImagesButNeverDownloadPackagesAutomatically() {
        api.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(previewPng())))
        val code = "<!doctype html><html><body><h1>HTML 产物预览</h1><p>用于验证深色模式与原生大卡片布局。</p></body></html>"
        val markdown = "## 分析结果\n\n```html\n$code\n```\n\n[测试安装包](${api.url("/sample.apk")})\n\n![测试图像](${api.url("/sample.png")})"
        val call = ToolCallInfo("purpose-ui", "process_list", """{"filter":"tencent","_purpose":"确认微信相关进程是否正在运行"}""", "{\"processes\":[]}", ToolCallStatus.COMPLETED)
        ui.setContent { KernelAiTheme(KernelAiAppSettings(KernelAiColorMode.DARK)) {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                ToolExecutionCard(call, Color(0xFFE7E9EF))
                MarkdownMessage(markdown, false, Color(0xFFE7E9EF))
            }
        } }
        ui.onNodeWithText("确认微信相关进程是否正在运行").assertIsDisplayed()
        ui.waitUntil(10000) { ui.onAllNodesWithText("AI 产物.html").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("AI 产物.html").performScrollTo().assertIsDisplayed()
        screenshot("agent-dark-html-artifact")
        ui.onNodeWithText("APK  ·  产物").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("inline-image").performScrollTo().assertIsDisplayed()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("inline-image").fetchSemanticsNodes().any { it.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "已加载" } }
        screenshot("agent-dark-download-artifacts")
        Assert.assertEquals(1, api.requestCount)
        Assert.assertEquals("/sample.png", api.takeRequest().path)
    }
    @Test fun externalToggleStartsSingleApkHostAndRecordsExternalCall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = KernelAiApplication.instance.mcpRuntime
        runtime.start(0)
        ui.setContent { KernelAiTheme { McpAccessPanel {} } }
        val http = OkHttpClient()
        try {
            ui.onNodeWithTag("mcp-exposure-switch").performClick()
            ui.waitUntil(15000) { runtime.state.value.exposed && !McpHostService.busy.value }
            val body = """{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"address_calculate","arguments":{"base_address":"0x1000","offsets":["0x20"],"_purpose":"校验外部客户端地址计算"}}}"""
            http.newCall(Request.Builder().url(runtime.endpoint().url).header("Authorization", "Bearer " + runtime.externalAccessToken())
                .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { Assert.assertEquals(200, it.code); Assert.assertTrue(it.body!!.string().contains("0x1020")) }
            Assert.assertTrue(runtime.audit.entries.value.any { it.external && it.tool == "address_calculate" && it.status == "成功" })
            screenshot("agent-external-mcp-access")
            ui.onNodeWithTag("mcp-exposure-switch").performClick()
            ui.waitUntil(15000) { !runtime.state.value.exposed && !McpHostService.busy.value }
            Assert.assertTrue(runtime.isRunning)
        } finally {
            McpHostService.setAccess(context, false)
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
    @Test fun providerCredentialsAndModelCapabilitiesSurviveEncryptedStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "provider-test.enc")
        val provider = com.kernelai.app.data.ProviderConfig(name = "测试供应商", baseUrl = "https://example.invalid/v1", apiKey = "fixture-secret",
            models = listOf(com.kernelai.app.data.ModelConfig("vision-fixture", contextWindow = 65536, supportsImages = true)))
        val catalog = com.kernelai.app.data.ProviderCatalog(listOf(provider), provider.id, "vision-fixture")
        try {
            kotlinx.coroutines.runBlocking { com.kernelai.app.data.ProviderStore(file).save(catalog) }
            Assert.assertFalse(file.readText().contains("fixture-secret"))
            val loaded = kotlinx.coroutines.runBlocking { com.kernelai.app.data.ProviderStore(file).load()!! }
            Assert.assertEquals(catalog, loaded); Assert.assertTrue(loaded.activeConfig()!!.supportsImages)
            Assert.assertEquals(65536, loaded.activeConfig()!!.contextWindow); Assert.assertEquals(5, loaded.activeConfig()!!.maxRetries)
        } finally { file.delete() }
    }
    @Test fun selectedImageIsDecodedAndTextFileIsCopiedIntoPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "artifacts").apply { mkdirs() }
        val image = File(directory, "attachment-fixture.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val text = File(directory, "attachment-fixture.txt").apply { writeText("测试文件内容 🙂") }
        val importer = com.kernelai.app.data.AttachmentStore(context)
        val imported = kotlinx.coroutines.runBlocking {
            listOf(image, text).map { file -> importer.import(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)) }
        }
        Assert.assertTrue(imported[0].isImage); Assert.assertTrue(File(imported[0].localPath).length() > 0)
        Assert.assertEquals("测试文件内容 🙂", imported[1].textContent)
        ui.setContent { KernelAiTheme { Column { imported.forEach { AttachmentTile(it) } } } }
        ui.onNodeWithText("attachment-fixture.png").assertIsDisplayed()
        ui.onNodeWithText("attachment-fixture.txt").assertIsDisplayed()
        screenshot("agent-imported-attachments")
    }
    @Test fun completedToolRunFoldsBatchesAndDetailsInThreeLevels() {
        val completed = androidx.compose.runtime.mutableStateOf(false)
        val one = ToolCallInfo("tree-one", "process_resolve", "{\"pkg_name\":\"com.tencent.mm\"}", "{\"pid\":123}", ToolCallStatus.COMPLETED)
        val two = ToolCallInfo("tree-two", "memory_inspect", "{\"pid\":123}", "{\"size\":32}", ToolCallStatus.COMPLETED)
        val messages = listOf(ChatMessage(id = "batch-one", role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(one)),
            ChatMessage(id = "batch-two", role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(two)))
        ui.setContent { KernelAiTheme {
            Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                ToolExecutionRun(messages, MiuixTheme.colorScheme.onBackground, generationComplete = completed.value)
                ChatMessageBubble(ChatMessage(role = MessageRole.ASSISTANT, content = "工具完成后的正文"))
            }
        } }
        ui.onNodeWithText("执行过程 · 2 项工具").assertIsDisplayed()
        ui.onNodeWithTag("tool-tree-one").assertDoesNotExist()
        ui.onNodeWithText("复制消息").assertDoesNotExist()
        screenshot("glass-tool-run-collapsed")
        ui.onNodeWithContentDescription("展开执行过程").performClick()
        ui.onNodeWithText("第 1 批 · 1 项").performClick()
        ui.runOnIdle { completed.value = true }
        ui.waitForIdle()
        ui.onNodeWithText("第 1 批 · 1 项").assertDoesNotExist()
        ui.onNodeWithContentDescription("展开执行过程").performClick()
        ui.onNodeWithText("第 1 批 · 1 项").performClick()
        ui.onNodeWithContentDescription("展开 process_resolve 详情").performClick()
        ui.onNodeWithText("调用 ID：tree-one").assertIsDisplayed()
        ui.onNodeWithTag("tool-tree-two").assertDoesNotExist()
        screenshot("glass-tool-run-details")
    }

    @Test fun streamFollowsUntilDraggedAndResumeReturnsToLatest() {
        val vm = viewModel()
        val events = (1..60).joinToString("") { index ->
            "data: " + buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("delta") { put("content", "第 $index 段：这是用于验证自动跟随与手动阅读互不干扰的流式回复。\n\n") } } } } + "\n\n"
        } + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        api.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events).throttleBody(420, 90, TimeUnit.MILLISECONDS))
        val aiConfig = config()
        ui.setContent { KernelAiTheme { ChatScreen(ConnectionStatus(), aiConfig, vm) } }
        ui.mainClock.autoAdvance = false
        ui.onNodeWithTag("chat-input").performTextInput("连续生成长回复")
        ui.mainClock.advanceTimeBy(200)
        ui.onNodeWithContentDescription("发送").assertIsEnabled()
        ui.onNodeWithContentDescription("发送").performClick()
        ui.mainClock.advanceTimeBy(200)
        try { ui.waitUntil(15000) {
            ui.mainClock.advanceTimeBy(80)
            val config = ui.onAllNodesWithTag("chat-messages").fetchSemanticsNodes().singleOrNull()?.config
            vm.isStreaming.value && vm.messages.value.lastOrNull()?.content.orEmpty().contains("第 16 段") && config?.get(ChatAtLatest) == true
        }
        } catch (e: Exception) {
            println("STREAM_DIAGNOSTICS generating=${vm.isStreaming.value} length=${vm.messages.value.lastOrNull()?.content?.length} requests=${api.requestCount} follows=${ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatFollowsLatest]} latest=${ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatAtLatest]}")
            screenshot("agent-stream-follow-timeout")
            throw e
        }
        ui.onNodeWithTag("chat-messages").performTouchInput { swipeDown(startY = height * .28f, endY = height * .68f, durationMillis = 700) }
        ui.waitUntil(5000) { ui.mainClock.advanceTimeBy(80); !ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatIsScrolling] }
        val before = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config
        Assert.assertFalse(before[ChatFollowsLatest])
        val id = before[ChatAnchorMessage]; val offset = before[ChatAnchorOffset]
        ui.waitUntil(20000) { ui.mainClock.advanceTimeBy(80); !vm.isStreaming.value }
        val after = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config
        Assert.assertEquals(id, after[ChatAnchorMessage]); Assert.assertEquals(offset, after[ChatAnchorOffset])
        ui.onNodeWithContentDescription("回到最新").performClick()
        ui.waitUntil(5000) { ui.mainClock.advanceTimeBy(80); ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatAtLatest] }
        ui.mainClock.autoAdvance = true
        ui.waitForIdle()
        for (index in 50..59) {
            val node = ui.onNodeWithText("第 $index 段：这是用于验证自动跟随与手动阅读互不干扰的流式回复。", useUnmergedTree = true)
            val next = ui.onNodeWithText("第 ${index + 1} 段：这是用于验证自动跟随与手动阅读互不干扰的流式回复。", useUnmergedTree = true)
            val bounds = node.getUnclippedBoundsInRoot()
            val pixels = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { get ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                Assert.assertTrue(get(results))
                val layout = results.single()
                Assert.assertTrue("Paragraph must reserve height for every rendered line", layout.size.height + 1 >= layout.getLineBottom(layout.lineCount - 1))
                Assert.assertTrue("Paragraph $index bounds must contain its rendered lines: $bounds / ${layout.size}", (bounds.bottom - bounds.top).value * pixels + 1 >= layout.size.height)
            }
            Assert.assertTrue("Paragraph $index must keep its 10dp block spacing", bounds.bottom.value + 8 <= next.getUnclippedBoundsInRoot().top.value)
        }
        screenshot("agent-stream-follow-resumed")
        ui.onNodeWithTag("chat-messages").performTouchInput { swipeDown(startY = height * .28f, endY = height * .68f, durationMillis = 700) }
        ui.waitUntil(5_000) { !ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatIsScrolling] }
        Assert.assertFalse(ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config[ChatFollowsLatest])
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"第二次发送应主动回到最新"},"finish_reason":"stop"}]}"""))
        ui.onNodeWithTag("chat-input").performTextInput("继续回复")
        ui.onNodeWithContentDescription("发送").performClick()
        ui.waitUntil(15_000) { !vm.isStreaming.value && vm.messages.value.lastOrNull()?.content == "第二次发送应主动回到最新" }
        ui.waitUntil(10_000) { val state = ui.onNodeWithTag("chat-messages").fetchSemanticsNode().config; state[ChatFollowsLatest] && state[ChatAtLatest] }
        ui.onNodeWithText("第二次发送应主动回到最新").assertIsDisplayed()
    }
}
