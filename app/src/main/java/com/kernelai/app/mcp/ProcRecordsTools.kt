package com.kernelai.app.mcp

import com.kernelai.app.driver.DriverDevice
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class ProcRecordsTools(private val device: DriverDevice) : McpToolExecutor {
    companion object {
        val definition = McpToolExecutor.ToolDefinition("process_records", "通过已授权的 Root 服务只读分页查询 maps/modules/threads。弥补 memory_map/module_list/thread_list 的驱动列表上限，返回完整路径。modules 是文件映射段，非解析后的 ELF。继续时传回 process_identity 和 revision，变更后从头刷新。", buildJsonObject {
            put("type", "object"); putJsonObject("properties") {
                putJsonObject("pid") { put("type", "integer"); put("minimum", 1) }
                putJsonObject("kind") { put("type", "string"); put("enum", JsonArray(listOf("maps", "modules", "threads").map(::JsonPrimitive))) }
                putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 200) }
                listOf("process_identity", "revision", "_purpose").forEach { key -> putJsonObject(key) { put("type", "string") } }
            }; put("required", JsonArray(listOf("pid", "kind").map(::JsonPrimitive)))
        })
    }
    override fun getToolDefinitions() = listOf(definition)
    override suspend fun executeTool(name: String, arguments: JsonObject): String = try {
        require(name == definition.name)
        check(device.isOpen || device.open()) { device.lastError ?: "Root 驱动连接不可用" }
        device.processRecords(arguments["pid"]!!.jsonPrimitive.int, arguments["kind"]!!.jsonPrimitive.content,
            arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0, arguments["limit"]?.jsonPrimitive?.intOrNull ?: 100,
            arguments["process_identity"]?.jsonPrimitive?.contentOrNull, arguments["revision"]?.jsonPrimitive?.contentOrNull)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        buildJsonObject { put("is_error", true); put("message", error.message.orEmpty()); put("page_complete", false); put("automatic_retry_safe", false); put("old_addresses_valid", false) }.toString()
    }
}
