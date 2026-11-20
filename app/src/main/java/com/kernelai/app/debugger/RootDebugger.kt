package com.kernelai.app.debugger

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process as AndroidProcess
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.kernelai.app.driver.ProcessInspector
import com.kernelai.app.driver.ProcessSnapshot
import com.kernelai.app.mcp.ProcessGuard
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The privileged service owns one LLDB all-stop session. UI/HTTP callers never own ptrace threads. */
internal class RootDebugger(private val context: Context, private val inspector: ProcessInspector = ProcessInspector()) : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "qingwei-debug-session").apply { isDaemon = true } }
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var listener: LocalServerSocket? = null
    private var session: Session? = null
    private data class Session(val id: String, val target: ProcessSnapshot, val server: Process, val remote: GdbRemote,
        val createdAt: Long = SystemClock.elapsedRealtime(),
        var state: String = "attaching", var lastUsed: Long = SystemClock.elapsedRealtime(), var lastChecked: Long = 0,
        var stop: String = "", var thread: String = "", var info: Map<String, String> = emptyMap(),
        var sequence: Long = 0, val events: ArrayDeque<JsonObject> = ArrayDeque(),
        val breakpoints: MutableMap<String, Breakpoint> = linkedMapOf(), var cleanupConfirmed: Boolean = false)
    private data class Breakpoint(val id: String, val type: Int, val address: ULong, val size: Int)
    private val executable get() = File(context.applicationInfo.nativeLibraryDir, "libqingwei_debugger.so")

    init { worker.scheduleWithFixedDelay({ runCatching { tick() } }, 100, 100, TimeUnit.MILLISECONDS) }

    fun call(action: String, raw: String, callerPid: Int): String {
        require(raw.length <= 32768) { "调试参数过大" }
        val future = worker.submit<String> {
            val args = Json.parseToJsonElement(raw).jsonObject
            try { dispatch(action, args, callerPid).toString() }
            catch (error: Exception) { buildJsonObject {
                put("is_error", true); put("message", error.message.orEmpty().take(4000)); put("automatic_retry_safe", false)
                session?.takeIf { action == "attach" || args["session_id"] == JsonPrimitive(it.id) }?.let { put("session_id", it.id); put("state", it.state); put("cleanup_confirmed", it.cleanupConfirmed) }
            }.toString() }
        }
        return try { future.get(18, TimeUnit.SECONDS) }
        catch (timeout: java.util.concurrent.TimeoutException) {
            // Closing a configured connection asks LLDB to remove breakpoints and detach.
            // The operation may already have happened; never automatically submit it again.
            closeSocket(); runCatching { listener?.close() }; future.cancel(true)
            buildJsonObject { put("is_error", true); put("message", "调试操作超时，已关闭连接并请求安全脱离；请查询状态，不能重放操作")
                put("operation_may_have_completed", true); put("automatic_retry_safe", false) }.toString()
        }
    }

    private fun dispatch(action: String, args: JsonObject, callerPid: Int): JsonObject {
        fun field(key: String) = args[key]?.jsonPrimitive?.contentOrNull ?: error("缺少 $key")
        if (action == "capabilities") return buildJsonObject {
            put("backend", "NDK r28c LLDB 19.0.1"); put("packaged", executable.isFile); put("root", Os.getuid() == 0)
            put("mode", "all_stop"); put("needs_ko", false); put("permission_checked_on_attach", true)
        }
        if (action == "target_status") return ProcessGuard.status(inspector.read(args["pid"]!!.jsonPrimitive.int))
        if (action == "attach") {
            val current = session
            require(current == null || current.state in TERMINAL) { "已有调试会话，请先脱离" }
            return attach(args["pid"]!!.jsonPrimitive.int, field("expected_identity"), callerPid)
        }
        val current = session ?: error("没有调试会话")
        require(field("session_id") == current.id) { "调试会话已改变；不能用旧会话操作新目标" }
        current.lastUsed = SystemClock.elapsedRealtime()
        tick()
        if (action == "status") return status(current, args["after_event"]?.jsonPrimitive?.longOrNull ?: 0)
        require(current.state !in TERMINAL) { "会话已结束：${current.state}；重新核实身份并附加" }
        checkIdentity(current)
        val result = when (action) {
            "detach" -> { detach(current, "detached"); status(current) }
            "pause" -> {
                if (current.state == "running" || current.state == "stepping") { current.remote.interrupt(); current.state = "pausing"; event(current, "pause_requested") }
                status(current)
            }
            "continue", "step" -> {
                stopped(current)
                val tid = thread(current, args["thread_id"]?.jsonPrimitive?.contentOrNull)
                val signal = args["signal"]?.jsonPrimitive?.intOrNull ?: 0
                require(signal in 0..64)
                val command = if (action == "step") "vCont;s:$tid" else if (signal != 0) "vCont;C${signal.toString(16)}" else "vCont;c"
                current.remote.send(command)
                current.state = if (action == "step") "stepping" else "running"
                event(current, "${action}_requested", buildJsonObject { put("thread_id", tid); put("signal", signal) })
                status(current)
            }
            "threads" -> { stopped(current); buildJsonObject { put("threads", JsonArray(threads(current).map(::JsonPrimitive))); put("session_id", current.id) } }
            "registers" -> registers(current, args)
            "memory_read" -> {
                stopped(current)
                val address = address(field("address")); val size = args["size"]?.jsonPrimitive?.intOrNull ?: 128
                require(size in 1..4096 && address <= ULong.MAX_VALUE - size.toULong()) { "内存范围无效" }
                val bytes = memory(current, address, size)
                buildJsonObject { put("session_id", current.id); put("address", hex(address)); put("data", bytes.toHex()); put("bytes", bytes.size); put("all_threads_stopped", true)
                    put("architecture", architecture(current)); put("endian", current.info["endian"].orEmpty()) }
            }
            "breakpoint_set" -> {
                stopped(current)
                require(current.breakpoints.size < 64) { "断点数量达到 64 项上限" }
                val address = address(field("address")); val type = args["type"]?.jsonPrimitive?.intOrNull ?: 0
                val arm64 = architecture(current) == "arm64"
                val size = args["size"]?.jsonPrimitive?.intOrNull ?: if (type <= 1 && arm64) 4 else 1
                require(type in 0..4 && size in listOf(1, 2, 4, 8) && address <= ULong.MAX_VALUE - size.toULong())
                if (type <= 1 && arm64) require(size == 4 && address % 4uL == 0uL) { "ARM64 执行断点须四字节对齐" }
                require(current.breakpoints.values.none { it.type == type && it.address == address && it.size == size }) { "该断点已存在，不重复设置" }
                ok(current.remote.request("Z$type,${address.toString(16)},${size.toString(16)}"))
                val bp = Breakpoint(UUID.randomUUID().toString(), type, address, size); current.breakpoints[bp.id] = bp
                event(current, "breakpoint_added", breakpoint(bp)); breakpoint(bp)
            }
            "breakpoint_remove" -> {
                stopped(current)
                val bp = current.breakpoints[field("breakpoint_id")] ?: error("断点不存在或来自旧会话")
                ok(current.remote.request("z${bp.type},${bp.address.toString(16)},${bp.size.toString(16)}"))
                current.breakpoints.remove(bp.id); event(current, "breakpoint_removed", breakpoint(bp))
                buildJsonObject { put("removed", true); put("breakpoint_id", bp.id) }
            }
            "breakpoints" -> buildJsonObject { put("breakpoints", JsonArray(current.breakpoints.values.map(::breakpoint))); put("session_id", current.id) }
            else -> error("不支持的调试操作")
        }
        if (current.state !in TERMINAL) checkIdentity(current)
        return result
    }

    private fun attach(pid: Int, expected: String, callerPid: Int): JsonObject {
        require(pid > 1 && pid != callerPid && pid != AndroidProcess.myPid()) { "不能暂停调试宿主自身，请使用独立测试进程" }
        require(executable.isFile && executable.canExecute()) { "当前 APK 没有可执行的 LLDB 后端" }
        val target = inspector.read(pid)
        require(target.alive && target.identity == expected) { "目标不存在或身份已改变，请重新调用 debug_target_status" }
        require(target.command != context.packageName && !target.command.startsWith(context.packageName + ":")) { "不能暂停承载聊天与 MCP 的宿主进程" }
        require(target.command != executable.absolutePath) { "不能暂停内置 LLDB 服务进程" }
        val name = "qingwei-debug-${UUID.randomUUID()}"
        val serverSocket = LocalServerSocket(name); listener = serverSocket
        var server: Process? = null
        var remote: GdbRemote? = null
        try {
            val process = ProcessBuilder(executable.absolutePath, "gdbserver", "--reverse-connect", "unix-abstract-connect://$name")
                .redirectErrorStream(true).start()
            server = process
            val log = StringBuilder()
            Thread({ process.inputStream.use { stream ->
                val buffer = ByteArray(1024)
                runCatching { while (true) { val n = stream.read(buffer); if (n < 0) break; synchronized(log) { log.append(String(buffer, 0, n)); if (log.length > 8192) log.delete(0, log.length - 8192) } } }
            } }, "qingwei-lldb-log").apply { isDaemon = true; start() }
            val poll = StructPollfd().apply { fd = serverSocket.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            check(Os.poll(arrayOf(poll), 8000) > 0) { "LLDB 连接超时：" + synchronized(log) { log.toString() } }
            val connected = serverSocket.accept(); socket = connected
            require(connected.peerCredentials.uid == Os.getuid()) { "调试连接来源 UID 不匹配" }
            serverSocket.close(); listener = null
            remote = GdbRemote(connected.inputStream.buffered(8192), connected.outputStream.buffered(8192), java.io.Closeable { shutdown(connected) })
            remote.negotiate()
            // Must succeed BEFORE attaching: connection loss must restore software breakpoints
            // and detach, rather than kill a user's target or leave patched instructions behind.
            ok(remote.request("QSetDetachOnError:1"))
            check(!Thread.currentThread().isInterrupted) { "附加请求已取消" }
            val state = Session(UUID.randomUUID().toString(), target, process, remote)
            session = state
            val reply = remote.request("vAttach;${pid.toString(16)}", 10000)
            require(reply.startsWith('T') || reply.startsWith('S')) { "LLDB 附加失败：${reply.take(2000)}" }
            acceptEvent(state, reply)
            require(state.state == "stopped") { "LLDB 附加失败：$reply" }
            state.info = fields(remote.request("qProcessInfo"))
            require(state.info["pid"]?.toIntOrNull(16) == pid) { "LLDB 返回的目标 PID 不匹配" }
            checkIdentity(state)
            event(state, "attached")
            return status(state)
        } catch (error: Exception) {
            // After vAttach the configured server owns restoration on disconnect.
            remote?.close(); closeSocket()
            if (session?.server === server) { session?.state = "attach_failed"; session?.let { event(it, "attach_failed") } }
            if (remote == null) server?.destroy()
            throw error
        } finally { runCatching { serverSocket.close() }; listener = null }
    }

    private fun tick() {
        val current = session ?: return
        if (current.state in TERMINAL) return
        try {
            repeat(64) { val packet = current.remote.receive(0) ?: return@repeat; acceptEvent(current, packet) }
            if (current.state in TERMINAL) return
            val now = SystemClock.elapsedRealtime()
            if (now - current.lastChecked >= 500) { checkIdentity(current); current.lastChecked = now }
            if (now - current.lastUsed > 120000) detach(current, "idle_timeout")
        } catch (error: Exception) {
            if (current.state !in TERMINAL) { current.state = "connection_lost"; event(current, "connection_lost", buildJsonObject { put("message", error.message.orEmpty().take(2000)) }) }
            current.remote.close(); socket = null
        }
    }

    private fun checkIdentity(current: Session) {
        val observed = inspector.read(current.target.pid)
        if (!observed.alive || observed.identity != current.target.identity) {
            detach(current, if (observed.state == "exited") "exited" else "target_changed")
            error("目标已退出、重启或 exec；已结束会话，旧地址与断点不能复用")
        }
    }

    private fun acceptEvent(current: Session, raw: String) {
        val packet = raw.removePrefix("Stop:")
        when (packet.firstOrNull()) {
            'T', 'S' -> {
                require(packet.length >= 3 && packet.substring(1, 3).toIntOrNull(16) != null)
                current.state = "stopped"; current.stop = packet.take(8192)
                val detail = fields(packet.drop(3))
                detail["thread"]?.let { require(validThread(it)); current.thread = it }
                event(current, "stopped", buildJsonObject { put("signal", packet.substring(1, 3).toInt(16)); put("raw_stop", current.stop); put("thread_id", current.thread) })
                if (detail.containsKey("exec") || detail["reason"] == "exec") {
                    event(current, "exec"); detach(current, "target_changed")
                }
            }
            'W', 'X' -> { current.state = "exited"; current.cleanupConfirmed = true; current.breakpoints.clear(); event(current, "exited", buildJsonObject { put("reply", packet.take(1000)) }); current.remote.close(); socket = null }
            'E' -> { current.state = "stopped"; event(current, "backend_error", buildJsonObject { put("reply", packet.take(2000)) }) }
            else -> error("未预期的调试事件：${packet.take(200)}")
        }
    }

    private fun detach(current: Session, reason: String) {
        try {
            if (current.state !in TERMINAL && current.state != "stopped") {
                current.remote.interrupt()
                val stop = current.remote.receive(3000) ?: error("脱离前暂停未确认")
                acceptEvent(current, stop)
            }
            if (current.state !in TERMINAL) { ok(current.remote.request("D", 3000)); current.cleanupConfirmed = true }
            current.breakpoints.clear()
        } catch (_: Exception) { current.cleanupConfirmed = false }
        finally {
            current.state = reason; event(current, reason)
            current.remote.close(); socket = null
            // QSetDetachOnError handles unconfirmed disconnects. Do not forcibly kill the
            // server while it may still be restoring a tracee's patched instructions.
            if (current.cleanupConfirmed) runCatching { if (!current.server.waitFor(1000, TimeUnit.MILLISECONDS)) current.server.destroy() }
        }
    }

    private fun event(current: Session, type: String, detail: JsonObject = JsonObject(emptyMap())) {
        current.sequence++
        current.events += buildJsonObject { put("id", current.sequence); put("time", System.currentTimeMillis()); put("type", type); put("detail", detail) }
        while (current.events.size > 64) current.events.removeFirst()
    }
    private fun status(current: Session, after: Long = 0): JsonObject = buildJsonObject {
        require(after >= 0)
        put("session_id", current.id); put("pid", current.target.pid); put("process_identity", current.target.identity)
        put("session_created_at", current.createdAt)
        put("state", current.state); put("mode", "all_stop"); put("all_threads_stopped", current.state == "stopped")
        put("architecture", architecture(current))
        put("old_addresses_valid", current.state !in TERMINAL); put("cleanup_confirmed", current.cleanupConfirmed)
        put("thread_id", current.thread); put("backend", "NDK r28c LLDB 19.0.1"); put("last_event", current.sequence)
        val pending = current.events.filter { it["id"]!!.jsonPrimitive.long > after }
        val page = pending.take(20)
        put("events", JsonArray(page)); put("has_more_events", pending.size > page.size)
        put("next_after_event", page.lastOrNull()?.get("id")?.jsonPrimitive?.long ?: after)
        put("events_truncated", after < (current.events.firstOrNull()?.get("id")?.jsonPrimitive?.long ?: 1) - 1)
        put("process_info", buildJsonObject { current.info.forEach { (key, value) -> put(key, value) } })
        put("breakpoints", JsonArray(current.breakpoints.values.map(::breakpoint))); put("automatic_retry_safe", false)
    }
    private fun stopped(current: Session) { check(current.state == "stopped") { "此操作需要所有线程暂停；先 debug_pause，再查询 debug_status" } }
    private fun architecture(current: Session): String {
        val triple = current.info["triple"]?.let { runCatching { decodeHex(it).decodeToString() }.getOrNull() }.orEmpty()
        return when {
            triple.startsWith("aarch64") || current.info["cputype"]?.toLongOrNull(16) == 0x100000cL -> "arm64"
            triple.startsWith("x86_64") || current.info["cputype"]?.toLongOrNull(16) == 0x1000007L -> "x86_64"
            triple.isNotEmpty() -> triple
            else -> "unknown"
        }
    }
    private fun threads(current: Session): List<String> {
        val all = mutableListOf<String>(); var response = current.remote.request("qfThreadInfo")
        while (response.startsWith('m')) {
            val page = response.drop(1).split(','); require(page.all(::validThread) && all.size + page.size <= 4096) { "线程列表无效或超过上限" }
            all += page; response = current.remote.request("qsThreadInfo")
        }
        require(response == "l") { "线程枚举未完成" }; return all
    }
    private fun thread(current: Session, requested: String?): String {
        val tid = requested ?: current.thread.ifBlank { threads(current).first() }
        require(validThread(tid)); require(tid in threads(current)) { "线程已退出或不属于该调试目标" }
        return tid
    }
    private fun registers(current: Session, args: JsonObject): JsonObject {
        stopped(current)
        val tid = thread(current, args["thread_id"]?.jsonPrimitive?.contentOrNull)
        ok(current.remote.request("Hg$tid"))
        val raw = current.remote.request("g"); require(raw.length <= 131072 && raw.length % 2 == 0 && raw.all { it in "0123456789abcdefABCDEFx" }) { "寄存器数据无效" }
        val rows = mutableListOf<JsonObject>()
        for (index in 0 until 512) {
            val response = current.remote.request("qRegisterInfo${index.toString(16)}")
            if (response.startsWith('E') || response.isEmpty()) break
            val info = fields(response); val bits = info["bitsize"]?.toIntOrNull() ?: error("寄存器位数缺失")
            val offset = info["offset"]?.toIntOrNull() ?: error("寄存器偏移缺失")
            require(bits in 8..8192 && bits % 8 == 0 && offset in 0..65536)
            val end = offset + bits / 8
            val bytes = if (end * 2 <= raw.length) raw.substring(offset * 2, end * 2) else ""
            val available = bytes.isNotEmpty() && 'x' !in bytes
            rows += buildJsonObject {
                put("index", index); put("name", info["name"].orEmpty()); put("bits", bits); put("generic", info["generic"].orEmpty()); put("available", available)
                if (available) { put("bytes", bytes); if (bits <= 64) put("value", "0x" + if (current.info["endian"] == "little") bytes.chunked(2).reversed().joinToString("") else bytes) }
            }
        }
        return buildJsonObject { put("session_id", current.id); put("thread_id", tid); put("registers", JsonArray(rows)); put("metadata_limit_reached", rows.size == 512); put("endian", current.info["endian"].orEmpty()); put("all_threads_stopped", true) }
    }
    private fun memory(current: Session, address: ULong, size: Int): ByteArray {
        val result = current.remote.request("m${address.toString(16)},${size.toString(16)}")
        require(!result.startsWith('E')) { "调试内存读取失败：$result" }
        return decodeHex(result).also { require(it.size == size) { "调试内存读取不完整" } }
    }
    private fun breakpoint(bp: Breakpoint) = buildJsonObject { put("id", bp.id); put("type", bp.type); put("address", hex(bp.address)); put("size", bp.size) }
    override fun close() {
        runCatching { worker.submit { session?.takeIf { it.state !in TERMINAL }?.let { detach(it, "service_closed") } }.get(4, TimeUnit.SECONDS) }
        closeSocket(); runCatching { listener?.close() }; worker.shutdownNow()
    }
    private fun closeSocket() {
        val old = socket; socket = null
        if (old != null) shutdown(old)
    }
    companion object {
        private fun shutdown(socket: LocalSocket) {
            // close() alone need not wake a read blocked on another thread. shutdown sends
            // EOF to LLDB immediately, allowing QSetDetachOnError to restore and detach.
            runCatching { socket.shutdownInput() }; runCatching { socket.shutdownOutput() }; runCatching { socket.close() }
        }
        private val TERMINAL = setOf("detached", "exited", "target_changed", "connection_lost", "idle_timeout", "attach_failed", "service_closed")
        private fun validThread(value: String) = value.length in 1..64 && value.matches(Regex("(?:p[0-9a-fA-F]+\\.)?[0-9a-fA-F]+"))
        private fun fields(value: String) = value.split(';').mapNotNull { val split = it.indexOf(':'); if (split > 0) it.substring(0, split) to it.substring(split + 1) else null }.toMap()
        private fun ok(reply: String) { check(reply == "OK") { "LLDB 拒绝操作：${reply.take(2000)}" } }
        private fun address(value: String) = if (value.startsWith("0x", true)) value.drop(2).toULong(16) else value.toULong()
        private fun hex(value: ULong) = "0x${value.toString(16)}"
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun decodeHex(value: String): ByteArray {
            require(value.length <= 131072 && value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" }) { "调试十六进制数据无效" }
            return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
