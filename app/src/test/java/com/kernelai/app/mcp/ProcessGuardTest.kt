package com.kernelai.app.mcp

import com.kernelai.app.driver.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProcessGuardTest {
    private class Device : DriverDevice {
        override val isOpen = true
        var snapshot = ProcessSnapshot(321, "running", "p1:first", "100")
        var ioctls = 0
        var replaceOnRead = false
        override fun open() = true
        override fun close() {}
        override fun processSnapshot(pid: Int) = snapshot.copy(pid = pid)
        override fun ioctl(command: Int, arg: ByteArray): Int {
            ioctls++
            check(command == IoctlCommands.CMD_RAW_READ)
            val b = ByteBuffer.wrap(arg).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(20, b.getInt(16))
            if (arg.size >= 48) b.putLong(40, 0x2000)
            if (replaceOnRead) snapshot = snapshot.copy(identity = "p1:second", startTicks = "200")
            return 0
        }
    }
    private fun args(identity: String? = "p1:first") = buildJsonObject {
        put("pid", 321); put("address", "0x1000"); put("size", 8)
        if (identity != null) put("expected_identity", identity)
    }
    @Test fun addressReadRequiresAnExplicitProcessIdentity() = runTest {
        val device = Device(); val result = Json.parseToJsonElement(McpToolExecutorImpl(device).executeTool("memory_read", args(null))).jsonObject
        assertEquals("process_identity_required", result["error_code"]!!.jsonPrimitive.content); assertEquals(0, device.ioctls)
    }
    @Test fun reusedPidIsRejectedBeforeAnyIoctl() = runTest {
        val device = Device().apply { snapshot = snapshot.copy(identity = "p1:new", startTicks = "200") }
        val result = Json.parseToJsonElement(McpToolExecutorImpl(device).executeTool("memory_read", args())).jsonObject
        assertTrue(result["requires_rebind"]!!.jsonPrimitive.boolean); assertEquals(0, device.ioctls)
    }
    @Test fun exitedAndUnverifiableTargetsFailClosed() = runTest {
        for (state in listOf("exited", "unavailable")) {
            val device = Device().apply { snapshot = ProcessSnapshot(321, state) }
            val result = Json.parseToJsonElement(McpToolExecutorImpl(device).executeTool("memory_read", args())).jsonObject
            assertTrue(result["is_error"]!!.jsonPrimitive.boolean); assertEquals(0, device.ioctls)
        }
    }
    @Test fun successfulReadCarriesTheCheckedIdentity() = runTest {
        val device = Device(); val result = Json.parseToJsonElement(McpToolExecutorImpl(device).executeTool("memory_read", args())).jsonObject
        assertEquals("p1:first", result["process_identity"]!!.jsonPrimitive.content)
        assertTrue(result["process_checked"]!!.jsonPrimitive.boolean); assertEquals(1, device.ioctls)
    }
    @Test fun changeDuringReadDiscardsDataAndNeverRetries() = runTest {
        val device = Device().apply { replaceOnRead = true }
        val result = Json.parseToJsonElement(McpToolExecutorImpl(device).executeTool("memory_read", args())).jsonObject
        assertTrue(result["is_error"]!!.jsonPrimitive.boolean); assertFalse(result.containsKey("data"))
        assertFalse(result["automatic_retry_safe"]!!.jsonPrimitive.boolean); assertEquals(1, device.ioctls)
    }
    @Test fun pointerWalkStopsBeforeReadingTheReplacementProcess() = runTest {
        val device = Device().apply { replaceOnRead = true }
        val args = buildJsonObject { put("pid", 321); put("expected_identity", "p1:first"); put("base_address", "0x1000")
            putJsonArray("steps") { repeat(2) { addJsonObject { put("offset", "0x0") } } }
        }
        val result = Json.parseToJsonElement(McpToolExecutorImpl(device).executeTool("pointer_chain", args)).jsonObject
        assertTrue(result["requires_rebind"]!!.jsonPrimitive.boolean); assertEquals(1, device.ioctls)
    }
    @Test fun statusReportsSameIdentityAndDoesNotPretendUnknownMeansExited() = runTest {
        val device = Device(); val executor = McpToolExecutorImpl(device)
        val same = Json.parseToJsonElement(executor.executeTool("process_status", buildJsonObject { put("pid", 321); put("previous_identity", "p1:first") })).jsonObject
        assertTrue(same["previous_matches"]!!.jsonPrimitive.boolean)
        device.snapshot = ProcessSnapshot(321, "unavailable")
        val unknown = Json.parseToJsonElement(executor.executeTool("process_status", buildJsonObject { put("pid", 321) })).jsonObject
        assertEquals("unavailable", unknown["state"]!!.jsonPrimitive.content)
    }
    @Test fun mutatingToolFailuresNeverClaimAnAutomaticRollback() {
        val error = TargetProcessChanged(ProcessSnapshot(321, "changed"), "changed")
        val result = ProcessGuard.failure(error, mayHaveCompleted = true)
        assertTrue(result["operation_may_have_completed"]!!.jsonPrimitive.boolean)
        assertFalse(result["automatic_retry_safe"]!!.jsonPrimitive.boolean)
    }
}
