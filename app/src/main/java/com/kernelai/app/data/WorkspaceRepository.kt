package com.kernelai.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class Workspace(val id: String, val name: String, val treeUri: String? = null, val enabled: Boolean = true)

/** Only the app UI can grant roots. Tools receive opaque IDs and relative paths. */
class WorkspaceRepository(private val context: Context) {
    private val lock = Mutex()
    private val config = File(context.filesDir, "workspaces/index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    val workspaces = _workspaces.asStateFlow()
    private var loaded = false
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock { loadLocked() } }
    private fun loadLocked() {
        if (loaded) return
        val values = if (config.exists()) {
            require(config.length() < 64 * 1024) { "工作区配置过大" }
            json.decodeFromString<List<Workspace>>(config.readText())
        } else listOf(Workspace("default", "默认工作区"))
        require(values.size <= 16 && values.map { it.id }.distinct().size == values.size)
        values.forEach { require(it.id.matches(Regex("[a-zA-Z0-9-]{1,64}"))) }
        _workspaces.value = values
        loaded = true
    }
    private fun save(values: List<Workspace>) {
        writeAtomic(config, json.encodeToString(values)); _workspaces.value = values
    }
    suspend fun add(name: String, tree: Uri? = null) = withContext(Dispatchers.IO) { lock.withLock {
        loadLocked(); require(_workspaces.value.size < 16) { "最多 16 个工作区" }
        require(name.trim().length in 1..64) { "名称应为 1–64 字" }
        tree?.let {
            require(DocumentsContract.isTreeUri(it))
            require(_workspaces.value.none { w -> w.treeUri == it.toString() }) { "该文件夹已添加" }
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            WorkspaceDocuments(context.contentResolver, it).list("")
        }
        save(_workspaces.value + Workspace(UUID.randomUUID().toString(), name.trim(), tree?.toString()))
    } }
    suspend fun enable(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { lock.withLock {
        loadLocked(); require(_workspaces.value.any { it.id == id }); save(_workspaces.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    } }
    private fun access(id: String, requireEnabled: Boolean): WorkspaceAccess {
        val workspace = _workspaces.value.singleOrNull { it.id == id } ?: error("工作区不存在")
        require(!requireEnabled || workspace.enabled) { "此工作区已关闭 AI 访问" }
        return workspace.treeUri?.let { WorkspaceDocuments(context.contentResolver, Uri.parse(it)) }
            ?: WorkspaceDisk(File(context.filesDir, "workspaces/files/${workspace.id}"))
    }
    internal suspend fun <T> use(id: String, ai: Boolean = true, block: (WorkspaceAccess) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked(); block(access(id, ai)) }
    }
    internal suspend fun entries(id: String, path: String, offset: Int = 0) = use(id, false) { it.list(path, offset) }
    internal suspend fun preview(id: String, path: String): File = use(id, false) {
        val text = WorkspacePaths.text(it.read(path))
        val directory = File(context.filesDir, "artifacts/workspace-previews").apply { mkdirs() }
        val extension = path.substringAfterLast('.', "txt").lowercase().takeIf { value -> value.matches(Regex("[a-z0-9]{1,12}")) } ?: "txt"
        File(directory, "${text.sha256}.$extension").apply { writeText(text.content) }
    }
}
