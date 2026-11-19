package com.kernelai.app.ai

import androidx.lifecycle.ViewModelStore
import com.kernelai.app.data.model.MessageRole
import com.kernelai.app.data.model.ToolCallStatus
import com.kernelai.app.driver.DriverDevice
import com.kernelai.app.driver.IoctlCommands
import com.kernelai.app.mcp.*
import com.kernelai.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP/SSE and MCP code, with an explicitly simulated ioctl device boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiMcpIntegrationTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var api: MockWebServer
    private lateinit var driver: SimulatedDriver
    private lateinit var runtime: McpRuntime
    private lateinit var tools: McpHttpToolExecutor
    private val managers = mutableListOf<AiChatManager>()
    private val clients = mutableListOf<OpenAiClient>()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        main = Executors.newSingleThreadExecutor { Thread(it, "ai-test-main").apply { isDaemon = true } }.asCoroutineDispatcher()
        Dispatchers.setMain(main)
        api = MockWebServer().apply { start() }
        driver = SimulatedDriver()
        runtime = McpRuntime(McpToolExecutorImpl(driver)).apply { start(0) }
        tools = McpHttpToolExecutor(runtime::endpoint)
    }
    @After fun teardown() {
        driver.release.countDown()
        runBlocking(Dispatchers.Main) { stores.forEach { it.clear() }; managers.forEach { it.destroy() } }
        clients.forEach { it.close() }
        tools.close(); runtime.stop(); api.shutdown()
        Dispatchers.resetMain(); main.close()
    }
    private fun config() = AiConfig(api.url("/v1").toString().trimEnd('/'), "", "fixture-model")
    private fun manager(): AiChatManager = AiChatManager(OpenAiClient(config()), tools, config()).also { managers.add(it) }
    private fun text(value: String, end: Boolean = true): String = chunk(buildJsonObject { put("content", value) }) +
        if (end) chunk(buildJsonObject {}, "stop") + "data: [DONE]\n\n" else ""
    private fun chunk(delta: JsonObject, finish: String? = null): String = "data: " + buildJsonObject {
        putJsonArray("choices") { addJsonObject { put("delta", delta); put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull) } }
    } + "\n\n"
    private fun toolDelta(index: Int, id: String? = null, name: String? = null, args: String? = null): String = chunk(buildJsonObject {
        putJsonArray("tool_calls") { addJsonObject {
            put("index", index); id?.let { put("id", it); put("type", "function") }
            putJsonObject("function") { name?.let { put("name", it) }; args?.let { put("arguments", it) } }
        } }
    })
    private fun toolResponse(args: String = "{\"max_count\":1}") =
        toolDelta(0, "call_demo", "process_", args.take(1)) + toolDelta(0, name = "list", args = args.drop(1)) +
            chunk(buildJsonObject {}, "tool_calls") + "data: [DONE]\n\n"
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setChunkedBody(body, 3)
    private suspend fun converse(manager: AiChatManager, until: (ChatEvent) -> Boolean = { it == ChatEvent.GenerationComplete || it is ChatEvent.Error }): List<ChatEvent> = coroutineScope {
        val result = CopyOnWriteArrayList<ChatEvent>()
        val done = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            manager.chatEvents.collect { result.add(it); if (until(it)) done.complete(Unit) }
        }
        try {
            withContext(Dispatchers.Main) { manager.sendMessage("请读取进程列表") }
            withTimeout(15_000) { done.await() }
            result.toList()
        } finally { collector.cancelAndJoin() }
    }
    private fun nextRequest(): JsonObject = Json.parseToJsonElement(
        api.takeRequest(5, TimeUnit.SECONDS)?.body?.readUtf8() ?: error("Missing model request")).jsonObject

    @Test fun streamedToolCallTraversesHttpMcpAndIoctlThenReturnsMatchingToolId() = runBlocking {
        api.enqueue(response(toolResponse())); api.enqueue(response(text("查询完成：demo 🙂")))
        val events = converse(manager())
        assertEquals(1, driver.calls.get())
        assertEquals(IoctlCommands.CMD_LIST_PROCS, driver.lastCommand)
        val result = events.filterIsInstance<ChatEvent.ToolCallResult>().single()
        assertFalse(result.isError)
        assertTrue(result.result.contains("4321"))
        assertTrue(result.result.contains("com.example.test"))
        assertEquals("查询完成：demo 🙂", events.filterIsInstance<ChatEvent.StreamingText>().joinToString("") { it.delta })
        assertTrue(nextRequest()["stream"]!!.jsonPrimitive.boolean)
        val sent = nextRequest()["messages"]!!.jsonArray
        val assistant = sent.first { it.jsonObject["role"] == JsonPrimitive("assistant") }.jsonObject
        val tool = sent.first { it.jsonObject["role"] == JsonPrimitive("tool") }.jsonObject
        assertEquals(assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"], tool["tool_call_id"])
    }
    @Test fun uiViewModelUsesTheSameNetworkToolChain() = runBlocking {
        api.enqueue(response(toolResponse())); api.enqueue(response(text("UI 链路成功")))
        val connections = CopyOnWriteArrayList<Boolean>()
        val vm = withContext(Dispatchers.Main) {
            ChatViewModel(tools) { connections.add(it) }.also {
                stores.add(ViewModelStore().apply { put("chat", it) })
                it.updateAiConfig(com.kernelai.app.data.model.AiConfig(config().baseUrl, model = "fixture-model"))
                it.updateInputText("列出进程"); it.sendMessage()
            }
        }
        withTimeout(15_000) {
            while (vm.isStreaming.value || vm.messages.value.none { it.content == "UI 链路成功" }) delay(10)
        }
        assertTrue(connections.last())
        assertEquals(1, driver.calls.get())
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.ASSISTANT), vm.messages.value.map { it.role })
        assertEquals(ToolCallStatus.COMPLETED, vm.messages.value.first { it.role == MessageRole.TOOL }.toolCalls!!.single().status)
    }
    @Test fun manualRetryKeepsCompletedToolReceiptsWithoutExecutingTheToolAgain() = runBlocking {
        api.enqueue(response(toolResponse())); api.enqueue(MockResponse().setResponseCode(401).setBody("invalid_api_key"))
        val manager = manager()
        assertTrue(converse(manager).any { it is ChatEvent.Error })
        assertEquals(1, driver.calls.get())
        assertEquals(listOf("system", "user", "assistant", "tool"), withContext(Dispatchers.Main) { manager.getMessages().map { it.role } })
        api.enqueue(response(text("沿用已经完成的查询结果")))
        val done = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) { manager.chatEvents.collect { if (it == ChatEvent.GenerationComplete) done.complete(Unit) } }
        try {
            withContext(Dispatchers.Main) { manager.retryLastRequest(manager.getActiveSessionId()) }
            withTimeout(15000) { done.await() }
            nextRequest(); nextRequest()
            val history = nextRequest()["messages"]!!.jsonArray
            assertEquals("Request roles: " + history.map { it.jsonObject["role"] }, 1, history.count { it.jsonObject["role"] == JsonPrimitive("tool") })
            assertEquals(1, driver.calls.get())
        } finally { collector.cancelAndJoin() }
    }
    @Test fun appConversationWorkspaceScopeRejectsCrossWorkspaceWritesBeforeDispatch() = runBlocking {
        val calls = AtomicInteger()
        val delegate = object : McpToolExecutor {
            override fun getToolDefinitions() = listOf(McpToolExecutor.ToolDefinition("workspace_write_file", "fixture", buildJsonObject { put("type", "object") }))
            override suspend fun executeTool(name: String, arguments: JsonObject): String { calls.incrementAndGet(); return "{\"saved\":true}" }
        }
        api.enqueue(response(toolDelta(0, "call_scope", "workspace_write_file", "{\"workspace_id\":\"other\",\"path\":\"a.txt\",\"content\":\"x\"}") + chunk(buildJsonObject {}, "tool_calls") + "data: [DONE]\n\n"))
        api.enqueue(response(text("请先选择正确的工作区")))
        val manager = AiChatManager(OpenAiClient(config()), delegate, config(), workspaceScope = { "selected" }).also { managers.add(it) }
        val events = converse(manager)
        assertTrue(events.filterIsInstance<ChatEvent.ToolCallResult>().single().isError)
        assertEquals(0, calls.get())
        val first = nextRequest()["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(first.contains("selected"))
    }
    @Test fun jsonResponseToStreamingRequestIsHandled() = runBlocking {
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"role":"assistant","content":"普通 JSON 🙂"},"finish_reason":"stop"}]}"""))
        val events = converse(manager())
        assertEquals("普通 JSON 🙂", events.filterIsInstance<ChatEvent.StreamingText>().joinToString("") { it.delta })
        assertEquals(0, driver.calls.get())
    }
    @Test fun oversizedJsonToolListIsRejectedBeforeAnyUiToolEventsOrIoctl() = runBlocking {
        val body = buildJsonObject { putJsonArray("choices") { addJsonObject {
            putJsonObject("message") {
                put("role", "assistant"); put("content", JsonNull)
                putJsonArray("tool_calls") { repeat(33) { index -> addJsonObject {
                    put("id", "call_$index"); put("type", "function")
                    putJsonObject("function") { put("name", "process_list"); put("arguments", "{}") }
                } } }
            }
            put("finish_reason", "tool_calls")
        } } }
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
        val events = converse(manager())
        assertTrue(events.any { it is ChatEvent.Error })
        assertTrue(events.none { it is ChatEvent.ToolCallStreaming || it is ChatEvent.ToolCallsStarting })
        assertEquals(0, driver.calls.get())
    }
    @Test fun multilineSseCrOnlyAndBomAreDecoded() = runBlocking {
        val body = "\uFEFF: keepalive\r\rdata: {\"choices\":[\rdata: {\"delta\":{\"content\":\"中文🙂\"},\"finish_reason\":null}]}\r\r" +
            chunk(buildJsonObject {}, "stop").replace("\n", "\r")
        api.enqueue(response(body))
        val events = converse(manager())
        assertEquals("中文🙂", events.filterIsInstance<ChatEvent.StreamingText>().joinToString("") { it.delta })
    }
    @Test fun malformedArgumentsAreReturnedAsToolErrorsWithoutIoctl() = runBlocking {
        api.enqueue(response(toolResponse("{broken"))); api.enqueue(response(text("参数错误已处理")))
        val events = converse(manager())
        assertTrue(events.filterIsInstance<ChatEvent.ToolCallResult>().single().isError)
        assertEquals(0, driver.calls.get())
        nextRequest()
        val tool = nextRequest()["messages"]!!.jsonArray.first { it.jsonObject["role"] == JsonPrimitive("tool") }.jsonObject
        assertEquals("call_demo", tool["tool_call_id"]!!.jsonPrimitive.content)
    }
    @Test fun truncatedStreamNeverExecutesEvenACompleteLookingTool() = runBlocking {
        api.enqueue(response(toolDelta(0, "call_demo", "process_list", "{}")))
        val events = converse(manager())
        assertTrue(events.any { it is ChatEvent.Error })
        assertEquals(0, driver.calls.get())
    }
    @Test fun httpFailureIsReportedWithoutToolExecution() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(401).setBody("not a valid key"))
        val events = converse(manager())
        assertTrue(events.filterIsInstance<ChatEvent.Error>().single().message.contains("401"))
        assertEquals(0, driver.calls.get())
    }
    @Test fun malformedChunkIsReported() = runBlocking {
        api.enqueue(response("data: {bad-json}\n\ndata: [DONE]\n\n"))
        assertTrue(converse(manager()).any { it is ChatEvent.Error })
        assertEquals(0, driver.calls.get())
    }
    @Test fun excessiveEventIsRejected() = runBlocking {
        api.enqueue(response("data: " + "x".repeat(256 * 1024 + 1) + "\n\n"))
        assertTrue(converse(manager()).any { it is ChatEvent.Error })
        assertEquals(0, driver.calls.get())
    }
    @Test fun driverFailureReturnsThroughMcpAsAnError() = runBlocking {
        driver.error = -5
        api.enqueue(response(toolResponse())); api.enqueue(response(text("驱动错误已处理")))
        val events = converse(manager())
        val result = events.filterIsInstance<ChatEvent.ToolCallResult>().single()
        assertTrue(result.isError); assertTrue(result.result.contains("ret=-5"))
        assertEquals(1, driver.calls.get())
    }
    @Test fun missingDriverDoesNotProduceSimulatedSuccess() = runBlocking {
        driver.isOpen = false; driver.allowOpen = false
        api.enqueue(response(toolResponse())); api.enqueue(response(text("驱动未连接")))
        assertTrue(converse(manager()).filterIsInstance<ChatEvent.ToolCallResult>().single().isError)
        assertEquals(0, driver.calls.get())
    }
    @Test fun interleavedToolIndexesAreCompletedInIndexOrder() = runBlocking {
        api.enqueue(response(toolDelta(1, "call_1", "process_list", "{}") + toolDelta(0, "call_0", "process_list", "{}") + chunk(buildJsonObject {}, "tool_calls")))
        api.enqueue(response(text("两次查询完成")))
        val events = converse(manager())
        assertEquals(listOf("call_0", "call_1"), events.filterIsInstance<ChatEvent.ToolCallResult>().map { it.toolCallId })
        assertEquals(2, driver.calls.get())
    }
    @Test fun cancellationStopsANonrespondingModelRequest() = runBlocking {
        api.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OpenAiClient(config()).also { clients.add(it) }
        val job = launch { client.streamChatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "hello"))).collect {} }
        withContext(Dispatchers.IO) { check(api.takeRequest(5, TimeUnit.SECONDS) != null) }
        withTimeout(5_000) { job.cancelAndJoin() }
    }
    @Test fun cancelledToolGetsAMatchingHistoryResultBeforeNextUserTurn() = runBlocking {
        driver.block = true
        api.enqueue(response(toolResponse())); api.enqueue(response(text("新的回复")))
        val manager = manager()
        val cancelled = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            manager.chatEvents.collect { if (it == ChatEvent.GenerationCancelled) cancelled.complete(Unit); if (it == ChatEvent.GenerationComplete) completed.complete(Unit) }
        }
        try {
            withContext(Dispatchers.Main) { manager.sendMessage("查询") }
            withContext(Dispatchers.IO) { assertTrue(driver.started.await(5, TimeUnit.SECONDS)) }
            withContext(Dispatchers.Main) { manager.cancelGeneration() }
            driver.release.countDown()
            withTimeout(5_000) { cancelled.await() }
            withContext(Dispatchers.Main) { manager.sendMessage("继续") }
            withTimeout(15_000) { completed.await() }
            val history = withContext(Dispatchers.Main) { manager.getMessages() }
            assertEquals("call_demo", history.first { it.role == "tool" }.toolCallId)
            assertTrue(history.first { it.role == "tool" }.content!!.contains("结果未确认"))
        } finally { collector.cancelAndJoin() }
    }
    @Test fun mcpHttpRequiresTokenAndRejectsForeignOrigin() {
        val client = OkHttpClient()
        try {
            val endpoint = runtime.endpoint()
            fun call(token: String, origin: String? = null): Int {
                val request = Request.Builder().url(endpoint.url).header("Authorization", "Bearer " + token)
                    .post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toRequestBody("application/json".toMediaType()))
                origin?.let { request.header("Origin", it) }
                return client.newCall(request.build()).execute().use { it.code }
            }
            assertEquals(401, call("wrong"))
            assertEquals(403, call(endpoint.token, "https://unrelated.example"))
            assertEquals(200, call(endpoint.token))
            assertEquals(0, driver.calls.get())
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun nonStreamingClientUsesTheCorrectRequestFormat() = runBlocking {
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"role":"assistant","content":"非流式"},"finish_reason":"stop"}]}"""))
        val client = OpenAiClient(config()).also { clients.add(it) }
        assertEquals("非流式", client.chatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "hello"))).getOrThrow().content)
        assertFalse(nextRequest()["stream"]!!.jsonPrimitive.boolean)
    }

    private suspend fun viewModel(): ChatViewModel = withContext(Dispatchers.Main) {
        ChatViewModel(tools) {}.also {
            stores.add(ViewModelStore().apply { put("chat", it) })
            it.updateAiConfig(com.kernelai.app.data.model.AiConfig(config().baseUrl, model = "fixture-model"))
        }
    }
    private suspend fun send(vm: ChatViewModel, text: String) = withContext(Dispatchers.Main) {
        vm.updateInputText(text); vm.sendMessage()
    }
    private suspend fun awaitReply(vm: ChatViewModel, text: String) = withTimeout(15_000) {
        while (vm.isStreaming.value || vm.messages.value.none { it.content == text }) delay(10)
    }
    @Test fun switchingRestoresMessagesIdsAndDraftWithoutFetchingAgain() = runBlocking {
        api.enqueue(response(text("第一段回复")))
        val vm = viewModel()
        val first = vm.uiState.value.sessionId
        send(vm, "第一段提问"); awaitReply(vm, "第一段回复")
        val ids = vm.messages.value.map { it.id }
        withContext(Dispatchers.Main) { vm.updateInputText("草稿 A"); vm.newSession() }
        val second = vm.uiState.value.sessionId
        assertNotEquals(first, second)
        assertTrue(vm.messages.value.isEmpty())
        withContext(Dispatchers.Main) { vm.updateInputText("草稿 B"); vm.switchSession(first) }
        assertEquals(ids, vm.messages.value.map { it.id })
        assertEquals("草稿 A", vm.inputText.value)
        withContext(Dispatchers.Main) { vm.switchSession(second) }
        assertEquals("草稿 B", vm.inputText.value)
        assertTrue(vm.messages.value.isEmpty())
        assertEquals(1, api.requestCount)
    }
    @Test fun cancellingOldToolDuringSwitchCannotPolluteNewConversation() = runBlocking {
        driver.block = true
        api.enqueue(response(toolResponse())); api.enqueue(response(text("新会话回复")))
        val vm = viewModel()
        val old = vm.uiState.value.sessionId
        send(vm, "旧会话读取进程")
        withContext(Dispatchers.IO) { assertTrue(driver.started.await(5, TimeUnit.SECONDS)) }
        withContext(Dispatchers.Main) { vm.newSession() }
        send(vm, "新会话提问"); awaitReply(vm, "新会话回复")
        assertEquals(listOf("新会话提问", "新会话回复"), vm.messages.value.map { it.content })
        assertFalse(vm.isStreaming.value)
        withContext(Dispatchers.Main) { vm.switchSession(old) }
        val call = vm.messages.value.first { it.role == MessageRole.ASSISTANT }.toolCalls!!.single()
        assertEquals(ToolCallStatus.CANCELLED, call.status)
        assertTrue(call.result!!.contains("结果未确认"))
        assertEquals("{\"max_count\":1}", call.arguments)
        assertFalse(vm.isStreaming.value)
    }
    @Test fun toolActivityReportsRealPhasesParametersAndElapsedTime() = runBlocking {
        driver.block = true
        api.enqueue(response(toolResponse())); api.enqueue(response(text("完成详情")))
        val vm = viewModel()
        send(vm, "列出进程")
        withContext(Dispatchers.IO) { assertTrue(driver.started.await(5, TimeUnit.SECONDS)) }
        withTimeout(5_000) { while (vm.messages.value.none { it.phase == com.kernelai.app.data.model.AssistantPhase.EXECUTING_TOOL }) delay(10) }
        val running = vm.messages.value.first { it.role == MessageRole.ASSISTANT }.toolCalls!!.single()
        assertEquals("call_demo", running.id)
        assertEquals(ToolCallStatus.RUNNING, running.status)
        assertNotNull(running.startedAtMillis)
        assertEquals("{\"max_count\":1}", running.arguments)
        driver.release.countDown()
        awaitReply(vm, "完成详情")
        val finished = vm.messages.value.first { it.role == MessageRole.ASSISTANT }.toolCalls!!.single()
        assertEquals(ToolCallStatus.COMPLETED, finished.status)
        assertTrue(finished.durationMs!! >= 0)
        assertTrue(finished.result!!.contains("4321"))
        assertEquals(com.kernelai.app.data.model.AssistantPhase.COMPLETE, vm.messages.value.last().phase)
    }
    @Test fun longConversationLoadsInBatchesAndRetainsLoadedWindowOnSwitch() = runBlocking {
        val vm = viewModel()
        repeat(42) { index ->
            api.enqueue(response(text("答复$index")))
            send(vm, "问题$index"); awaitReply(vm, "答复$index")
        }
        assertEquals(84, vm.messages.value.size)
        assertEquals(80, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.hasOlderMessages)
        val first = vm.uiState.value.sessionId
        val lastId = vm.uiState.value.messages.last().id
        withContext(Dispatchers.Main) { vm.loadOlderMessages() }
        assertEquals(84, vm.uiState.value.messages.size)
        assertEquals(lastId, vm.uiState.value.messages.last().id)
        assertFalse(vm.uiState.value.hasOlderMessages)
        withContext(Dispatchers.Main) { vm.newSession(); vm.switchSession(first) }
        assertEquals(84, vm.uiState.value.messages.size)
    }
    @Test fun deletingConversationRejectsItsLateEvents() = runBlocking {
        api.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        api.enqueue(response(text("删除后新回复")))
        val vm = viewModel()
        send(vm, "旧消息")
        withContext(Dispatchers.IO) { assertNotNull(api.takeRequest(5, TimeUnit.SECONDS)) }
        val old = vm.uiState.value.sessionId
        withContext(Dispatchers.Main) { vm.clearChat() }
        assertNotEquals(old, vm.uiState.value.sessionId)
        send(vm, "新问题"); awaitReply(vm, "删除后新回复")
        assertEquals(listOf("新问题", "删除后新回复"), vm.messages.value.map { it.content })
    }

    @Test fun interruptedArchiveRestoresPartialTextAfterCompletingMissingToolResults() = runBlocking {
        val chat = manager()
        val call = com.kernelai.app.data.model.ApiToolCall("interrupted-tool", function = com.kernelai.app.data.model.ApiFunctionCall("process_list", "{}"))
        withContext(Dispatchers.Main) {
            chat.restoreSession("restored-partial", "部分回复", 1, listOf(
                com.kernelai.app.data.model.ApiChatMessage("user", "分析"),
                com.kernelai.app.data.model.ApiChatMessage("assistant", "开始读取", toolCalls = listOf(call))), interruptedContent = "已读取部分内容")
        }
        val history = chat.getMessages("restored-partial")
        assertEquals(listOf("user", "assistant", "tool", "assistant"), history.map { it.role })
        assertEquals("interrupted-tool", history[2].toolCallId)
        assertEquals("已读取部分内容", history.last().content)
        assertEquals(0, driver.calls.get())
    }
    @Test fun defaultPolicyMakesExactlyFiveRetriesThenStops() = runBlocking {
        repeat(6) { api.enqueue(MockResponse().setResponseCode(503)) }
        val pauses = mutableListOf<Long>()
        val client = OpenAiClient(config(), retryPause = { pauses.add(it) }).also { clients.add(it) }
        val events = mutableListOf<StreamEvent>()
        client.streamChatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "hello"))).collect { events.add(it) }
        assertEquals(6, api.requestCount)
        assertEquals(listOf(1, 2, 3, 4, 5), events.filterIsInstance<StreamEvent.Retrying>().map { it.attempt })
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), pauses)
        assertEquals(1, events.filterIsInstance<StreamEvent.Error>().size)
        assertEquals(0, driver.calls.get())
    }
    @Test fun retryIsBoundedAndOnlyBeforeOutput() = runBlocking {
        api.enqueue(MockResponse().setResponseCode(503)); api.enqueue(response(text("重试成功")))
        val client = OpenAiClient(config().copy(maxRetries = 1)).also { clients.add(it) }
        val events = mutableListOf<StreamEvent>()
        client.streamChatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "hello"))).collect { events.add(it) }
        assertEquals(2, api.requestCount); assertEquals(1, events.filterIsInstance<StreamEvent.Retrying>().size)
        assertEquals("重试成功", events.filterIsInstance<StreamEvent.ContentDelta>().joinToString("") { it.delta })
        assertEquals(0, driver.calls.get())
    }
    @Test fun partialStreamNeverRetriesAndUsageAfterFinishIsPreserved() = runBlocking {
        val client = OpenAiClient(config().copy(maxRetries = 2)).also { clients.add(it) }
        api.enqueue(response(text("部分回复", end = false)))
        val partial = mutableListOf<StreamEvent>()
        client.streamChatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "hello"))).collect { partial.add(it) }
        assertTrue(partial.any { it is StreamEvent.Error }); assertFalse(partial.any { it is StreamEvent.Retrying }); assertEquals(1, api.requestCount)
        api.enqueue(response(text("完成", end = false) + chunk(buildJsonObject {}, "stop") +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":12,\"total_tokens\":333}}\n\ndata: [DONE]\n\n"))
        val events = mutableListOf<StreamEvent>()
        client.streamChatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "hello"))).collect { events.add(it) }
        val usage = events.filterIsInstance<StreamEvent.Usage>().single().usage
        assertEquals(321, usage.promptTokens); assertEquals(12, usage.completionTokens); assertFalse(usage.estimated)
    }
    @Test fun aiCanWaitForAUserAnswerWithoutCallingTheDriver() = runBlocking {
        val chat = manager()
        val question = toolDelta(0, "call_question", "ask_user", "{\"question\":\"分析哪个进程？\",\"options\":[\"微信\",\"自己的测试应用\"]}") + chunk(buildJsonObject {}, "tool_calls") + "data: [DONE]\n\n"
        api.enqueue(response(question)); api.enqueue(response(text("已收到你的选择")))
        val seen = CopyOnWriteArrayList<ChatEvent>()
        val collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) { chat.chatEvents.collect { event ->
            seen.add(event)
            if (event is ChatEvent.QuestionAsked) assertTrue(chat.answer(chat.getActiveSessionId(), "自己的测试应用"))
        } }
        try {
            withContext(Dispatchers.Main) { chat.sendMessage("先询问目标") }
            withTimeout(15000) { while (seen.none { it == ChatEvent.GenerationComplete }) delay(10) }
            assertTrue(seen.any { it is ChatEvent.QuestionAsked }); assertEquals(0, driver.calls.get())
            nextRequest(); val sent = nextRequest()["messages"]!!.jsonArray
            val answer = sent.first { it.jsonObject["role"] == JsonPrimitive("tool") }.jsonObject
            assertEquals("call_question", answer["tool_call_id"]!!.jsonPrimitive.content)
            assertTrue(answer["content"]!!.jsonPrimitive.content.contains("自己的测试应用"))
        } finally { collector.cancelAndJoin() }
    }
    @Test fun steeringIsInsertedAfterToolResultsBeforeNextModelRequest() = runBlocking {
        driver.block = true
        api.enqueue(response(toolResponse())); api.enqueue(response(text("已按补充要求分析")))
        val chat = manager()
        val result = async { converse(chat) }
        assertTrue(withContext(Dispatchers.IO) { driver.started.await(5, TimeUnit.SECONDS) })
        withContext(Dispatchers.Main) { chat.steer("只读，不要写入", emptyList(), chat.getActiveSessionId()) }
        driver.release.countDown()
        val events = result.await(); assertTrue(events.any { it == ChatEvent.GenerationComplete })
        nextRequest(); val sent = nextRequest()["messages"]!!.jsonArray.map { it.jsonObject }
        val toolIndex = sent.indexOfLast { it["role"] == JsonPrimitive("tool") }
        val steerIndex = sent.indexOfLast { it["content"] == JsonPrimitive("只读，不要写入") }
        assertTrue(toolIndex >= 0 && steerIndex > toolIndex); assertEquals(1, driver.calls.get())
    }
    @Test fun imagesAndFilesAreEncodedAsUserContentParts() = runBlocking {
        val file = java.io.File.createTempFile("qw-test", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            api.enqueue(response(text("已收到附件")))
            val client = OpenAiClient(config().copy(supportsImages = true)).also { clients.add(it) }
            val attachments = listOf(com.kernelai.app.data.model.Attachment(name = "图像.jpg", mime = "image/jpeg", size = 3, localPath = file.absolutePath),
                com.kernelai.app.data.model.Attachment(name = "日志.txt", mime = "text/plain", size = 6, localPath = "", textContent = "log内容"))
            client.streamChatCompletion(listOf(com.kernelai.app.data.model.ApiChatMessage("user", "分析附件", attachments = attachments))).collect {}
            val parts = nextRequest()["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals(3, parts.size)
            assertEquals("data:image/jpeg;base64,AQID", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertTrue(parts[2].jsonObject["text"]!!.jsonPrimitive.content.contains("log内容"))
        } finally { file.delete() }
    }
    @Test fun localArchiveRestoresTwoConversationsDraftAndModelHistory() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("qw-archive").toFile()
        try {
            val storage = com.kernelai.app.data.ChatStore(java.io.File(directory, "chats.json"))
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(tools, storage) {}.also { stores.add(ViewModelStore().apply { put("saved", it) }) }
            }
            withTimeout(5000) { while (vm.uiState.value.loading) delay(10) }
            withContext(Dispatchers.Main) { vm.updateAiConfig(com.kernelai.app.data.model.AiConfig(config().baseUrl, model = "fixture-model")) }
            api.enqueue(response(text("持久化回复")))
            withContext(Dispatchers.Main) { vm.updateInputText("本地会话一"); vm.sendMessage() }
            withTimeout(15000) { while (vm.isStreaming.value || vm.messages.value.none { it.content == "持久化回复" }) delay(10) }
            val first = vm.uiState.value.sessionId
            withContext(Dispatchers.Main) { vm.updateInputText("保留草稿"); vm.newSession(); vm.updateInputText("会话二草稿") }
            val second = vm.uiState.value.sessionId
            delay(600)
            assertEquals(2, storage.load().sessions.size)
            val restored = withContext(Dispatchers.Main) { ChatViewModel(tools, storage) {}.also { stores.add(ViewModelStore().apply { put("restored", it) }) } }
            withTimeout(5000) { while (restored.uiState.value.loading) delay(10) }
            assertEquals(second, restored.uiState.value.sessionId); assertEquals("会话二草稿", restored.inputText.value)
            withContext(Dispatchers.Main) { restored.switchSession(first) }
            assertEquals("保留草稿", restored.inputText.value); assertTrue(restored.messages.value.any { it.content == "持久化回复" })
        } finally { directory.deleteRecursively() }
    }

    private class SimulatedDriver : DriverDevice {
        @Volatile override var isOpen = true
        override val lastError: String get() = "Test driver unavailable"
        var allowOpen = true
        @Volatile var error = 0
        @Volatile var block = false
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var lastCommand = 0
        override fun open(): Boolean { isOpen = allowOpen; return isOpen }
        override fun close() { isOpen = false }
        override fun ioctl(command: Int, arg: ByteArray): Int {
            calls.incrementAndGet(); lastCommand = command; started.countDown()
            if (block) check(release.await(10, TimeUnit.SECONDS))
            if (error != 0) return error
            check(command == IoctlCommands.CMD_LIST_PROCS)
            val data = ByteBuffer.wrap(arg).order(ByteOrder.LITTLE_ENDIAN)
            check(data.getLong(24) == (arg.size - 32).toLong())
            check(data.getInt(0) in 1..512)
            data.putInt(4, 1); data.putInt(8, 1)
            data.position(32); data.putInt(4321); data.putInt(100); data.putInt(4321)
            data.put(ByteArray(16).also { "demo".toByteArray().copyInto(it) })
            data.put(ByteArray(256).also { "com.example.test".toByteArray().copyInto(it) })
            data.putLong(0); data.putInt(3)
            return 0
        }
    }
}
