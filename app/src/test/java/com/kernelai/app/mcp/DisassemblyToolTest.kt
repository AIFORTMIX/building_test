package com.kernelai.app.mcp

import com.kernelai.app.driver.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DisassemblyToolTest {
    private class Device(private val data: ByteArray) : DriverDevice {
        override val isOpen = true
        var calls = 0
        override fun open() = true
        override fun close() {}
        override fun processSnapshot(pid: Int) = ProcessSnapshot(pid, "running", "p1:fixture")
        override fun ioctl(command: Int, arg: ByteArray): Int {
            assertEquals(IoctlCommands.CMD_RAW_READ, command); calls++
            ByteBuffer.wrap(arg).order(ByteOrder.LITTLE_ENDIAN).putInt(20, data.size)
            data.copyInto(arg, 40); return 0
        }
    }
    private fun args(address: String = "0x1000") = buildJsonObject {
        put("pid", 123); put("address", address); put("count", 4); put("expected_identity", "p1:fixture")
    }
    @Test fun partialInstructionBytesAreRetainedInsteadOfDiscardedOrPadded() = runTest {
        val data = ByteArray(10) { it.toByte() }; val device = Device(data)
        var decoded = 0
        val tool = McpToolExecutorImpl(device) { bytes, address, count ->
            assertEquals(8, bytes.size); assertEquals(0x1000uL, address); assertEquals(2, count); decoded++
            buildJsonObject { put("engine", "fixture-decoder"); put("instructions", JsonArray(emptyList())) }
        }
        val result = Json.parseToJsonElement(tool.executeTool("disassemble_at", args())).jsonObject
        assertTrue(result["partial_read"]!!.jsonPrimitive.boolean)
        assertEquals(10, result["byte_count"]!!.jsonPrimitive.int)
        assertEquals(2, result["instruction_count"]!!.jsonPrimitive.int)
        assertEquals("0809", result["trailing_bytes"]!!.jsonPrimitive.content)
        assertEquals(20, result["data"]!!.jsonPrimitive.content.length)
        assertEquals(1, decoded); assertEquals(1, device.calls)
    }
    @Test fun lessThanOneInstructionStillReturnsTheActualReadBytes() = runTest {
        val result = Json.parseToJsonElement(McpToolExecutorImpl(Device(byteArrayOf(1, 2, 3))) { _, _, _ -> error("Incomplete instruction must not be decoded") }
            .executeTool("disassemble_at", args())).jsonObject
        assertEquals("010203", result["data"]!!.jsonPrimitive.content)
        assertTrue(result["instructions"]!!.jsonArray.isEmpty())
        assertTrue(result["partial_read"]!!.jsonPrimitive.boolean)
    }
    @Test fun unalignedAddressIsRejectedBeforeMemoryAccess() = runTest {
        val device = Device(ByteArray(4)); val tools = McpToolExecutorImpl(device)
        for (address in listOf("0x1001", "0xfffffffffffffff0")) {
            try { tools.executeTool("disassemble_at", args(address)); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, device.calls)
    }
}
