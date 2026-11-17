package com.kernelai.app

import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernelai.app.driver.DeviceNode
import com.kernelai.app.driver.IoctlCommands
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes real x86_64/ARM64 JNI against /dev/null; no root or Qingwei device is assumed. */
@RunWith(AndroidJUnit4::class)
class NativeBoundaryTest {
    @Test fun nativeOpenPreservesErrnoAndIoctlRejectsWrongBufferSizes() {
        val device = DeviceNode()
        val open = DeviceNode::class.java.getDeclaredMethod("nativeOpen", String::class.java).apply { isAccessible = true }
        val close = DeviceNode::class.java.getDeclaredMethod("nativeClose", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val ioctl = DeviceNode::class.java.getDeclaredMethod("nativeIoctl", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, ByteArray::class.java).apply { isAccessible = true }
        assertEquals(-OsConstants.ENOENT, open.invoke(device, "/dev/qingwei_test_node_missing"))
        val fd = open.invoke(device, "/dev/null") as Int
        assertTrue(fd >= 0)
        try {
            assertEquals(-OsConstants.EINVAL, ioctl.invoke(device, fd, IoctlCommands.CMD_GET_MODULE_INFO, ByteArray(1)))
            assertEquals(-OsConstants.ENOTTY, ioctl.invoke(device, fd, IoctlCommands.CMD_GET_MODULE_INFO, ByteArray(56)))
        } finally { close.invoke(device, fd) }
    }
}
