package com.kernelai.app.mcp

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Durable full receipts. Reaching the disk budget reports an error; old receipts are never silently evicted. */
class ToolResultStore(private val directory: File) {
    companion object { const val PAGE = 12_000; const val MAX_RESULT_BYTES = 8 * 1024 * 1024; const val MAX_DISK_BYTES = 128L * 1024 * 1024 }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "结果 ID 无效" }
        return File(directory, "$id.txt")
    }
    @Synchronized fun save(tool: String, raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RESULT_BYTES) { "工具完整结果超过 8 MiB，需缩小查询范围或使用源分页" }
        directory.mkdirs()
        require(directory.listFiles().orEmpty().sumOf { it.length() } + bytes.size <= MAX_DISK_BYTES) { "完整工具结果存储达到 128 MiB，请先导出并清理历史结果；未自动丢弃旧结果" }
        val id = UUID.randomUUID().toString()
        val output = file(id)
        FileOutputStream(output).use { it.write(bytes); it.fd.sync() }
        return id
    }
    fun localFile(id: String) = file(id).also { require(it.isFile) { "完整工具结果不存在，请核对结果 ID" } }
    /** UI-only explicit removal; no tool or automatic cleanup can discard saved receipts. */
    @Synchronized fun remove(id: String) { require(localFile(id).delete()) { "完整结果无法删除" } }
    @Synchronized fun page(id: String, offset: Int = 0, length: Int = PAGE, query: String? = null): JsonObject {
        val target = localFile(id); require(target.length() <= MAX_RESULT_BYTES)
        val value = target.readText(Charsets.UTF_8)
        require(offset in 0..value.length && length in 256..24_000)
        require(query == null || query.length in 1..256)
        var begin = if (query == null) offset else value.indexOf(query, offset, ignoreCase = true).let { if (it < 0) value.length else maxOf(offset, it - 200) }
        if (begin > 0 && begin < value.length && value[begin].isLowSurrogate() && value[begin - 1].isHighSurrogate()) begin--
        var end = minOf(value.length, begin + length)
        if (end < value.length && end > begin && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end++
        val source = receiptMetadata(value)
        return buildJsonObject {
            put("format", "paged_tool_result"); put("result_id", id); put("offset", begin); put("total_chars", value.length)
            put("text", value.substring(begin, end)); put("has_more", end < value.length); put("next_offset", end)
            put("full_result_saved", true); put("fragment_is_complete_json", begin == 0 && end == value.length)
            if (query != null) put("match_found", begin < value.length)
            put("sha256", MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
            if (source["is_error"] == JsonPrimitive(true)) put("is_error", true)
            listOf("requires_rebind", "old_addresses_valid", "error_code", "operation_may_have_completed", "automatic_retry_safe", "pid", "process_identity").forEach { key -> source[key]?.let { put(key, it) } }
            listOf("may_be_truncated", "complete", "has_more", "next_offset", "limit_reached").forEach { key -> source[key]?.let { put("source_$key", it) } }
            listOf("revision", "kind", "total_count", "page_complete").forEach { key -> source[key]?.let { put("source_$key", it) } }
            put("read_more", "继续调用 tool_result_read，传 result_id 和 next_offset；这是完整原文的片段，不可把片段当成完整 JSON 或完整查询。")
        }
    }
}

internal class PagedToolExecutor(private val delegate: McpToolExecutor, private val results: ToolResultStore) : McpToolExecutor {
    companion object {
        val definition = McpToolExecutor.ToolDefinition("tool_result_read", "分段读取已保存的完整工具返回，不重新执行原工具。用 next_offset 继续，或 query 定位关键词；源数据自身有限制时仍须源分页。", buildJsonObject {
            put("type", "object"); putJsonObject("properties") {
                putJsonObject("result_id") { put("type", "string") }; putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
                putJsonObject("max_chars") { put("type", "integer"); put("minimum", 256); put("maximum", 24000) }
                putJsonObject("query") { put("type", "string") }; putJsonObject("_purpose") { put("type", "string") }
            }; putJsonArray("required") { add("result_id") }
        })
    }
    override fun getToolDefinitions() = delegate.getToolDefinitions() + definition
    override suspend fun executeTool(name: String, arguments: JsonObject): String {
        if (name == "tool_result_read") return results.page(arguments["result_id"]?.jsonPrimitive?.content ?: error("缺少 result_id"),
            arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0, arguments["max_chars"]?.jsonPrimitive?.intOrNull ?: ToolResultStore.PAGE,
            arguments["query"]?.jsonPrimitive?.contentOrNull).toString()
        val raw = delegate.executeTool(name, arguments)
        if (raw.length <= ToolResultStore.PAGE) return raw
        return try { results.page(results.save(name, raw)).toString() }
        catch (error: Exception) { buildJsonObject {
            put("is_error", true); put("full_result_saved", false); put("message", "原工具已执行，但完整结果归档失败：${error.message}")
            put("operation_may_have_completed", true); put("automatic_retry_safe", false)
            put("preview", raw.take(2000)); put("preview_only", true)
        }.toString() }
    }
}
