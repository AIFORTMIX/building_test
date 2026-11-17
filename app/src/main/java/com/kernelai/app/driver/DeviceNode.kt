package com.kernelai.app.driver

import android.system.OsConstants
import java.io.File

/**
 * 管理 /dev/qingwei_mcp 设备节点的文件描述符
 */
class DeviceNode : DriverDevice {
    companion object {
        const val DEVICE_PATH = "/dev/qingwei_mcp"

        private val nativeAvailable = try {
            System.loadLibrary("qingwei_mcp_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    @Volatile
    private var fileDescriptor: Int = -1

    override val isOpen: Boolean get() = fileDescriptor >= 0
    @Volatile override var lastError: String? = null
        private set

    @Synchronized
    override fun open(): Boolean {
        if (fileDescriptor >= 0) return true
        if (!nativeAvailable) { lastError = "当前 ABI 的 JNI 库无法加载"; return false }
        if (!File(DEVICE_PATH).exists()) { lastError = "驱动设备节点不存在"; return false }
        fileDescriptor = nativeOpen(DEVICE_PATH)
        lastError = if (fileDescriptor < 0) "打开驱动失败，errno=${-fileDescriptor}" else null
        return fileDescriptor >= 0
    }

    @Synchronized
    override fun close() {
        if (fileDescriptor >= 0) {
            val fd = fileDescriptor
            fileDescriptor = -1
            nativeClose(fd)
        }
    }

    @Synchronized
    override fun ioctl(command: Int, arg: ByteArray): Int {
        if (fileDescriptor < 0) return -OsConstants.EBADF
        val result = nativeIoctl(fileDescriptor, command, arg)
        lastError = if (result < 0) "驱动调用失败，errno=${-result}" else null
        return result
    }

    private external fun nativeOpen(path: String): Int
    private external fun nativeClose(fd: Int): Int
    private external fun nativeIoctl(fd: Int, command: Int, arg: ByteArray): Int
}
