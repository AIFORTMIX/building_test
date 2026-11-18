package com.kernelai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.data.model.ProcessInfo
import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class ProcessViewModel(private val tools: McpToolExecutor = KernelAiApplication.instance.aiToolExecutor) : ViewModel() {
    private val _allProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList()); val allProcesses = _allProcesses.asStateFlow()
    private val _filteredProcesses = MutableStateFlow<List<ProcessInfo>>(emptyList()); val filteredProcesses = _filteredProcesses.asStateFlow()
    private val _searchQuery = MutableStateFlow(""); val searchQuery = _searchQuery.asStateFlow()
    private val _isLoading = MutableStateFlow(false); val isLoading = _isLoading.asStateFlow()
    private val _expandedPid = MutableStateFlow<Int?>(null); val expandedPid = _expandedPid.asStateFlow()
    private val _processDetails = MutableStateFlow<Map<Int, ProcessDetails>>(emptyMap()); val processDetails = _processDetails.asStateFlow()
    private val _selectedProcess = MutableStateFlow<ProcessInfo?>(null); val selectedProcess = _selectedProcess.asStateFlow()
    private val _status = MutableStateFlow("下拉或点击刷新查询设备"); val status = _status.asStateFlow()
    fun refresh() = loadProcesses()
    fun loadProcesses() {
        if (_isLoading.value) return
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val values = linkedMapOf<Int, ProcessInfo>()
                var cursor = 0; var more = true
                repeat(32) {
                    if (!more) return@repeat
                    val result = call("process_list", buildJsonObject { put("offset", cursor); put("max_count", 512) })
                    result["processes"]!!.jsonArray.forEach { item ->
                        val o = item.jsonObject; val pid = o["pid"]!!.jsonPrimitive.int
                        values[pid] = ProcessInfo(pid, o["ppid"]!!.jsonPrimitive.int, o.text("comm"), o.text("cmdline"), o.text("state"), o["thread_count"]!!.jsonPrimitive.int)
                    }
                    more = result["has_more"]!!.jsonPrimitive.boolean
                    if (more) {
                        val next = result["next_offset"]!!.jsonPrimitive.int
                        require(next > cursor) { "分页未前进" }; cursor = next
                    }
                }
                _allProcesses.value = values.values.toList(); _processDetails.value = emptyMap(); applyFilter()
                _status.value = "设备查询：${values.size} 个进程" + if (more) "，达到分页上限，结果不完整" else "，跨页非原子快照"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _allProcesses.value = emptyList(); applyFilter(); _status.value = "查询失败：${e.message}"
            } finally { _isLoading.value = false }
        }
    }
    fun updateSearchQuery(query: String) { _searchQuery.value = query; applyFilter() }
    fun selectProcess(process: ProcessInfo) { _selectedProcess.value = process }
    fun toggleExpand(pid: Int) {
        if (_expandedPid.value == pid) { _expandedPid.value = null; return }
        _expandedPid.value = pid
        viewModelScope.launch {
            try {
                val o = call("process_info", buildJsonObject { put("pid", pid) })
                val details = ProcessDetails(
                    o["modules"]!!.jsonArray.map { it.jsonObject.let { m -> ModuleInfo(m.text("path"), m.text("base_addr"), m["size"]!!.jsonPrimitive.long, flags(m["flags"]!!.jsonPrimitive.int)) } },
                    o["memory_map"]!!.jsonArray.map { it.jsonObject.let { v -> VmaEntry(v.text("start"), v.text("end"), flags(v["flags"]!!.jsonPrimitive.int), v.text("name")) } },
                    o["threads"]!!.jsonArray.map { it.jsonObject.let { t -> ThreadInfo(t["tid"]!!.jsonPrimitive.int, t.text("comm"), t.text("state"), t.text("stack_ptr"), t.text("pc")) } })
                _processDetails.value += pid to details
                _status.value = "PID $pid：模块/VMA/线程有返回上限，列表不能证明不存在其他条目"
            } catch (e: Exception) { if (e is CancellationException) throw e; _status.value = "PID $pid 查询失败：${e.message}" }
        }
    }
    private suspend fun call(name: String, args: JsonObject) = Json.parseToJsonElement(tools.executeTool(name, args)).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun flags(f: Int) = "${if (f and 1 != 0) 'r' else '-'}${if (f and 2 != 0) 'w' else '-'}${if (f and 4 != 0) 'x' else '-'}${if (f and 8 != 0) 's' else 'p'}"
    private fun applyFilter() {
        val q = _searchQuery.value.trim()
        _filteredProcesses.value = _allProcesses.value.filter { q.isEmpty() || it.displayName.contains(q, true) || it.comm.contains(q, true) || it.pid.toString().contains(q) }
    }
    data class ProcessDetails(
        val modules: List<ModuleInfo> = emptyList(),
        val memoryMaps: List<VmaEntry> = emptyList(),
        val threads: List<ThreadInfo> = emptyList()
    )

    data class ModuleInfo(
        val name: String,
        val baseAddress: String,
        val size: Long,
        val permissions: String
    )

    data class VmaEntry(
        val startAddr: String,
        val endAddr: String,
        val perms: String,
        val name: String
    )

    data class ThreadInfo(
        val tid: Int,
        val name: String,
        val state: String,
        val stackPtr: String,
        val pc: String
    )
}
