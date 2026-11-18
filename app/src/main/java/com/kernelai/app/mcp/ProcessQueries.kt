package com.kernelai.app.mcp

import com.kernelai.app.driver.DriverDevice
import com.kernelai.app.driver.IoctlCommands
import com.kernelai.app.driver.IoctlStructs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded process queries. Pagination cursors count raw processes, never filtered matches. */
internal class ProcessQueries(private val device: DriverDevice) {
    companion object { const val PAGE_SIZE = 512; const val MAX_SCAN_PAGES = 32 }

    suspend fun list(args: JsonObject): String {
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        val limit = args["max_count"]?.jsonPrimitive?.intOrNull ?: 64
        val filter = args["filter"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        require(offset >= 0) { "offset 必须大于等于 0" }
        require(limit in 1..PAGE_SIZE) { "max_count 必须在 1..512 之间" }
        require(filter == null || filter.length <= 256) { "filter 过长" }
        val matches = mutableListOf<IoctlStructs.ProcessInfo>()
        val seen = mutableSetOf<Int>()
        var cursor = offset
        var total = 0
        var scanned = 0
        var pages = 0
        var unavailable = 0
        var stoppedForLimit = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val (processes, count) = readPage(cursor, if (filter == null) limit else PAGE_SIZE)
            total = count
            pages++
            if (processes.isEmpty()) {
                if (cursor < total) throw IOException("进程枚举未前进，不能判定目标未运行")
                break
            }
            for (process in processes) {
                cursor++
                scanned++
                if (process.cmdline.isEmpty()) unavailable++
                if ((filter == null || process.cmdline.contains(filter, true) || process.comm.contains(filter, true)) && seen.add(process.pid)) {
                    matches.add(process)
                    if (matches.size == limit) { stoppedForLimit = true; break }
                }
            }
            if (filter == null || stoppedForLimit || cursor >= total || pages >= MAX_SCAN_PAGES) break
        }
        val more = cursor < total
        return buildJsonObject {
            put("total_count", total); put("total_count_scope", "unfiltered_processes")
            put("offset", offset); put("returned_count", matches.size); put("scanned_count", scanned)
            put("scanned_pages", pages); put("max_count", limit)
            put("next_offset", if (more) JsonPrimitive(cursor) else JsonNull)
            put("has_more", more); put("search_complete", offset == 0 && !more)
            put("scan_limit_reached", more && !stoppedForLimit && pages >= MAX_SCAN_PAGES)
            put("cmdline_unavailable_count", unavailable)
            put("filter", filter?.let(::JsonPrimitive) ?: JsonNull)
            put("match_status", if (matches.isEmpty()) "not_observed" else "matched")
            if (matches.isEmpty()) put("note", "本次未观察到匹配进程，不是应用已停止的证明。has_more=true 时须用 next_offset 继续；filter 匹配包名/comm，不匹配应用中文显示名。可用 process_resolve 按完整包名复核。")
            put("snapshot_consistent", false)
            putJsonArray("processes") { matches.forEach { process -> addJsonObject {
                put("pid", process.pid); put("ppid", process.ppid); put("tgid", process.tgid)
                put("comm", process.comm); put("cmdline", process.cmdline)
                put("state", process.state); put("thread_count", process.threadCount)
            } } }
        }.toString()
    }

    private fun readPage(offset: Int, count: Int): Pair<List<IoctlStructs.ProcessInfo>, Int> {
        val packet = IoctlStructs.buildProcListPacket(count, offset).array()
        val ret = device.ioctl(IoctlCommands.CMD_LIST_PROCS, packet)
        if (ret < 0) throw IOException("CMD_LIST_PROCS 失败, offset=$offset, ret=$ret；查询失败不等于进程未运行")
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val actual = buf.getInt(4)
        val total = buf.getInt(8)
        if (actual !in 0..count || total < 0 || buf.getInt(12) != offset ||
            (actual > 0 && offset.toLong() + actual > total)) throw IOException("进程页元数据无效")
        return IoctlStructs.parseProcessInfo(packet.copyOfRange(32, packet.size), actual) to total
    }

    data class Resolution(val packageName: String, val pid: Int, val running: Boolean) {
        fun json() = buildJsonObject {
            put("pkg_name", packageName); put("pid", pid); put("running", running)
            put("method", "CMD_RESOLVE_PKG")
            if (!running) put("note", "内核本次未解析到目标；请核对完整包名、用户/容器环境和系统 pidof 结果，不应把查询错误解释成未运行。")
        }
    }
    fun resolve(packageName: String): Resolution {
        val name = packageName.trim()
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..255 && '\u0000' !in name) { "pkg_name 必须为 1..255 字节且不含 NUL 的完整包名" }
        val packet = ByteBuffer.allocate(264).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes) }.array()
        val ret = device.ioctl(IoctlCommands.CMD_RESOLVE_PKG, packet)
        if (ret < 0) throw IOException("CMD_RESOLVE_PKG 失败, pkg=$name, ret=$ret；这不是未运行的结论")
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val pid = buf.getInt(256)
        val running = buf.getInt(260)
        if (running !in 0..1 || (running == 1 && pid <= 0)) throw IOException("包名解析返回无效状态")
        return Resolution(name, pid, running == 1)
    }

    fun requirePid(packageName: String): Int {
        val result = resolve(packageName)
        if (!result.running) throw IOException("内核本次未解析到包名 '$packageName'，请用 process_resolve 和系统 pidof 复核")
        return result.pid
    }
}
