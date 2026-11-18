package com.kernelai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.data.model.BreakpointInfo
import com.kernelai.app.data.model.BreakpointInfo.BreakpointType
import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class BreakpointViewModel(private val tools: McpToolExecutor = KernelAiApplication.instance.aiToolExecutor) : ViewModel() {
    private val _breakpoints = MutableStateFlow<List<BreakpointInfo>>(emptyList()); val breakpoints = _breakpoints.asStateFlow()
    private val _isLoading = MutableStateFlow(false); val isLoading = _isLoading.asStateFlow()
    private val _selectedBreakpoint = MutableStateFlow<BreakpointInfo?>(null); val selectedBreakpoint = _selectedBreakpoint.asStateFlow()
    private val _registerSnapshot = MutableStateFlow<RegisterSnapshot?>(null); val registerSnapshot = _registerSnapshot.asStateFlow()
    private val _showAddDialog = MutableStateFlow(false); val showAddDialog = _showAddDialog.asStateFlow()
    private val _status = MutableStateFlow("点击刷新读取驱动硬件断点，列表不含软件断点"); val status = _status.asStateFlow()
    private suspend fun call(name: String, args: JsonObject): JsonObject {
        val result = Json.parseToJsonElement(tools.executeTool(name, args)).jsonObject
        check(result["is_error"]?.jsonPrimitive?.booleanOrNull != true) { result["message"]?.jsonPrimitive?.contentOrNull ?: "设备操作失败" }
        return result
    }
    private suspend fun refreshList() {
        val result = call("breakpoint_list", JsonObject(emptyMap()))
        _breakpoints.value = result["breakpoints"]!!.jsonArray.map { it.jsonObject.let { b ->
            BreakpointInfo(b["bp_index"]!!.jsonPrimitive.int, b["address"]!!.jsonPrimitive.content.removePrefix("0x").toULong(16).toLong(),
                BreakpointType.fromValue(b["type"]!!.jsonPrimitive.int), b["length"]!!.jsonPrimitive.int, b["hit_count"]!!.jsonPrimitive.long, b["active"]!!.jsonPrimitive.boolean)
        } }
        _status.value = "驱动硬件断点：${_breakpoints.value.size} 个；列表为全局范围"
    }
    private fun operation(block: suspend () -> Unit) {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch { try { block() } catch (e: Exception) { if (e is CancellationException) throw e; _status.value = "操作失败：${e.message}" } finally { _isLoading.value = false } }
    }
    fun loadBreakpoints() = operation { refreshList() }
    fun showAddDialog() { _showAddDialog.value = true }
    fun hideAddDialog() { _showAddDialog.value = false }
    fun addBreakpoint(address: Long, type: BreakpointType, pid: Int, expectedIdentity: String, length: Int = 4) = operation {
        require(pid > 0 && address != 0L) { "请输入有效 PID 和地址" }
        val name = when (type) { BreakpointType.HW_EXECUTE -> "hw_execute"; BreakpointType.HW_READ -> "hw_read"; BreakpointType.HW_WRITE -> "hw_write"; BreakpointType.HW_ACCESS -> "hw_access"; BreakpointType.SW -> "sw" }
        call("breakpoint_set", buildJsonObject { put("pid", pid); put("expected_identity", expectedIdentity); put("address", "0x" + address.toULong().toString(16)); put("type", name); put("length", length) })
        _showAddDialog.value = false; refreshList()
    }
    fun removeBreakpoint(breakpoint: BreakpointInfo) = operation {
        call("breakpoint_remove", buildJsonObject { put("bp_id", breakpoint.bpIndex); put("type", if (breakpoint.type == BreakpointType.SW) "sw" else "hw") })
        clearSelection(); refreshList()
    }
    fun toggleBreakpoint(breakpoint: BreakpointInfo) { _status.value = "驱动不提供原位启停接口，请删除后重新设置断点" }
    fun selectBreakpoint(breakpoint: BreakpointInfo) = operation {
        _selectedBreakpoint.value = breakpoint; _registerSnapshot.value = null
        val result = call("register_read", buildJsonObject { put("bp_id", breakpoint.bpIndex) })
        if (result["valid"]?.jsonPrimitive?.boolean != true) { _status.value = "断点尚无有效寄存器快照"; return@operation }
        val registers = result["registers"]!!.jsonObject
        fun value(name: String) = registers[name]!!.jsonPrimitive.content.removePrefix("0x").toULong(16).toLong()
        _registerSnapshot.value = RegisterSnapshot(x0 = value("x0"), x1 = value("x1"), x2 = value("x2"), x3 = value("x3"), x4 = value("x4"), x5 = value("x5"), x6 = value("x6"), x7 = value("x7"), x8 = value("x8"), x9 = value("x9"), x10 = value("x10"), x11 = value("x11"), x12 = value("x12"), x13 = value("x13"), x14 = value("x14"), x15 = value("x15"), x16 = value("x16"), x17 = value("x17"), x18 = value("x18"), x19 = value("x19"), x20 = value("x20"), x21 = value("x21"), x22 = value("x22"), x23 = value("x23"), x24 = value("x24"), x25 = value("x25"), x26 = value("x26"), x27 = value("x27"), x28 = value("x28"), x29 = value("x29"), x30 = value("x30"), sp = value("sp"), pc = value("pc"), pstate = value("pstate"))
    }
    fun clearSelection() { _selectedBreakpoint.value = null; _registerSnapshot.value = null }
    data class RegisterSnapshot(
        val x0: Long, val x1: Long, val x2: Long, val x3: Long,
        val x4: Long, val x5: Long, val x6: Long, val x7: Long,
        val x8: Long, val x9: Long, val x10: Long, val x11: Long,
        val x12: Long, val x13: Long, val x14: Long, val x15: Long,
        val x16: Long, val x17: Long, val x18: Long, val x19: Long,
        val x20: Long, val x21: Long, val x22: Long, val x23: Long,
        val x24: Long, val x25: Long, val x26: Long, val x27: Long,
        val x28: Long, val x29: Long, val x30: Long,
        val sp: Long, val pc: Long, val pstate: Long
    ) {
        fun getRegisterValue(index: Int): Long = when (index) {
            0 -> x0; 1 -> x1; 2 -> x2; 3 -> x3
            4 -> x4; 5 -> x5; 6 -> x6; 7 -> x7
            8 -> x8; 9 -> x9; 10 -> x10; 11 -> x11
            12 -> x12; 13 -> x13; 14 -> x14; 15 -> x15
            16 -> x16; 17 -> x17; 18 -> x18; 19 -> x19
            20 -> x20; 21 -> x21; 22 -> x22; 23 -> x23
            24 -> x24; 25 -> x25; 26 -> x26; 27 -> x27
            28 -> x28; 29 -> x29; 30 -> x30
            else -> 0L
        }

        companion object {
            val REGISTER_NAMES = (0..30).map { "X$it" } + listOf("SP", "PC", "PSTATE")
        }
    }
}
