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
    @Test fun jsonResponseToStreamingRequestIsHandled() = runBlocking {
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"role":"assistant","content":"普通 JSON 🙂"},"finish_reason":"stop"}]}"""))
        val events = converse(manager())
        assertEquals("普通 JSON 🙂", events.filterIsInstance<ChatEvent.StreamingText>().joinToString("") { it.delta })
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
