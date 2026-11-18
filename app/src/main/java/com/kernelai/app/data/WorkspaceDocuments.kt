package com.kernelai.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.util.UUID

/** SAF access follows only descendants of the user-selected tree, never arbitrary content URIs. */
internal class WorkspaceDocuments(private val resolver: ContentResolver, private val tree: Uri) : WorkspaceAccess {
    private data class Node(val id: String, val name: String, val mime: String, val size: Long, val flags: Int) {
        val directory get() = mime == Docs.Document.MIME_TYPE_DIR
    }
    private val rootId = Docs.getTreeDocumentId(tree)
    private fun uri(id: String) = Docs.buildDocumentUriUsingTree(tree, id)
    private val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
        Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE, Docs.Document.COLUMN_FLAGS)
    private fun children(id: String): Pair<List<Node>, Boolean> {
        val result = mutableListOf<Node>()
        resolver.query(Docs.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result.add(Node(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), if (cursor.isNull(3)) -1 else cursor.getLong(3), cursor.getInt(4)))
                if (result.size > 500) return result.take(500) to true
            }
        } ?: error("工作区目录无法读取，系统授权可能已失效")
        return result to false
    }
    private fun locate(parts: List<String>): Node {
        var node = Node(rootId, "", Docs.Document.MIME_TYPE_DIR, 0, 0)
        parts.forEach { name ->
            require(node.directory) { "父路径不是目录" }
            val (entries, truncated) = children(node.id)
            require(!truncated) { "目录超过 500 项，不能完整解析文件名，请选择更小的工作区目录" }
            node = entries.filter { it.name == name }.singleOrNull() ?: error("文件或目录不存在，或名称不唯一：$name")
        }
        return node
    }
    override fun list(path: String): Pair<List<WorkspaceEntry>, Boolean> {
        val directory = locate(WorkspacePaths.parts(path, true)); require(directory.directory)
        val (entries, more) = children(directory.id)
        return entries.filterNot { it.name.startsWith(".qingwei-") }.map { WorkspaceEntry(it.name, it.directory, it.size) } to more
    }
    private fun bytes(id: String): ByteArray = resolver.openInputStream(uri(id))?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (out.size() <= WorkspacePaths.MAX_TEXT) {
            val n = input.read(buffer, 0, minOf(buffer.size, WorkspacePaths.MAX_TEXT + 1 - out.size()))
            if (n < 0) break; out.write(buffer, 0, n)
        }
        out.toByteArray().also { require(it.size <= WorkspacePaths.MAX_TEXT) { "文件超过 256 KiB 文本上限" } }
    } ?: error("文件无法读取")
    override fun read(path: String): ByteArray {
        val node = locate(WorkspacePaths.parts(path)); require(!node.directory && node.size <= WorkspacePaths.MAX_TEXT)
        return bytes(node.id)
    }
    override fun mkdir(path: String) {
        var id = rootId
        WorkspacePaths.parts(path).forEach { name ->
            val (entries, more) = children(id); require(!more)
            val existing = entries.filter { it.name == name }
            require(existing.size <= 1)
            id = if (existing.isEmpty()) Docs.getDocumentId(Docs.createDocument(resolver, uri(id), Docs.Document.MIME_TYPE_DIR, name) ?: error("目录创建失败"))
                else existing.single().also { require(it.directory) }.id
        }
    }
    override fun write(path: String, bytes: ByteArray, expectedHash: String?, overwrite: Boolean) {
        require(bytes.size <= WorkspacePaths.MAX_TEXT)
        val parts = WorkspacePaths.parts(path); val name = parts.last(); val parent = locate(parts.dropLast(1))
        val (entries, more) = children(parent.id); require(!more)
        val matches = entries.filter { it.name == name }; require(matches.size <= 1)
        val old = matches.singleOrNull(); require(old?.directory != true)
        val original = old?.let { this.bytes(it.id) }
        WorkspacePaths.checkOverwrite(original, expectedHash, overwrite)
        if (old != null) require(old.flags and Docs.Document.FLAG_SUPPORTS_RENAME != 0) { "此文件夹不支持安全替换，请写入新文件名或选择其他工作区" }
        val extension = name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
        val mime = extension?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "application/octet-stream"
        val temporary = Docs.createDocument(resolver, uri(parent.id), mime, ".qingwei-write-${UUID.randomUUID()}${extension?.let { ".$it" }.orEmpty()}") ?: error("无法创建临时文件")
        var backup: Uri? = null; var renamed: Uri? = null
        var backupAttempted = false; var commitAttempted = false
        val backupName = "qingwei-backup-${UUID.randomUUID()}-${name.take(80)}"
        try {
            resolver.openOutputStream(temporary, "wt")?.use { it.write(bytes); it.flush() } ?: error("文件无法写入")
            require(WorkspacePaths.hash(this.bytes(Docs.getDocumentId(temporary))) == WorkspacePaths.hash(bytes)) { "临时文件校验失败，原文件保留" }
            if (old != null) {
                require(WorkspacePaths.hash(this.bytes(old.id)) == expectedHash) { "workspace_conflict：写入前文件已发生变化" }
                backupAttempted = true
                backup = Docs.renameDocument(resolver, uri(old.id), backupName) ?: error("备份原文件结果未确认")
            }
            val (current, incomplete) = children(parent.id)
            require(!incomplete && current.none { it.name == name }) { "workspace_conflict：提交前出现同名文件" }
            commitAttempted = true
            renamed = Docs.renameDocument(resolver, temporary, name) ?: error("新文件提交结果未确认")
            val final = locate(parts)
            require(final.id == Docs.getDocumentId(renamed) && WorkspacePaths.hash(this.bytes(final.id)) == WorkspacePaths.hash(bytes)) { "文件提交结果未确认" }
            backup?.let { runCatching { Docs.deleteDocument(resolver, it) } }
        } catch (error: Exception) {
            if (backup != null && !commitAttempted) runCatching {
                val (current, incomplete) = children(parent.id)
                if (!incomplete && current.none { it.name == name }) Docs.renameDocument(resolver, backup!!, name)
            }
            throw IllegalStateException(error.message + if (backupAttempted || commitAttempted) "；替换结果需核实，可能保留备份 $backupName，禁止自动重放写入" else "；原文件未覆盖", error)
        } finally { if (!commitAttempted) runCatching { Docs.deleteDocument(resolver, temporary) } }
    }
}
