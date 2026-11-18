package com.kernelai.app.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MemoryAnalysisToolsTest {
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun pointer(value: Long, size: Int = 8) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
        if (size == 8) putLong(value) else putInt(value.toInt())
    }.array()
    @Test fun arithmeticUsesLosslessUnsigned64AndSignedOffsets() {
        val result = MemoryAnalysisTools.calculate(args("""{"base_address":"0xffffffffffffffff","offsets":["-0x10","1"]}"""))
        assertEquals("0xfffffffffffffff0", result["address"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { MemoryAnalysisTools.calculate(args("""{"base_address":"0xffffffffffffffff","offsets":["1"]}""")) }
        assertThrows(IllegalArgumentException::class.java) { MemoryAnalysisTools.calculate(args("""{"base_address":"0","offsets":["-1"]}""")) }
    }
    @Test fun pointerChainTracesDereferencesAndFinalFieldOffset() = runBlocking {
        val reads = mutableListOf<Long>()
        val tools = MemoryAnalysisTools({ 9 }) { pid, address, size ->
            assertEquals(9, pid); reads.add(address)
            when (address) { 0x1008L -> pointer(0x2000); 0x2010L -> pointer(0x3000); 0x3004L -> byteArrayOf(1, 2); else -> error("unmapped") }
        }
        val result = tools.chain(args("""{"base_address":"0x1000","steps":[{"offset":"8"},{"offset":"0x10"},{"offset":"4","dereference":false}],"read_size":2}"""))
        assertEquals(listOf(0x1008L, 0x2010L, 0x3004L), reads)
        assertEquals("0x3004", result["address"]!!.jsonPrimitive.content)
        assertEquals("0102", result["raw_hex"]!!.jsonPrimitive.content)
        assertFalse(result["is_error"]!!.jsonPrimitive.boolean)
    }
    @Test fun nullPartialAndCyclesReturnTraceAndMcpErrorFlag() = runBlocking {
        for (value in listOf(pointer(0), byteArrayOf(1), pointer(0x1000))) {
            val tools = MemoryAnalysisTools({ 9 }) { _, _, _ -> value }
            val result = tools.chain(args("""{"base_address":"0x1000","steps":[{"offset":"0"},{"offset":"0"}]}"""))
            assertTrue(result["is_error"]!!.jsonPrimitive.boolean)
            assertFalse(result["complete"]!!.jsonPrimitive.boolean)
            assertTrue(result["error"]!!.jsonPrimitive.content.isNotEmpty())
        }
    }
    @Test fun pointerWidthAndTopByteStrippingAreExplicit() = runBlocking {
        val narrow = MemoryAnalysisTools({ 1 }) { _, _, size -> assertEquals(4, size); pointer(0x87654321, 4) }
        assertEquals("0x87654321", narrow.chain(args("""{"base_address":"0x1000","pointer_size":4,"steps":[{"offset":"0"}]}"""))["address"]!!.jsonPrimitive.content)
        val tagged = MemoryAnalysisTools({ 1 }) { _, _, _ -> pointer(0xab00000000002000uL.toLong()) }
        val common = "\"base_address\":\"0x1000\",\"steps\":[{\"offset\":\"0\"}]"
        assertEquals("0xab00000000002000", tagged.chain(args("{$common}"))["address"]!!.jsonPrimitive.content)
        assertEquals("0x2000", tagged.chain(args("{$common,\"strip_top_byte\":true}"))["address"]!!.jsonPrimitive.content)
    }
    @Test fun typedValuesPreserveUnsigned64AndDontFabricatePartialBytes() {
        val tools = MemoryAnalysisTools({ 2 }) { _, _, _ -> ByteArray(8) { 0xff.toByte() } }
        val result = tools.typed(args("""{"address":"0x1000","type":"u64"}"""))
        assertEquals("18446744073709551615", result["value"]!!.jsonPrimitive.content)
        val partial = MemoryAnalysisTools({ 2 }) { _, _, _ -> byteArrayOf(1, 2) }.typed(args("""{"address":"0x1000","type":"u64"}"""))
        assertTrue(partial["is_error"]!!.jsonPrimitive.boolean); assertNull(partial["value"])
    }
    @Test fun structuresOnlyDecodeAvailableFieldsFromCallerLayout() {
        val tools = MemoryAnalysisTools({ 2 }) { _, _, _ -> byteArrayOf(1, 0, 0, 0, 65) }
        val result = tools.structure(args("""{"address":"0x1000","fields":[{"name":"count","offset":"0","type":"i32"},{"name":"pointer","offset":"8","type":"pointer"}]}"""))
        val fields = result["fields"]!!.jsonArray
        assertEquals(1, fields[0].jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, fields[1].jsonObject["value"])
        assertTrue(result["is_error"]!!.jsonPrimitive.boolean)
    }
    @Test fun stringsRespectUtf16UnitsAndReportTruncatedUtf8() {
        val tools = MemoryAnalysisTools({ 2 }) { _, _, _ -> "中文\u0000".toByteArray(Charsets.UTF_16LE) }
        val result = tools.string(args("""{"address":"0x1000","encoding":"UTF-16LE","max_bytes":8}"""))
        assertEquals("中文", result["text"]!!.jsonPrimitive.content)
        assertFalse(result["is_error"]!!.jsonPrimitive.boolean)
        val broken = MemoryAnalysisTools({ 2 }) { _, _, _ -> byteArrayOf(0xe4.toByte()) }.string(args("""{"address":"0x1000"}"""))
        assertTrue(broken["is_error"]!!.jsonPrimitive.boolean)
    }
    @Test fun boundsRejectBeforeMemoryAccess() {
        val tools = MemoryAnalysisTools({ 2 }) { _, _, _ -> error("must not read") }
        assertThrows(IllegalArgumentException::class.java) { tools.typed(args("""{"address":"0x0","type":"i32"}""")) }
        assertThrows(IllegalArgumentException::class.java) { tools.inspect(args("""{"address":"0x1000","size":4096}""")) }
        assertThrows(IllegalArgumentException::class.java) { tools.structure(args("""{"address":"0x1000","fields":[{"name":"x","offset":"4095","type":"u64"}]}""")) }
    }
    @Test fun publishedSchemasIncludePurposeAndBoundedNestedLayouts() {
        val definitions = McpToolExecutorImpl(object : com.kernelai.app.driver.DriverDevice {
            override val isOpen = false
            override fun open(): Boolean = error("no device access for definitions")
            override fun close() {}
            override fun ioctl(command: Int, arg: ByteArray): Int = error("no device access")
        }).getToolDefinitions()
        assertEquals(23, definitions.size)
        assertEquals(23, definitions.map { it.name }.distinct().size)
        assertTrue(definitions.all { it.inputSchema["properties"]!!.jsonObject.containsKey("_purpose") })
        assertEquals(32, definitions.first { it.name == "pointer_chain" }.inputSchema["properties"]!!.jsonObject["steps"]!!.jsonObject["maxItems"]!!.jsonPrimitive.int)
    }
}
