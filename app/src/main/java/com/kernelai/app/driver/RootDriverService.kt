package com.kernelai.app.driver

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SharedMemory
import android.system.OsConstants
import com.topjohnwu.superuser.ipc.RootService

/** Native ioctls run in this bound root process; the kernel capability check stays intact. */
class RootDriverService : RootService() {
    private val device = DeviceNode()
    private val inspector = ProcessInspector()
    private val allowedUid by lazy { packageManager.getApplicationInfo(packageName, 0).uid }
    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != allowedUid && uid != 0) throw SecurityException("Root driver caller rejected")
    }
    private val binder = object : IRootDriver.Stub() {
        override fun processRecords(pid: Int, kind: String, offset: Int, limit: Int, expectedIdentity: String?, expectedRevision: String?): String {
            checkCaller()
            return ProcRecords().read(pid, kind, offset, limit, expectedIdentity, expectedRevision)
        }
        override fun processSnapshot(pid: Int): String {
            checkCaller()
            return kotlinx.serialization.json.Json.encodeToString(ProcessSnapshot.serializer(), inspector.read(pid))
        }
        override fun open(): Boolean {
            checkCaller()
            if (!device.open()) return false
            // Opening a path alone does not establish a working driver connection.
            if (device.ioctl(IoctlCommands.CMD_GET_MODULE_INFO, ByteArray(56)) < 0) {
                device.close()
                return false
            }
            return true
        }
        override fun close() { checkCaller(); device.close() }
        override fun getLastError(): String { checkCaller(); return device.lastError.orEmpty() }
        override fun ioctl(command: Int, memory: SharedMemory, size: Int): Int {
            memory.use {
                checkCaller()
                if (size <= 0 || size > MAX_PACKET_BYTES || size > memory.size) return -OsConstants.EINVAL
                val mapped = memory.mapReadWrite()
                try {
                    val bytes = ByteArray(size)
                    mapped.get(bytes)
                    val result = device.ioctl(command, bytes)
                    mapped.position(0)
                    mapped.put(bytes)
                    return result
                } finally { SharedMemory.unmap(mapped) }
            }
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { device.close(); super.onDestroy() }
    companion object { const val MAX_PACKET_BYTES = 40 + 16 * 1024 * 1024 }
}
