package com.kernelai.app.ui.screen.memory

import androidx.compose.runtime.*
import com.kernelai.app.KernelAiApplication
import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal data class BoundProcess(val pid: Int, val identity: String)

internal class ProcessBinding(private val scope: CoroutineScope, private val tools: McpToolExecutor,
    private val invalidated: () -> Unit) {
    var target by mutableStateOf<BoundProcess?>(null); private set
    var status by mutableStateOf("先绑定目标，再输入当前进程的地址"); private set
    var busy by mutableStateOf(false); private set
    private var job: Job? = null
    private var generation = 0
    fun reset() { generation++; job?.cancel(); target = null; busy = false; status = "先绑定目标，再输入当前进程的地址"; invalidated() }
    fun close() { generation++; job?.cancel() }
    fun invalidate(reason: String) { reset(); status = reason + "；旧地址已清除，请重新绑定" }
    fun bind(value: String) {
        reset(); busy = true; status = "正在核实目标身份…"
        val revision = generation
        job = scope.launch {
            try {
                val args = buildJsonObject {
                    value.trim().toIntOrNull()?.let { require(it > 0); put("pid", it) } ?: put("pkg_name", value.trim())
                }
                val initial = query(args)
                require(initial["running"]?.jsonPrimitive?.boolean == true) { initial["detail"]?.jsonPrimitive?.contentOrNull ?: "目标当前未运行或身份不可核实" }
                val binding = BoundProcess(initial["pid"]!!.jsonPrimitive.int, initial["process_identity"]!!.jsonPrimitive.content)
                if (revision != generation) return@launch
                target = binding; busy = false; status = "PID ${binding.pid} · 身份已绑定，持续检测中"
                while (isActive) {
                    delay(2000)
                    val checked = query(buildJsonObject { put("pid", binding.pid); put("previous_identity", binding.identity) })
                    if (revision != generation) return@launch
                    if (checked["running"]?.jsonPrimitive?.boolean != true || checked["previous_matches"]?.jsonPrimitive?.boolean != true) {
                        target = null; status = "目标退出、重启或身份不可核实，旧地址已清除；请重新绑定"; invalidated(); return@launch
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (revision == generation) { target = null; busy = false; status = "目标检测失败：${error.message}；旧地址已清除"; invalidated() }
            }
        }
    }
    private suspend fun query(args: JsonObject): JsonObject {
        val result = Json.parseToJsonElement(tools.executeTool("process_status", args)).jsonObject
        require(result["is_error"]?.jsonPrimitive?.booleanOrNull != true) { result["message"]?.jsonPrimitive?.contentOrNull ?: "状态查询失败" }
        return result
    }
}

@Composable
internal fun rememberProcessBinding(value: String, onInvalidated: () -> Unit): ProcessBinding {
    val scope = rememberCoroutineScope()
    val callback by rememberUpdatedState(onInvalidated)
    val binding = remember { ProcessBinding(scope, KernelAiApplication.instance.aiToolExecutor) { callback() } }
    LaunchedEffect(value) { binding.reset() }
    DisposableEffect(binding) { onDispose { binding.close() } }
    return binding
}
