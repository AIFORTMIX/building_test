package com.kernelai.app.driver

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest

@Serializable
data class ProcessSnapshot(val pid: Int, val state: String, val identity: String? = null,
    val startTicks: String? = null, val taskState: String = "", val command: String = "",
    val layoutObservable: Boolean = false, val detail: String = "", val checkedAt: Long = System.currentTimeMillis()) {
    val alive: Boolean get() = state == "running"
}

/** Read-only proc metadata, executed in the existing bound Root service. No kernel ABI change. */
class ProcessInspector(private val root: File = File("/proc")) {
    private val bootId by lazy { readBounded(File(root, "sys/kernel/random/boot_id"), 128).trim().also { require(it.isNotEmpty()) } }
    fun read(pid: Int): ProcessSnapshot {
        require(pid > 0) { "PID 必须大于 0" }
        val stat = File(root, "$pid/stat")
        return try {
            val first = parseStat(readBounded(stat, 8192), pid)
            val command = runCatching { readBounded(File(root, "$pid/cmdline"), 2048).substringBefore('\u0000').take(256) }.getOrDefault(first.comm)
            val second = parseStat(readBounded(stat, 8192), pid)
            if (first.fingerprint != second.fingerprint) ProcessSnapshot(pid, "changed", detail = "采样期间进程身份或执行映像改变")
            else if (second.state in listOf("Z", "X", "x")) ProcessSnapshot(pid, "exited", startTicks = second.start,
                taskState = second.state, command = command, detail = "进程已退出或成为僵尸")
            else {
                val digest = MessageDigest.getInstance("SHA-256").digest((bootId + ":" + pid + ":" + second.fingerprint).toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                ProcessSnapshot(pid, "running", "p1:$digest", second.start, second.state, command,
                    second.layoutObservable, if (second.layoutObservable) "已校验启动时间及主执行映像布局" else "主映像地址不可见，仅能校验进程启动身份")
            }
        } catch (missing: NoSuchFileException) {
            if (missing.file == stat.path) ProcessSnapshot(pid, "exited", detail = "进程已退出，/proc/$pid/stat 不存在")
            else ProcessSnapshot(pid, "unavailable", detail = "启动身份来源不可读取，无法核实进程")
        } catch (error: Exception) {
            ProcessSnapshot(pid, "unavailable", detail = "无法核实目标身份：${error.message}")
        }
    }
    internal data class Stat(val comm: String, val state: String, val start: String, val code: String, val endCode: String, val stack: String) {
        val fingerprint get() = "$start:$code:$endCode:$stack"
        val layoutObservable get() = code != "0" || endCode != "0" || stack != "0"
    }
    companion object {
        internal fun parseStat(text: String, pid: Int): Stat {
            val begin = text.indexOf('('); val end = text.lastIndexOf(')')
            require(begin > 0 && end > begin && text.substring(0, begin).trim().toInt() == pid) { "无效的进程状态记录" }
            val fields = text.substring(end + 1).trim().split(Regex("\\s+"))
            require(fields.size >= 26 && fields[0].length == 1) { "进程状态记录不完整" }
            listOf(19, 23, 24, 25).forEach { require(fields[it].toULongOrNull() != null) { "无效的进程身份字段" } }
            return Stat(text.substring(begin + 1, end), fields[0], fields[19], fields[23], fields[24], fields[25])
        }
        internal fun readBounded(file: File, limit: Int): String = Files.newInputStream(file.toPath()).use { input ->
            val data = ByteArray(limit); var count = 0
            while (count < data.size) { val next = input.read(data, count, data.size - count); if (next < 0) break; count += next }
            String(data, 0, count, Charsets.UTF_8)
        }
    }
}
