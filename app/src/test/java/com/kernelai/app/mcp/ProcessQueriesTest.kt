package com.kernelai.app.mcp

import com.kernelai.app.driver.DriverDevice
import com.kernelai.app.driver.IoctlCommands
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProcessQueriesTest {
    private class Processes(val total: Int = 1050) : DriverDevice {
        override val isOpen = true
        override fun open() = true
        override fun close() {}
        val offsets = mutableListOf<Int>()
        var resolveCalls = 0
        var error = 0
        var stall = false
        override fun ioctl(command: Int, arg: ByteArray): Int {
            if (error != 0) return error
            val buf = ByteBuffer.wrap(arg).order(ByteOrder.LITTLE_ENDIAN)
            if (command == IoctlCommands.CMD_RESOLVE_PKG) {
                resolveCalls++
                val name = arg.copyOfRange(0, 256).toString(Charsets.UTF_8).substringBefore('\u0000')
                val pid = if (name == "com.tencent.mm") 27240 else 0
                buf.putInt(256, pid); buf.putInt(260, if (pid > 0) 1 else 0)
                return 0
            }
            check(command == IoctlCommands.CMD_LIST_PROCS)
            val limit = buf.getInt(0); val offset = buf.getInt(12)
            offsets.add(offset)
            val count = if (stall) 0 else (total - offset).coerceIn(0, limit)
            buf.putInt(4, count); buf.putInt(8, total)
            repeat(count) { n ->
                val index = offset + n
                val name = when (index) { 900 -> "com.tencent.mm"; 1040 -> "com.tencent.mm:push"; else -> "worker.$index" }
                val pid = when (index) { 900 -> 27240; 1040 -> 30478; else -> 1000 + index }
                buf.position(32 + 296 * n)
                buf.putInt(pid); buf.putInt(1); buf.putInt(pid)
                buf.put(ByteArray(16).also { name.toByteArray().take(15).toByteArray().copyInto(it) })
                buf.put(ByteArray(256).also { name.toByteArray().copyInto(it) })
                buf.putLong(0); buf.putInt(1)
            }
            return 0
        }
    }
    private suspend fun query(driver: Processes, args: String) = Json.parseToJsonElement(
        ProcessQueries(driver).list(Json.parseToJsonElement(args).jsonObject)).jsonObject

    @Test fun reported1050ProcessWechatCaseSearchesBeyondFirst50And256() = runBlocking {
        val driver = Processes()
        val result = query(driver, """{"filter":"tencent","max_count":50}""")
        assertEquals(listOf(0, 512, 1024), driver.offsets)
        assertEquals(listOf(27240, 30478), result["processes"]!!.jsonArray.map { it.jsonObject["pid"]!!.jsonPrimitive.int })
        assertEquals(1050, result["scanned_count"]!!.jsonPrimitive.int)
        assertTrue(result["search_complete"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, result["next_offset"])
    }
    @Test fun unfilteredPageExplicitlyReportsRemainingRawProcesses() = runBlocking {
        val driver = Processes()
        val result = query(driver, """{"max_count":50}""")
        assertEquals(listOf(0), driver.offsets)
        assertEquals(50, result["returned_count"]!!.jsonPrimitive.int)
        assertEquals(50, result["next_offset"]!!.jsonPrimitive.int)
        assertTrue(result["has_more"]!!.jsonPrimitive.boolean)
        assertFalse(result["search_complete"]!!.jsonPrimitive.boolean)
    }
    @Test fun filteredResultLimitDoesNotSkipUnconsumedPartOfRawPage() = runBlocking {
        val driver = Processes()
        val first = query(driver, """{"filter":"worker","max_count":3}""")
        assertEquals(3, first["next_offset"]!!.jsonPrimitive.int)
        val next = query(driver, """{"filter":"worker","max_count":3,"offset":3}""")
        assertEquals(1003, next["processes"]!!.jsonArray.first().jsonObject["pid"]!!.jsonPrimitive.int)
    }
    @Test fun packageResolutionUsesNativeIoctlInsteadOfFirst256Entries() {
        val driver = Processes()
        assertEquals(27240, ProcessQueries(driver).requirePid("com.tencent.mm"))
        assertEquals(1, driver.resolveCalls)
        assertTrue(driver.offsets.isEmpty())
        assertFalse(ProcessQueries(driver).resolve("com.tencent.mm.fake").running)
    }
    @Test fun searchBudgetReturnsIncompleteInsteadOfFalseAbsence() = runBlocking {
        val driver = Processes(20_000)
        val result = query(driver, """{"filter":"does.not.exist"}""")
        assertEquals(ProcessQueries.MAX_SCAN_PAGES, driver.offsets.size)
        assertTrue(result["has_more"]!!.jsonPrimitive.boolean)
        assertTrue(result["scan_limit_reached"]!!.jsonPrimitive.boolean)
        assertFalse(result["search_complete"]!!.jsonPrimitive.boolean)
        assertEquals("not_observed", result["match_status"]!!.jsonPrimitive.content)
    }
    @Test fun ioctlFailuresAndStalledPagesAreNotReportedAsEmptySuccess() = runBlocking {
        for (driver in listOf(Processes().apply { error = -1 }, Processes().apply { stall = true })) {
            try { query(driver, "{}"); fail("Expected query failure") } catch (_: java.io.IOException) {}
        }
    }
    @Test fun packageLengthAndNulAreValidatedBeforeDeviceAccess() {
        val driver = Processes()
        for (name in listOf("", "a".repeat(256), "com.tencent.mm\u0000other")) {
            try { ProcessQueries(driver).resolve(name); fail("Expected invalid package") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(0, driver.resolveCalls)
    }
}
