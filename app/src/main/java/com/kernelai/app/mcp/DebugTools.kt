package com.kernelai.app.mcp

import com.kernelai.app.analysis.Arm64Disassembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** One API for the App UI, its AI agent, and authorized external MCP clients. */
internal class DebugTools(private val command: (String, String) -> String) : McpToolExecutor {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<JsonObject?>(null)
    val state = _state.asStateFlow()
    companion object {
        private val actions = linkedMapOf(
            "debug_capabilities" to "capabilities", "debug_target_status" to "target_status", "debug_attach" to "attach",
            "debug_status" to "status", "debug_pause" to "pause", "debug_continue" to "continue", "debug_step" to "step",
            "debug_threads" to "threads", "debug_registers" to "registers", "debug_memory_read" to "memory_read",
            "debug_breakpoint_set" to "breakpoint_set", "debug_breakpoint_remove" to "breakpoint_remove",
            "debug_breakpoint_list" to "breakpoints", "debug_detach" to "detach", "debug_disassemble" to "memory_read")
        val definitions = actions.map { (name, action) ->
            val required = when (action) {
                "capabilities" -> emptyList()
                "target_status" -> listOf("pid")
                "attach" -> listOf("pid", "expected_identity")
                "memory_read", "breakpoint_set" -> listOf("session_id", "address")
                "breakpoint_remove" -> listOf("session_id", "breakpoint_id")
                else -> listOf("session_id")
            }
            val description = when (name) {
                "debug_capabilities" -> "检查内置 LLDB 用户态调试后端，不需要 KO；Root/SELinux 权限在附加时实际验证。"
                "debug_target_status" -> "不依赖 KO，通过 Root 取得目标 process_identity，供 debug_attach 的 expected_identity 使用。"
                "debug_attach" -> "LLDB 附加并暂停整个目标进程。先核实用户意图，使用刚取得的 expected_identity；不能暂停承载聊天/MCP 的宿主自身。返回 session_id，后续操作必须传同一 ID。"
                "debug_status" -> "查询调试状态和事件，不重放原命令。事件页 has_more_events=true 时传 next_after_event 继续；last_event 是当前状态版本，不代表本页已读完。只有 state=stopped 才能读寄存器/内存/改断点。events_truncated 表示旧事件已超出保留范围。闲置两分钟自动脱离，断线请求恢复断点并脱离。"
                "debug_pause" -> "请求暂停所有线程，之后轮询 debug_status 直到 stopped；不把已发出请求当作已经暂停。"
                "debug_continue" -> "继续进程，返回 running；signal 可显式选择注入的 Linux 信号，默认 0 不注入。随后查询 debug_status 获取断点、信号或退出事件。"
                "debug_step" -> "单步一个 thread_id（十六进制线程 ID，默认停止线程），其他线程保持暂停；返回 stepping，查询 debug_status 确认单步完成。"
                "debug_threads" -> "暂停状态下读取真实线程 ID；返回 GDB 十六进制线程标识，用于 debug_step/registers。"
                "debug_registers" -> "暂停时读取所选线程寄存器，包含真实名称、位数、原始字节和可用性；数值按目标字节序解释。不把硬件断点旧快照当成当前寄存器。"
                "debug_memory_read" -> "所有线程暂停后读取真实内存，size=1..4096；地址为无损字符串。未暂停或进程身份变化时拒绝。"
                "debug_disassemble" -> "所有线程暂停后读取并用 Capstone 解码小端 ARM64 指令，count=1..256；先核实目标是 ARM64，不能把 x86/ARM32 字节解释为 ARM64。"
                "debug_breakpoint_set" -> "暂停后设置 LLDB 断点，type=0 软件执行/1 硬件执行/2 写监视/3 读监视/4 读写监视。size 为字节；ARM64 执行断点设为 4，x86 执行为 1。硬件不支持时明确失败，不偷偷替换类型。"
                "debug_breakpoint_remove" -> "移除本会话的 breakpoint_id；不能用旧会话 ID 操作新目标。"
                "debug_breakpoint_list" -> "列出此 LLDB 会话的真实已确认断点；和原驱动全局硬件断点列表分别管理。"
                else -> "恢复本会话的软件断点并从目标脱离，保持目标继续运行。检查 cleanup_confirmed；未确认时不能声称已完成恢复。"
            }
            McpToolExecutor.ToolDefinition(name, description, buildJsonObject {
                put("type", "object"); putJsonObject("properties") {
                    listOf("session_id", "expected_identity", "thread_id", "address", "breakpoint_id", "_purpose").forEach { key -> putJsonObject(key) { put("type", "string") } }
                    putJsonObject("pid") { put("type", "integer"); put("minimum", 2) }
                    putJsonObject("after_event") { put("type", "integer"); put("minimum", 0) }
                    putJsonObject("size") { put("type", "integer"); put("minimum", 1); put("maximum", 4096) }
                    putJsonObject("count") { put("type", "integer"); put("minimum", 1); put("maximum", 256) }
                    putJsonObject("type") { put("type", "integer"); put("minimum", 0); put("maximum", 4) }
                    putJsonObject("signal") { put("type", "integer"); put("minimum", 0); put("maximum", 64) }
                }; put("required", JsonArray(required.map(::JsonPrimitive))); put("additionalProperties", false)
            })
        }
        val mutating = setOf("debug_attach", "debug_pause", "debug_continue", "debug_step", "debug_breakpoint_set", "debug_breakpoint_remove", "debug_detach")
    }
    override fun getToolDefinitions() = definitions
    override suspend fun executeTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            val action = actions[name] ?: error("未知调试工具")
            val raw = if (name != "debug_disassemble") command(action, arguments.toString())
            else {
                val count = arguments["count"]?.jsonPrimitive?.intOrNull ?: 16
                require(count in 1..256)
                val result = Json.parseToJsonElement(command("memory_read", JsonObject(arguments + ("size" to JsonPrimitive(count * 4))).toString())).jsonObject
                if (result["is_error"] == JsonPrimitive(true)) result.toString() else {
                    require(result["architecture"] == JsonPrimitive("arm64") && result["endian"] == JsonPrimitive("little")) { "反汇编需要已确认的小端 ARM64 目标，不解码其他架构的字节" }
                    val hex = result["data"]!!.jsonPrimitive.content
                    require(hex.length == count * 8)
                    val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    val rawAddress = arguments["address"]!!.jsonPrimitive.content
                    val address = if (rawAddress.startsWith("0x", true)) rawAddress.drop(2).toULong(16) else rawAddress.toULong()
                    buildJsonObject {
                        result.forEach { (key, value) -> put(key, value) }
                        Arm64Disassembler.decode(bytes, address, count).forEach { (key, value) -> put(key, value) }
                    }.toString()
                }
            }
            val parsed = Json.parseToJsonElement(raw).jsonObject
            if (parsed["state"] != null && parsed["session_id"] != null) _state.update { current ->
                if (current == null) parsed else {
                    val same = current["session_id"] == parsed["session_id"]
                    val newer = if (same) (parsed["last_event"]?.jsonPrimitive?.longOrNull ?: -1) >= (current["last_event"]?.jsonPrimitive?.longOrNull ?: -1)
                        else (parsed["session_created_at"]?.jsonPrimitive?.longOrNull ?: -1) > (current["session_created_at"]?.jsonPrimitive?.longOrNull ?: -1)
                    if (newer) parsed else current
                }
            }
            else if (name == "debug_status" && parsed["is_error"] == JsonPrimitive(true) && _state.value?.get("session_id") == arguments["session_id"]) {
                _state.value = JsonObject(_state.value.orEmpty() + mapOf("state" to JsonPrimitive("unavailable"), "old_addresses_valid" to JsonPrimitive(false), "is_error" to JsonPrimitive(true), "message" to (parsed["message"] ?: JsonPrimitive("调试状态不可确认"))))
            }
            raw
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            buildJsonObject { put("is_error", true); put("message", error.message.orEmpty().take(4000)); put("automatic_retry_safe", false) }.toString()
        }
    }
}
