package com.kernelai.app

import com.kernelai.app.ai.*
import com.kernelai.app.mcp.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DebuggerAiTest {
    @Test fun aiUsesActualRootDebugResultsToAttachReadRegistersAndDetachOverMcp(): Unit = runBlocking {
        DebugRootHost().use { host -> DebugFixture().use { fixture ->
            val runtime = McpRuntime(host.tools).apply { start(0) }
            val transport = McpHttpToolExecutor(runtime::endpoint, host.tools.getToolDefinitions())
            val api = MockWebServer()
            val stage = AtomicInteger()
            val session = AtomicReference<String>()
            fun tool(name: String, fields: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                putJsonArray("choices") { addJsonObject {
                    put("finish_reason", "tool_calls"); putJsonObject("message") {
                        put("role", "assistant"); put("content", JsonNull)
                        putJsonArray("tool_calls") { addJsonObject {
                            put("id", "debug-${stage.get()}"); put("type", "function")
                            putJsonObject("function") { put("name", name); put("arguments", fields.toString()) }
                        } }
                    }
                } }
            }.toString())
            api.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    fun lastResult() = Json.parseToJsonElement(body["messages"]!!.jsonArray.last { it.jsonObject["role"] == JsonPrimitive("tool") }.jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                    return when (stage.getAndIncrement()) {
                        0 -> tool("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
                        1 -> tool("debug_attach", buildJsonObject { put("pid", fixture.pid); put("expected_identity", lastResult()["process_identity"]!!) })
                        2 -> {
                            val attached = lastResult()
                            check(attached["state"] == JsonPrimitive("stopped"))
                            session.set(attached["session_id"]!!.jsonPrimitive.content)
                            tool("debug_registers", buildJsonObject { put("session_id", session.get()) })
                        }
                        3 -> {
                            check(lastResult()["registers"]!!.jsonArray.any { it.jsonObject["generic"] == JsonPrimitive("pc") && it.jsonObject["available"] == JsonPrimitive(true) })
                            tool("debug_detach", buildJsonObject { put("session_id", session.get()) })
                        }
                        else -> {
                            check(lastResult()["cleanup_confirmed"] == JsonPrimitive(true))
                            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"已读取当前寄存器并脱离，目标继续运行"},"finish_reason":"stop"}]}""")
                        }
                    }
                }
            }
            api.start()
            val config = AiConfig(baseUrl = api.url("/v1").toString().trimEnd('/'), model = "debug-fixture")
            val client = OpenAiClient(config)
            val manager = AiChatManager(client, transport, config)
            val done = CompletableDeferred<Unit>(); val results = mutableListOf<ChatEvent.ToolCallResult>()
            val collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                manager.chatEvents.collect { event ->
                    if (event is ChatEvent.ToolCallResult) results += event
                    if (event == ChatEvent.GenerationComplete) done.complete(Unit)
                    if (event is ChatEvent.Error) done.completeExceptionally(AssertionError(event.toString()))
                }
            }
            try {
                withContext(Dispatchers.Main) { manager.sendMessage("调试我的测试进程，暂停后读取寄存器，最后脱离") }
                withTimeout(25000) { done.await() }
                assertEquals(4, results.size); assertTrue(results.none { it.isError }); assertEquals(5, api.requestCount)
                assertEquals(0, fixture.tracerPid())
            } finally {
                collector.cancelAndJoin(); withContext(Dispatchers.Main) { manager.destroy() }
                session.get()?.let { host.tools.executeTool("debug_detach", buildJsonObject { put("session_id", it) }) }
                client.close(); transport.close(); runtime.stop(); api.shutdown()
            }
        } }
    }
}
