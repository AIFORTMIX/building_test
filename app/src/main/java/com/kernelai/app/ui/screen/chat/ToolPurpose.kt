package com.kernelai.app.ui.screen.chat

import com.kernelai.app.data.model.ToolCallInfo
import kotlinx.serialization.json.*

internal fun toolPurpose(call: ToolCallInfo): String {
    val args = runCatching { if (call.arguments.length <= 32000) Json.parseToJsonElement(call.arguments).jsonObject else null }.getOrNull()
    fun arg(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull?.take(100).orEmpty()
    arg("_purpose").takeIf { it.isNotBlank() }?.let { return it.take(160) }
    val target = arg("pkg_name").ifBlank { arg("pid").takeIf { it.isNotEmpty() }?.let { "PID $it" }.orEmpty() }
    return when (call.name) {
        "process_list" -> arg("filter").let { if (it.isBlank()) "查询设备正在运行的进程" else "查找与 $it 相关的进程" }
        "process_resolve" -> "核对 ${arg("pkg_name")} 是否运行"
        "process_info" -> "查看 $target 的模块、映射和线程"
        "module_list" -> "查看 $target 加载的模块"
        "module_address" -> "计算 ${arg("module")} 的映射地址与偏移"
        "memory_map" -> "查看 $target 的内存映射"
        "pointer_chain" -> "按指定偏移逐步追踪 $target 的指针链"
        "address_calculate" -> "计算地址与偏移并检查溢出"
        "memory_read_struct" -> "按字段布局解析 $target 的内存结构"
        "memory_read_string" -> "读取并解码 $target 的字符串"
        "memory_read_typed" -> "按 ${arg("type")} 类型读取 $target 的内存"
        "memory_read", "memory_inspect" -> "读取 $target 在 ${arg("address")} 的内存"
        "memory_search" -> "在 $target 中搜索指定字节模式"
        "memory_write" -> "向 $target 的指定地址写入数据"
        "breakpoint_set" -> "在 ${arg("address")} 设置断点"
        "breakpoint_remove" -> "移除编号 ${arg("bp_id")} 的断点"
        "breakpoint_list" -> "查询驱动当前的硬件断点"
        "register_read" -> "读取断点命中时的寄存器快照"
        "thread_list" -> "查询 $target 的线程"
        "disassemble_at" -> "读取 $target 的指令字节供分析"
        "callstack_capture" -> "获取断点命中时的调用栈"
        "ask_user" -> "确认分析所需的信息"
        else -> if (call.name.isBlank()) "正在准备下一项操作" else "执行 ${call.name} 并检查结果"
    }
}
