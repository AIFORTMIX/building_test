package com.kernelai.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.*

/** A slow Binder/ioctl must not hold every HTTP worker until the client times out. */
internal class McpTaskDispatcher(private val delegate: McpToolExecutor, private val deadlineMs: Long = 2_000) : McpToolExecutor {
    private data class Work(val tool: String, val future: CompletableFuture<String>)
    private val jobs = LinkedHashMap<String, Work>()
    private val pool = ThreadPoolExecutor(0, 2, 5, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "qingwei-mcp-operation").apply { isDaemon = true } })
    companion object {
        val definition = McpToolExecutor.ToolDefinition("tool_operation_status", "查询已提交的慢工具任务，不重新执行原操作。operation_pending=true 时继续按 operation_id 查询；不要重新提交写入或断点。", buildJsonObject {
            put("type", "object"); putJsonObject("properties") {
                putJsonObject("operation_id") { put("type", "string") }
                putJsonObject("wait_ms") { put("type", "integer"); put("minimum", 0); put("maximum", 5000) }
                putJsonObject("_purpose") { put("type", "string") }
            }; putJsonArray("required") { add("operation_id") }
        })
    }
    override fun getToolDefinitions() = delegate.getToolDefinitions() + definition
    override suspend fun executeTool(name: String, arguments: JsonObject): String {
        if (name == "tool_operation_status") {
            val id = arguments["operation_id"]?.jsonPrimitive?.content ?: error("缺少 operation_id")
            val work = synchronized(jobs) { jobs[id] } ?: return buildJsonObject { put("is_error", true); put("message", "任务记录不存在或已过期；不要据此重放已提交操作"); put("automatic_retry_safe", false) }.toString()
            val wait = arguments["wait_ms"]?.jsonPrimitive?.longOrNull ?: 2000
            require(wait in 0..5000)
            val result = await(work, wait) ?: return pending(id, work.tool)
            return buildJsonObject {
                put("operation_id", id); put("operation_pending", false); put("status", "completed"); put("tool", work.tool)
                val parsed = runCatching { Json.parseToJsonElement(result) }.getOrNull()
                put("result", parsed ?: JsonPrimitive(result))
                if ((parsed as? JsonObject)?.get("is_error") == JsonPrimitive(true)) put("is_error", true)
            }.toString()
        }
        if (name == "tool_result_read") return delegate.executeTool(name, arguments)
        val id = UUID.randomUUID().toString(); val work = Work(name, CompletableFuture())
        synchronized(jobs) {
            while (jobs.size >= 128) { val completed = jobs.entries.firstOrNull { it.value.future.isDone } ?: error("工具任务队列已满"); jobs.remove(completed.key) }
            jobs[id] = work
        }
        try { pool.execute {
            try { work.future.complete(runBlocking { delegate.executeTool(name, arguments) }) }
            catch (error: Exception) { work.future.complete(buildJsonObject { put("is_error", true); put("message", error.message.orEmpty().take(2000)); put("automatic_retry_safe", false) }.toString()) }
        } } catch (_: RejectedExecutionException) {
            synchronized(jobs) { jobs.remove(id) }
            return buildJsonObject { put("is_error", true); put("error_code", "host_busy"); put("message", "设备工具仍有任务执行中，请稍后查询或再提交"); put("operation_submitted", false) }.toString()
        }
        return await(work, deadlineMs) ?: pending(id, name)
    }
    private fun await(work: Work, timeout: Long): String? = try { work.future.get(timeout, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { null }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
    private fun pending(id: String, tool: String) = buildJsonObject {
        put("operation_id", id); put("tool", tool); put("status", "running"); put("operation_pending", true)
        put("message", "工具仍在后台执行；调用 tool_operation_status 获取结果，不要重新提交原操作")
        put("automatic_retry_safe", false)
    }.toString()
}
