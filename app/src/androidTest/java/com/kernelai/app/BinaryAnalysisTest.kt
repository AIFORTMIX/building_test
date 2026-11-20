package com.kernelai.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.analysis.Arm64Disassembler
import com.kernelai.app.analysis.ElfFile
import com.kernelai.app.data.WorkspaceRepository
import com.kernelai.app.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import com.kernelai.app.ai.*

class BinaryAnalysisTest {
    @Test fun aiCanCallRealBinaryToolsThroughHttpAndReceiveDecodedInstructions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "binary-ai-${UUID.randomUUID()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = directory }
        val fixture = File(directory, "workspaces/files/default/fixture.so").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("analysis-fixture.so").use { source -> fixture.outputStream().use { source.copyTo(it) } }
        val hash = com.kernelai.app.data.WorkspacePaths.hash(fixture.readBytes())
        val address = ElfFile(fixture).use { it.query("functions", 0, 100, "qingwei_fixture_sum")["entries"]!!.jsonArray.first().jsonObject["address"]!!.jsonPrimitive.content }
        val definitions = WorkspaceBinaryTools(WorkspaceRepository(scoped), File(directory, "snapshots"))
        val runtime = McpRuntime(definitions).apply { start(0) }
        val transport = McpHttpToolExecutor(runtime::endpoint, definitions.getToolDefinitions())
        val api = MockWebServer().apply { start() }
        fun tool(name: String, id: String, arguments: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
            putJsonArray("choices") { addJsonObject {
                put("finish_reason", "tool_calls"); putJsonObject("message") {
                    put("role", "assistant"); put("content", JsonNull); putJsonArray("tool_calls") { addJsonObject {
                        put("id", id); put("type", "function"); putJsonObject("function") { put("name", name); put("arguments", arguments.toString()) }
                    } }
                }
            } }
        }.toString())
        api.enqueue(tool("workspace_binary_info", "elf-info", buildJsonObject { put("workspace_id", "default"); put("path", "fixture.so") }))
        api.enqueue(tool("workspace_binary_disassemble", "elf-decode", buildJsonObject {
            put("workspace_id", "default"); put("path", "fixture.so"); put("expected_sha256", hash); put("from", "virtual_address"); put("value", address); put("count", 4)
        }))
        api.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"role":"assistant","content":"已读取 ELF 并完成真实反汇编"},"finish_reason":"stop"}]}"""))
        val config = AiConfig(baseUrl = api.url("/v1").toString().trimEnd('/'), model = "binary-fixture")
        val client = OpenAiClient(config)
        val manager = AiChatManager(client, transport, config, workspaceScope = { "default" })
        val results = mutableListOf<ChatEvent.ToolCallResult>()
        val done = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            manager.chatEvents.collect { event ->
                if (event is ChatEvent.ToolCallResult) results += event
                if (event == ChatEvent.GenerationComplete) done.complete(Unit)
                if (event is ChatEvent.Error) done.completeExceptionally(AssertionError(event.toString()))
            }
        }
        try {
            withContext(Dispatchers.Main) { manager.sendMessage("分析默认工作区 fixture.so 的 ARM64 指令") }
            withTimeout(20000) { done.await() }
            assertEquals(2, results.size); assertTrue(results.none { it.isError })
            val requests = List(3) { Json.parseToJsonElement(requireNotNull(api.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()).jsonObject }
            assertTrue(requests.first()["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"] == JsonPrimitive("workspace_binary_disassemble") })
            val returned = requests.last()["messages"]!!.jsonArray.filter { it.jsonObject["role"] == JsonPrimitive("tool") }.map { it.jsonObject["content"]!!.jsonPrimitive.content }
            assertTrue(returned.any { it.contains(hash) && it.contains("ELF64") })
            assertTrue(returned.any { it.contains("Capstone 5.0.6") && it.contains("mnemonic") })
        } finally {
            collector.cancelAndJoin(); withContext(Dispatchers.Main) { manager.destroy() }
            client.close(); transport.close(); runtime.stop(); api.shutdown(); directory.deleteRecursively()
        }
    }

    @Test fun nativeArm64DecoderReturnsRealInstructionsAndLosslessBranchTargets() {
        val code = byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte(), 0x02, 0, 0, 0x14, 0xc0.toByte(), 0x03, 0x5f, 0xd6.toByte(), -1, -1, -1, -1)
        val base = 0xffff000000001000uL
        val result = Arm64Disassembler.decode(code, base, 4)
        val rows = result["instructions"]!!.jsonArray
        assertEquals(4, rows.size)
        assertEquals("nop", rows[0].jsonObject["mnemonic"]!!.jsonPrimitive.content)
        assertEquals("0xffff00000000100c", rows[1].jsonObject["target"]!!.jsonPrimitive.content)
        assertEquals("ret", rows[2].jsonObject["mnemonic"]!!.jsonPrimitive.content)
        assertFalse(rows[3].jsonObject["decoded"]!!.jsonPrimitive.boolean)
        assertEquals("0xffff000000001010", result["next_address"]!!.jsonPrimitive.content)
    }

    @Test fun httpWorkspaceAnalysisChecksVersionsPermissionsAndCleansTemporaryFiles(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "binary-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scoped = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        val repository = WorkspaceRepository(scoped)
        val cache = File(directory, "snapshots")
        val tools = WorkspaceBinaryTools(repository, cache)
        val runtime = McpRuntime(tools); runtime.start(0)
        val http = McpHttpToolExecutor(runtime::endpoint, tools.getToolDefinitions())
        val fixture = File(directory, "workspaces/files/default/fixture.so").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("analysis-fixture.so").use { source -> fixture.outputStream().use { source.copyTo(it) } }
        suspend fun call(name: String, fields: Map<String, String> = emptyMap()): JsonObject = Json.parseToJsonElement(http.executeTool(name, buildJsonObject {
            put("workspace_id", "default"); put("path", "fixture.so"); fields.forEach { (key, value) -> put(key, value) }
        })).jsonObject
        suspend fun failure(name: String, fields: Map<String, String>): JsonObject {
            try { call(name, fields); error("Invalid binary request accepted") }
            catch (error: McpToolFailure) { return Json.parseToJsonElement(error.result).jsonObject.also { assertEquals(JsonPrimitive(true), it["is_error"]) } }
        }
        try {
            val info = call("workspace_binary_info")
            assertEquals(183, info["machine"]!!.jsonPrimitive.int)
            val hash = info["sha256"]!!.jsonPrimitive.content
            val exports = call("workspace_binary_query", mapOf("kind" to "exports", "query" to "qingwei_fixture_sum", "expected_sha256" to hash))
            val function = exports["entries"]!!.jsonArray.single().jsonObject
            val decoded = call("workspace_binary_disassemble", mapOf("from" to "virtual_address", "value" to function["address"]!!.jsonPrimitive.content, "expected_sha256" to hash))
            assertTrue(decoded["instructions"]!!.jsonArray.any { it.jsonObject["decoded"] == JsonPrimitive(true) })
            assertTrue(cache.listFiles().orEmpty().isEmpty())
            fixture.appendBytes(byteArrayOf(0))
            assertTrue(failure("workspace_binary_info", mapOf("expected_sha256" to hash))["message"]!!.jsonPrimitive.content.contains("binary_changed"))
            failure("workspace_binary_query", mapOf("kind" to "symbols", "offset" to "1"))
            failure("workspace_binary_info", mapOf("path" to "../fixture.so"))
            val outside = File(directory, "outside.so").apply { writeBytes(fixture.readBytes()) }
            android.system.Os.symlink(outside.path, File(fixture.parentFile, "link.so").path)
            failure("workspace_binary_info", mapOf("path" to "link.so"))
            repository.enable("default", false)
            failure("workspace_binary_info", emptyMap())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        } finally { http.close(); runtime.stop(); directory.deleteRecursively() }
    }

    @Test fun nativeDecoderRejectsInvalidSizesAndAddressesBeforeAllocation() {
        listOf(0, 3, 1028).forEach { size ->
            try { Arm64Disassembler.decode(ByteArray(size), 0u, 1); fail() } catch (_: IllegalArgumentException) { }
        }
        try { Arm64Disassembler.decode(ByteArray(4), ULong.MAX_VALUE - 3u, 1); fail() } catch (_: IllegalArgumentException) { }
        try { Arm64Disassembler.decode(ByteArray(4), 1u, 1); fail() } catch (_: IllegalArgumentException) { }
    }
}
