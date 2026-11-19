package com.kernelai.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ToolReceiptTest {
    @Test fun fullUnicodeResultSurvivesRestartAndEveryPageCanBeReassembled() {
        val root = Files.createTempDirectory("receipt").toFile()
        try {
            val raw = "🙂汉字\n".repeat(15000) + "唯一尾部"
            val id = ToolResultStore(root).save("fixture", raw)
            val store = ToolResultStore(root)
            val rebuilt = StringBuilder(); var offset = 0
            do { val page = store.page(id, offset, 257); rebuilt.append(page["text"]!!.jsonPrimitive.content); offset = page["next_offset"]!!.jsonPrimitive.int } while (page["has_more"]!!.jsonPrimitive.boolean)
            assertEquals(raw, rebuilt.toString())
            assertTrue(store.page(id, query = "唯一尾部")["match_found"]!!.jsonPrimitive.boolean)
            assertFalse(store.page(id, query = "没有这个词")["match_found"]!!.jsonPrimitive.boolean)
            assertThrows(IllegalArgumentException::class.java) { store.localFile("../../secrets") }
            store.remove(id)
            assertThrows(IllegalArgumentException::class.java) { store.localFile(id) }
        } finally { root.deleteRecursively() }
    }
    @Test fun largeErrorFlagsAndSourceLimitsRemainMachineReadable() = runBlocking {
        val root = Files.createTempDirectory("receipt-error").toFile()
        try {
            val raw = buildJsonObject { put("padding", "escaped \"is_error\":false " + "x".repeat(80000)); put("is_error", true); put("requires_rebind", true); put("old_addresses_valid", false); put("may_be_truncated", true) }.toString()
            val store = ToolResultStore(root)
            val page = store.page(store.save("test", raw))
            assertEquals(JsonPrimitive(true), page["is_error"])
            assertEquals(JsonPrimitive(true), page["requires_rebind"])
            assertEquals(JsonPrimitive(false), page["old_addresses_valid"])
            assertEquals(JsonPrimitive(true), page["source_may_be_truncated"])
        } finally { root.deleteRecursively() }
    }
    @Test fun readingAdditionalPagesNeverRepeatsTheOriginalOperation() = runBlocking {
        val root = Files.createTempDirectory("receipt-operation").toFile(); val calls = AtomicInteger()
        try {
            val delegate = object : McpToolExecutor {
                override fun getToolDefinitions() = emptyList<McpToolExecutor.ToolDefinition>()
                override suspend fun executeTool(name: String, arguments: JsonObject): String { calls.incrementAndGet(); return "x".repeat(40000) }
            }
            val tools = PagedToolExecutor(delegate, ToolResultStore(root))
            val first = Json.parseToJsonElement(tools.executeTool("write-once", JsonObject(emptyMap()))).jsonObject
            tools.executeTool("tool_result_read", buildJsonObject { put("result_id", first["result_id"]!!); put("offset", first["next_offset"]!!) })
            assertEquals(1, calls.get())
        } finally { root.deleteRecursively() }
    }
    @Test fun slowOperationsReturnHandlesWhileControlRequestsStayResponsiveAndPollDoesNotReplay() = runBlocking {
        val release = CountDownLatch(1); val started = CountDownLatch(1); val calls = AtomicInteger()
        val delegate = object : McpToolExecutor {
            override fun getToolDefinitions() = emptyList<McpToolExecutor.ToolDefinition>()
            override suspend fun executeTool(name: String, arguments: JsonObject): String {
                calls.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return "{\"saved\":true}"
            }
        }
        val tools = McpTaskDispatcher(delegate, 20)
        try {
            val pending = Json.parseToJsonElement(tools.executeTool("write-once", JsonObject(emptyMap()))).jsonObject
            assertEquals(JsonPrimitive(true), pending["operation_pending"])
            val args = buildJsonObject { put("operation_id", pending["operation_id"]!!); put("wait_ms", 0) }
            repeat(3) { assertTrue(tools.executeTool("tool_operation_status", args).contains("running")) }
            release.countDown()
            val done = Json.parseToJsonElement(tools.executeTool("tool_operation_status", JsonObject(args + ("wait_ms" to JsonPrimitive(1000))))).jsonObject
            assertEquals(JsonPrimitive(false), done["operation_pending"])
            assertEquals(JsonPrimitive(true), done["result"]!!.jsonObject["saved"])
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }
}
