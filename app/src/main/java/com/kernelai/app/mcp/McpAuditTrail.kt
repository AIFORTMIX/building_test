package com.kernelai.app.mcp

import com.kernelai.app.data.writeAtomic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

@Serializable
data class McpAuditEntry(val id: String = UUID.randomUUID().toString(), val time: Long = System.currentTimeMillis(),
    val remoteAddress: String, val client: String, val external: Boolean, val method: String,
    val tool: String = "", val purpose: String = "", val arguments: String = "", val durationMs: Long = 0,
    val status: String, val requestId: String = "")

/** Bounded persistent audit. Tokens, headers, memory results and secrets are never recorded. */
class McpAuditTrail(private val file: File? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow<List<McpAuditEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private var saveJob: Job? = null
    private var dirty = false
    private var clearedDuringLoad = false
    private val ready = CompletableDeferred<Unit>()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    init {
        if (file == null) ready.complete(Unit)
        else scope.launch {
            try {
                val loaded = if (file.exists()) {
                    require(file.length() <= 16 * 1024 * 1024) { "调用记录文件过大" }
                    Json.decodeFromString<List<McpAuditEntry>>(file.readText())
                } else emptyList()
                synchronized(this@McpAuditTrail) {
                    if (!clearedDuringLoad) _entries.value = (_entries.value + loaded).distinctBy { it.id }.sortedByDescending { it.time }.take(1000)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(this@McpAuditTrail) { if (!clearedDuringLoad) _error.value = "调用记录读取失败，原文件保留：${e.message}" }
            } finally { ready.complete(Unit) }
        }
    }
    @Synchronized fun append(entry: McpAuditEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(1000)
        persist()
    }
    @Synchronized fun clear() {
        clearedDuringLoad = true; _error.value = null; _entries.value = emptyList(); persist()
    }
    @Synchronized private fun persist() {
        val target = file ?: return
        dirty = true
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            ready.await()
            while (true) {
                delay(300)
                val value = synchronized(this@McpAuditTrail) {
                    if (!dirty || _error.value != null) return@launch
                    dirty = false
                    _entries.value
                }
                try { writeAtomic(target, Json.encodeToString(value)) }
                catch (e: Exception) { if (e is CancellationException) throw e; _error.value = "调用记录保存失败：${e.message}"; return@launch }
                synchronized(this@McpAuditTrail) { if (!dirty) return@launch }
            }
        }.also { job -> job.invokeOnCompletion {
            synchronized(this@McpAuditTrail) {
                if (saveJob === job) { saveJob = null; if (dirty && _error.value == null) persist() }
            }
        } }
    }
    companion object {
        fun summarize(args: JsonObject): String {
            fun redact(value: JsonElement, depth: Int): JsonElement {
                if (depth > 4) return JsonPrimitive("…")
                return when (value) {
                    is JsonObject -> JsonObject(value.entries.take(32).associate { (key, item) -> key to
                        if (Regex("(?i)(key|token|secret|password|authorization|data|pattern)").containsMatchIn(key)) JsonPrimitive("[已省略]") else redact(item, depth + 1) })
                    is JsonArray -> JsonArray(value.take(16).map { redact(it, depth + 1) })
                    is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.take(256)) else value
                }
            }
            return redact(args, 0).toString().take(2048)
        }
    }
}
