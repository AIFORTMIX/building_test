package com.kernelai.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

internal data class WorkspaceEntry(val name: String, val directory: Boolean, val size: Long)
internal data class WorkspaceText(val content: String, val sha256: String, val bytes: Int)
internal interface WorkspaceAccess {
    fun list(path: String): Pair<List<WorkspaceEntry>, Boolean>
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray, expectedHash: String?, overwrite: Boolean)
    fun mkdir(path: String)
}

internal object WorkspacePaths {
    const val MAX_TEXT = 256 * 1024
    fun parts(path: String, root: Boolean = false): List<String> {
        require(path.length <= 1024 && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path) { "工作区路径必须为相对路径" }
        val parts = path.split('/').filter { it.isNotEmpty() }
        require((root || parts.isNotEmpty()) && parts.size <= 16 && parts.all {
            it != "." && it != ".." && !it.startsWith(".qingwei-") && it.toByteArray().size <= 255 && it.none { c -> c.code < 32 }
        }) { "路径不可越出工作区或访问内部临时文件" }
        return parts
    }
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun text(bytes: ByteArray): WorkspaceText {
        require(bytes.size <= MAX_TEXT && bytes.none { it == 0.toByte() }) { "仅支持不超过 256 KiB 的 UTF-8 文本；二进制文件不可当作文本读取" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return WorkspaceText(text, hash(bytes), bytes.size)
    }
    fun checkOverwrite(existing: ByteArray?, expected: String?, overwrite: Boolean) {
        if (existing != null) {
            require(overwrite && expected != null && expected == hash(existing)) { "workspace_conflict：文件已存在或已改变，请重新读取并传回 expected_sha256" }
        } else require(expected == null) { "workspace_conflict：此前读取的文件已不存在" }
    }
}

internal class WorkspaceDisk(private val root: File) : WorkspaceAccess {
    init { require(!Files.isSymbolicLink(root.toPath())); root.mkdirs() }
    private fun file(path: String, allowRoot: Boolean = false): File {
        var target = root
        WorkspacePaths.parts(path, allowRoot).forEach { part ->
            target = File(target, part)
            require(!Files.isSymbolicLink(target.toPath())) { "工作区内不跟随符号链接" }
        }
        require(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "路径越出工作区" }
        return target
    }
    override fun list(path: String): Pair<List<WorkspaceEntry>, Boolean> {
        val directory = file(path, true); require(directory.isDirectory) { "目录不存在" }
        val files = Files.newDirectoryStream(directory.toPath()).use { entries -> entries.asSequence().filterNot { it.fileName.toString().startsWith(".qingwei-") }.take(501).map { it.toFile() }.toList() }
        return files.take(500).map { val link = Files.isSymbolicLink(it.toPath()); WorkspaceEntry(it.name, !link && it.isDirectory, if (!link && it.isFile) it.length() else 0) } to (files.size > 500)
    }
    override fun read(path: String): ByteArray {
        val target = file(path); require(target.isFile && target.length() <= WorkspacePaths.MAX_TEXT) { "文件不存在或超过 256 KiB 文本上限" }
        return target.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (output.size() <= WorkspacePaths.MAX_TEXT) {
                val n = input.read(buffer, 0, minOf(buffer.size, WorkspacePaths.MAX_TEXT + 1 - output.size()))
                if (n < 0) break; output.write(buffer, 0, n)
            }
            output.toByteArray().also { require(it.size <= WorkspacePaths.MAX_TEXT) { "文件超过 256 KiB" } }
        }
    }
    override fun write(path: String, bytes: ByteArray, expectedHash: String?, overwrite: Boolean) {
        require(bytes.size <= WorkspacePaths.MAX_TEXT)
        val target = file(path); require(target.parentFile?.isDirectory == true && !target.isDirectory) { "父目录不存在或目标是目录" }
        WorkspacePaths.checkOverwrite(if (target.exists()) read(path) else null, expectedHash, overwrite)
        val temporary = File(target.parentFile, ".qingwei-write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
    override fun mkdir(path: String) { val target = file(path); require(!target.exists() || target.isDirectory); require(target.isDirectory || target.mkdirs()) { "目录创建失败" } }
}
