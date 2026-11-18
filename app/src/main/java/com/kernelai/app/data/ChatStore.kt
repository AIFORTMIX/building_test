package com.kernelai.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ChatStore(private val file: File) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun load(): ChatArchive = withContext(Dispatchers.IO) { lock.withLock {
        if (!file.exists()) return@withLock ChatArchive()
        require(file.length() <= 64L * 1024 * 1024) { "会话文件过大，原文件保留" }
        json.decodeFromString<ChatArchive>(file.readText()).also { require(it.version == 1); require(it.sessions.map { s -> s.id }.distinct().size == it.sessions.size) }
    } }
    suspend fun save(archive: ChatArchive) = withContext(Dispatchers.IO) { lock.withLock {
        writeAtomic(file, json.encodeToString(archive))
    } }
}

internal fun writeAtomic(file: File, content: String) {
    val data = content.toByteArray(Charsets.UTF_8)
    require(data.size <= 64 * 1024 * 1024) { "本地存储达到 64 MiB 上限，请导出并清理旧对话" }
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    try {
        FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { temporary.delete() }
}
