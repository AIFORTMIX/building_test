package com.kernelai.app.driver

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Read-only source paging; no ioctl layout or kernel code is changed. */
internal class ProcRecords(private val root: File = File("/proc")) {
    private val inspector = ProcessInspector(root)
    fun read(pid: Int, kind: String, offset: Int, limit: Int, expectedIdentity: String?, expectedRevision: String?): String {
        require(pid > 0 && kind in setOf("maps", "modules", "threads") && offset in 0..200000 && limit in 1..200)
        val before = inspector.read(pid)
        require(before.alive && before.identity != null) { "目标进程不存在或身份不可核实" }
        require(expectedIdentity == null || expectedIdentity == before.identity) { "process_changed：进程已重启，旧地址和分页失效" }
        val digest = MessageDigest.getInstance("SHA-256")
        val records = mutableListOf<JsonObject>(); var total = 0; var bytes = 0L
        var pageChars = 0
        fun addRecord(record: JsonObject) { val size = record.toString().length; if (pageChars + size <= 96000) { records.add(record); pageChars += size } }
        if (kind == "threads") {
            val ids = Files.newDirectoryStream(File(root, "$pid/task").toPath()).use { stream ->
                stream.asSequence().mapNotNull { it.fileName.toString().toIntOrNull() }.take(200001).sorted().toList()
            }
            require(ids.size <= 200000) { "线程目录超过扫描上限，请缩小目标" }
            total = ids.size
            ids.forEach { digest.update("$it\n".toByteArray()) }
            ids.drop(offset).take(limit).forEach { tid ->
                val stat = runCatching { ProcessInspector.readBounded(File(root, "$pid/task/$tid/stat"), 8192) }.getOrNull()
                addRecord(buildJsonObject {
                    put("tid", tid); put("present", stat != null)
                    stat?.let { ProcessInspector.parseStat(it, tid) }?.let { put("comm", it.comm); put("state", it.state) }
                })
            }
        } else {
            File(root, "$pid/maps").bufferedReader().use { reader ->
                while (true) {
                    // Bounded line reads avoid allocating an attacker-controlled giant line.
                    val line = StringBuilder()
                    while (true) { val c = reader.read(); if (c < 0 || c == 10) break; require(line.length < 16384) { "映射行超长" }; line.append(c.toChar()) }
                    if (line.isEmpty()) break
                    bytes += line.length; require(bytes <= 64L * 1024 * 1024) { "映射超过 64 MiB 扫描上限" }
                    digest.update(line.toString().toByteArray()); digest.update(10.toByte())
                    val fields = line.toString().trim().split(Regex("\\s+"), limit = 6)
                    require(fields.size >= 5)
                    val path = fields.getOrElse(5) { "" }
                    if (kind == "modules" && !path.startsWith('/')) continue
                    require(total < 200000) { "映射条目超过扫描上限" }
                    if (total in offset until offset + limit && total == offset + records.size) addRecord(buildJsonObject {
                        val range = fields[0].split('-'); require(range.size == 2)
                        put("start", "0x${range[0]}"); put("end", "0x${range[1]}")
                        put("permissions", fields[1]); put("file_offset", "0x${fields[2]}"); put("device", fields[3]); put("inode", fields[4]); put("path", path)
                    })
                    total++
                }
            }
        }
        val after = inspector.read(pid)
        require(after.alive && before.identity == after.identity) { "process_changed：采样期间进程退出或重启，请重新绑定" }
        val revision = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(expectedRevision == null || expectedRevision == revision) { "snapshot_changed：映射或线程列表已变化，请从 offset=0 重新分页" }
        return buildJsonObject {
            put("pid", pid); put("process_identity", after.identity); put("revision", revision); put("kind", kind); put("source", "root_proc_readonly")
            put("records", JsonArray(records)); put("offset", offset); put("total_count", total); put("has_more", offset + records.size < total)
            if (offset + records.size < total) put("next_offset", offset + records.size)
            put("page_complete", true); put("live_data", true)
            put("continuation", "继续时传回 process_identity 和 revision；若列表变化应从头刷新，线程状态会实时变化。modules 每项为文件映射段，不合并为 ELF 模块。")
        }.toString()
    }
}
