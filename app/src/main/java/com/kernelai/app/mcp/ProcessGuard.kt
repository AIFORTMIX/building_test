package com.kernelai.app.mcp

import com.kernelai.app.driver.ProcessSnapshot
import kotlinx.serialization.json.*

internal class TargetProcessChanged(val snapshot: ProcessSnapshot, val reason: String) : Exception(reason)

internal class ProcessGuard(private val inspect: (Int) -> ProcessSnapshot) {
    fun capture(pid: Int, expected: String? = null): ProcessSnapshot {
        val current = inspect(pid)
        if (!current.alive || current.identity == null) throw TargetProcessChanged(current, "目标不存在或身份无法核实，旧地址已失效；请重新查询进程与模块基址")
        if (expected != null && expected != current.identity) throw TargetProcessChanged(current.copy(state = "changed"), "目标已重启、PID 被复用或执行映像改变，禁止沿用旧地址；请重新绑定并解析模块、偏移与指针链")
        return current
    }
    fun verify(before: ProcessSnapshot) = capture(before.pid, before.identity)
    companion object {
        val TARGET_TOOLS = setOf("process_info", "module_list", "memory_map", "thread_list", "module_address",
            "memory_read", "memory_write", "memory_search", "memory_read_typed", "memory_read_string", "memory_read_struct",
            "memory_inspect", "pointer_chain", "breakpoint_set", "disassemble_at", "callstack_capture")
        val ADDRESS_TOOLS = TARGET_TOOLS - setOf("process_info", "module_list", "memory_map", "thread_list", "module_address")
        val MUTATING_TOOLS = setOf("memory_write", "breakpoint_set")
        fun status(snapshot: ProcessSnapshot, previous: String? = null) = buildJsonObject {
            put("pid", snapshot.pid); put("running", snapshot.alive); put("state", snapshot.state)
            put("process_identity", snapshot.identity?.let(::JsonPrimitive) ?: JsonNull)
            put("start_ticks", snapshot.startTicks?.let(::JsonPrimitive) ?: JsonNull)
            put("task_state", snapshot.taskState); put("command", snapshot.command); put("layout_observable", snapshot.layoutObservable)
            put("previous_matches", previous?.let { JsonPrimitive(snapshot.alive && snapshot.identity == it) } ?: JsonNull)
            put("safe_to_reuse_addresses", previous != null && snapshot.alive && previous == snapshot.identity)
            put("checked_at", snapshot.checkedAt); put("detail", snapshot.detail)
        }
        fun failure(error: TargetProcessChanged, mayHaveCompleted: Boolean = false) = buildJsonObject {
            put("is_error", true); put("error_code", "target_" + error.snapshot.state); put("pid", error.snapshot.pid)
            put("message", error.reason); put("requires_rebind", true); put("old_addresses_valid", false)
            put("operation_may_have_completed", mayHaveCompleted); put("automatic_retry_safe", false)
            if (mayHaveCompleted) put("note", "操作可能已经提交，未宣称已回滚；禁止自动重放，请核实设备状态")
        }
    }
}
