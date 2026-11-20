package com.kernelai.app.mcp

import kotlinx.serialization.json.*

/**
 * MCP 工具定义 - 14 个工具对应内核驱动的 ioctl 命令
 */
object McpToolDefinitions {
    
    val ALL_TOOLS = listOf(
        ToolDef(
            name = "process_list",
            description = "分页查询真实进程。指定 filter 时自动跨原始进程页搜索；max_count 是返回匹配条数上限（1..512）。检查 has_more/next_offset/search_complete，空页不代表应用未运行。微信完整包名为 com.tencent.mm，可用 process_resolve 直接核对。",
            params = listOf(
                Param("offset", "integer", "原始进程游标；继续搜索时使用上次 next_offset", false),
                Param("max_count", "integer", "最大返回数量", false),
                Param("filter", "string", "匹配 cmdline/comm 的关键字，例 tencent 或 com.tencent.mm；不是应用中文显示名", false)
            )
        ),
        ToolDef(
            name = "process_info",
            description = "获取指定进程的详细信息，包括加载的模块列表、内存映射概览、线程列表。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pkg_name", "string", "目标包名（与 pid 二选一）", false)
            )
        ),
        ToolDef(
            name = "memory_read",
            description = "读取目标进程指定地址的内存内容。返回十六进制字节数据。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pkg_name", "string", "目标包名", false),
                Param("address", "string", "目标地址（十六进制字符串）", true),
                Param("size", "integer", "读取字节数", true)
            )
        ),
        ToolDef(
            name = "memory_write",
            description = "向目标进程指定地址写入数据。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pkg_name", "string", "目标包名", false),
                Param("address", "string", "目标地址（十六进制）", true),
                Param("data", "string", "写入数据（十六进制字符串）", true)
            )
        ),
        ToolDef(
            name = "memory_search",
            description = "在目标进程地址空间中搜索字节模式。按精确字节匹配。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pattern", "string", "搜索模式（十六进制）", true),
                Param("start_addr", "string", "搜索起始地址", false),
                Param("end_addr", "string", "搜索结束地址", false),
                Param("max_results", "integer", "最大结果数", false)
            )
        ),
        ToolDef(
            name = "module_list",
            description = "列出目标进程加载的所有共享库/模块。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pkg_name", "string", "目标包名", false)
            )
        ),
        ToolDef(
            name = "memory_map",
            description = "读取目标进程 VMA（最多 1024 条）。检查 may_be_truncated；受限列表不能证明其他地址未映射。VM_READ=1、VM_WRITE=2、VM_EXEC=4。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pkg_name", "string", "目标包名", false),
                Param("filter_executable", "boolean", "仅返回可执行段", false)
            )
        ),
        ToolDef(
            name = "breakpoint_set",
            description = "在目标地址设置断点。支持硬件断点（执行/读/写/读写）和软件断点。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("address", "string", "断点地址（十六进制）", true),
                Param("type", "string", "断点类型: hw_execute/hw_read/hw_write/hw_access/sw", true),
                Param("length", "integer", "监视长度（仅数据断点）", false)
            )
        ),
        ToolDef(
            name = "breakpoint_remove",
            description = "移除指定断点。",
            params = listOf(
                Param("bp_id", "integer", "断点 ID/索引", true),
                Param("type", "string", "断点类型: hw/sw", true)
            )
        ),
        ToolDef(
            name = "breakpoint_list",
            description = "列出所有已设置的断点及其状态。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false)
            )
        ),
        ToolDef(
            name = "register_read",
            description = "读取目标进程在断点命中时的寄存器快照（ARM64 X0-X30, SP, PC, PSTATE）。",
            params = listOf(
                Param("bp_id", "integer", "断点 ID", true)
            )
        ),
        ToolDef(
            name = "disassemble_at",
            description = "读取并用 Capstone 解码目标的小端 ARM64 指令，返回原始字节、助记符、操作数和立即跳转目标。count=1..256，地址四字节对齐；先核实 expected_identity。不推断目标是 ARM32，也不伪造未知编码。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("address", "string", "起始地址（十六进制）", true),
                Param("count", "integer", "读取指令数量", false)
            )
        ),
        ToolDef(
            name = "callstack_capture",
            description = "捕获目标进程在断点命中时的调用栈（ARM64 FP 链回溯）。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("bp_id", "integer", "触发断点 ID", true),
                Param("max_frames", "integer", "最大帧数", false)
            )
        ),
        ToolDef(
            name = "thread_list",
            description = "列出目标进程的所有线程。",
            params = listOf(
                Param("pid", "integer", "目标进程 PID", false),
                Param("pkg_name", "string", "目标包名", false)
            )
        )
    ) + MemoryToolDefinitions.tools
    
    data class ToolDef(
        val name: String,
        val description: String,
        val params: List<Param>,
        val schema: JsonObject? = null
    )
    
    data class Param(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean
    )
    
    fun toJsonSchema(tool: ToolDef): JsonObject {
        val base = tool.schema ?: buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                tool.params.forEach { param ->
                    putJsonObject(param.name) {
                        put("type", param.type)
                        put("description", param.description)
                    }
                }
            }
            val required = tool.params.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) {
                putJsonArray("required") {
                    required.forEach { add(it) }
                }
            }
        }
        return buildJsonObject {
            base.forEach { (key, value) -> if (key != "properties") put(key, value) }
            putJsonObject("properties") {
                base["properties"]?.jsonObject?.forEach { (key, value) -> put(key, value) }
                putJsonObject("_purpose") { put("type", "string"); put("description", "用简短中文说明这次调用是为了做什么，供执行卡片展示"); put("maxLength", 160) }
                if (tool.name in ProcessGuard.TARGET_TOOLS) putJsonObject("expected_identity") {
                    put("type", "string"); put("description", "来自 process_status 的 process_identity；禁止用新身份搭配旧地址"); put("maxLength", 80)
                }
            }
            if (tool.name in ProcessGuard.ADDRESS_TOOLS) put("required", JsonArray((base["required"]?.jsonArray.orEmpty() + JsonPrimitive("expected_identity")).distinct()))
        }
    }
}
