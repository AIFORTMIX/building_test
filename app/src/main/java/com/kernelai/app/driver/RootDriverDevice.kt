package com.kernelai.app.driver

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Small Binder transactions carry shared-memory handles, including for large ioctl packets. */
class RootDriverDevice(context: Context) : DriverDevice {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var binding: Binding? = null
    @Volatile override var lastError: String? = null
        private set
    private inner class Binding : ServiceConnection {
        val ready = CountDownLatch(1)
        @Volatile var remote: IRootDriver? = null
        @Volatile var opened = false
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (binding !== this) { runCatching { RootService.unbind(this) }; ready.countDown(); return }
            remote = IRootDriver.Stub.asInterface(service)
            ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null; opened = false
            synchronized(lock) { if (binding === this) binding = null }
            ready.countDown()
            runCatching { RootService.unbind(this) }
        }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
    }
    override val isOpen: Boolean
        get() = binding?.let { it.opened && it.remote?.asBinder()?.isBinderAlive == true } == true

    override fun open(): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "驱动连接必须在后台线程执行" }
        if (isOpen) return true
        try {
            if (Shell.getCachedShell()?.isRoot == false) Shell.getCachedShell()?.close()
            if (!Shell.getShell().isRoot) {
                lastError = "未获得 Root 授权"
                Shell.getCachedShell()?.close()
                return false
            }
            val session = synchronized(lock) {
                binding ?: Binding().also { next ->
                    binding = next
                    main.post {
                        if (binding !== next) { next.ready.countDown(); return@post }
                        try { RootService.bind(Intent(context, RootDriverService::class.java), next) }
                        catch (error: Exception) { lastError = error.message; next.ready.countDown() }
                    }
                }
            }
            if (!session.ready.await(30, TimeUnit.SECONDS)) { lastError = "Root 驱动服务连接超时"; close(); return false }
            val remote = session.remote ?: run { lastError = lastError ?: "Root 驱动服务未就绪"; close(); return false }
            val connected = remote.open()
            session.opened = connected && binding === session
            lastError = if (session.opened) null else remote.lastError.ifBlank { "驱动连接失败" }
            return session.opened
        } catch (error: Exception) {
            lastError = error.message ?: "Root 驱动服务不可用"
            close()
            return false
        }
    }
    override fun close() {
        val old = synchronized(lock) { binding.also { binding = null } } ?: return
        old.opened = false
        old.ready.countDown()
        // Unbinding destroys the root service and closes its native descriptor.
        main.post { runCatching { RootService.unbind(old) } }
    }
    override fun ioctl(command: Int, arg: ByteArray): Int {
        val session = binding
        val remote = session?.remote
        if (!isOpen || remote == null) return -OsConstants.EBADF
        if (arg.isEmpty() || arg.size > RootDriverService.MAX_PACKET_BYTES) return -OsConstants.EINVAL
        try {
            SharedMemory.create("qingwei-ioctl", arg.size).use { memory ->
                val mapped = memory.mapReadWrite()
                try {
                    mapped.put(arg)
                    val result = remote.ioctl(command, memory, arg.size)
                    mapped.position(0)
                    mapped.get(arg)
                    lastError = if (result < 0) "驱动调用失败，errno=${-result}" else null
                    return result
                } finally { SharedMemory.unmap(mapped) }
            }
        } catch (error: RemoteException) {
            session.opened = false
            lastError = "Root 驱动服务连接已断开"
            return -OsConstants.EPIPE
        } catch (error: Exception) {
            lastError = error.message
            return -OsConstants.EIO
        }
    }
    override fun processSnapshot(pid: Int): ProcessSnapshot {
        require(pid > 0)
        return try {
            val remote = binding?.remote ?: return ProcessSnapshot(pid, "unavailable", detail = "Root 服务未连接")
            val raw = remote.processSnapshot(pid)
            require(raw.length <= 8192) { "进程状态响应过大" }
            kotlinx.serialization.json.Json.decodeFromString(ProcessSnapshot.serializer(), raw).also { require(it.pid == pid) }
        } catch (error: Exception) { ProcessSnapshot(pid, "unavailable", detail = "Root 进程状态查询失败：${error.message}") }
    }
}
