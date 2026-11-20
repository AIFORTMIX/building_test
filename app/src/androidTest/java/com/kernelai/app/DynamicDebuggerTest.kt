package com.kernelai.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class DynamicDebuggerTest {
    private lateinit var host: DebugRootHost
    private val tools get() = host.tools
    @Before fun startHost() { host = DebugRootHost() }
    @After fun closeHost() { if (::host.isInitialized) host.close() }
    private suspend fun call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        return Json.parseToJsonElement(tools.executeTool(name, args)).jsonObject.also { check(it["is_error"] != JsonPrimitive(true)) { it.toString() } }
    }
    private fun args(id: String, block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("session_id", id); block() }
    private suspend fun stopped(id: String) = withTimeout(10000) {
        var state: JsonObject
        do {
            delay(60); state = call("debug_status", args(id))
            check(state["state"]?.jsonPrimitive?.content in listOf("running", "stepping", "pausing", "stopped")) { "Unexpected session state: $state" }
        } while (state["state"] != JsonPrimitive("stopped"))
        state
    }

    @Test fun rootLldbReallyStopsThreadsReadsRegistersStepsAndHitsSoftwareBreakpointWithoutKo(): Unit = runBlocking {
        DebugFixture().use { fixture ->
            val capability = call("debug_capabilities")
            assertEquals(JsonPrimitive(true), capability["packaged"])
            assertEquals(JsonPrimitive(true), capability["root"])
            val self = Json.parseToJsonElement(tools.executeTool("debug_attach", buildJsonObject {
                put("pid", android.os.Process.myPid()); put("expected_identity", "unused-host-guard")
            })).jsonObject
            assertEquals(JsonPrimitive(true), self["is_error"])
            assertTrue(self["message"]!!.jsonPrimitive.content.contains("宿主"))
            val target = call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
            val attached = call("debug_attach", buildJsonObject { put("pid", fixture.pid); put("expected_identity", target["process_identity"]!!) })
            val id = attached["session_id"]!!.jsonPrimitive.content
            try {
                assertEquals("stopped", attached["state"]!!.jsonPrimitive.content)
                assertTrue(fixture.tracerPid() > 0)
                val threads = call("debug_threads", args(id))["threads"]!!.jsonArray
                assertTrue(threads.size >= 2)
                val marker = call("debug_memory_read", args(id) { put("address", fixture.marker); put("size", 8) })
                assertEquals("8877665544332211", marker["data"]!!.jsonPrimitive.content)
                val before = call("debug_memory_read", args(id) { put("address", fixture.counter); put("size", 8) })
                delay(120)
                assertEquals(before["data"], call("debug_memory_read", args(id) { put("address", fixture.counter); put("size", 8) })["data"])
                val registers = call("debug_registers", args(id))["registers"]!!.jsonArray
                assertTrue(registers.any { it.jsonObject["generic"] == JsonPrimitive("pc") && it.jsonObject["available"] == JsonPrimitive(true) })
                // The main fixture thread intentionally blocks forever in pause(). Step the
                // worker, whose sleep is bounded; stepping a blocked syscall need not finish.
                val worker = threads.map { it.jsonPrimitive.content }.first { it != fixture.pid.toString(16) }
                call("debug_step", args(id) { put("thread_id", worker) })
                val stepped = stopped(id)
                assertTrue(stepped["events"]!!.jsonArray.any { it.jsonObject["type"] == JsonPrimitive("step_requested") })
                val architecture = marker["architecture"]?.jsonPrimitive?.content
                val bp = call("debug_breakpoint_set", args(id) { put("address", fixture.tick); put("type", 0); put("size", if (architecture == "arm64") 4 else 1) })
                call("debug_continue", args(id))
                val hit = stopped(id)
                assertTrue(hit["events"]!!.jsonArray.any { it.jsonObject["type"] == JsonPrimitive("stopped") && it.jsonObject["detail"]!!.jsonObject["signal"] == JsonPrimitive(5) })
                call("debug_breakpoint_remove", args(id) { put("breakpoint_id", bp["id"]!!) })
                assertTrue(call("debug_breakpoint_list", args(id))["breakpoints"]!!.jsonArray.isEmpty())
                call("debug_continue", args(id)); delay(80); call("debug_pause", args(id)); stopped(id)
                val detached = call("debug_detach", args(id))
                assertEquals(JsonPrimitive(true), detached["cleanup_confirmed"])
                assertEquals(0, fixture.tracerPid())
                assertEquals(JsonPrimitive(true), call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })["running"])
            } finally { tools.executeTool("debug_detach", args(id)) }
        }
    }

    @Test fun lostDebuggerTransportRestoresSoftwareBreakpointAndLeavesTargetAlive(): Unit = runBlocking {
        DebugFixture().use { fixture ->
            val target = call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
            val attached = call("debug_attach", buildJsonObject { put("pid", fixture.pid); put("expected_identity", target["process_identity"]!!) })
            val id = attached["session_id"]!!.jsonPrimitive.content
            call("debug_breakpoint_set", args(id) { put("address", fixture.tick); put("type", 0); put("size", if (attached["architecture"] == JsonPrimitive("arm64")) 4 else 1) })
            host.disconnectDebuggerTransport()
            withTimeout(10000) { while (fixture.tracerPid() != 0) delay(100) }
            val live = call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
            assertEquals(JsonPrimitive(true), live["running"])
            assertFalse(live["task_state"]!!.jsonPrimitive.content in listOf("T", "t"))
            assertEquals(target["process_identity"], live["process_identity"])
            val ended = call("debug_status", args(id))
            assertEquals(JsonPrimitive(false), ended["old_addresses_valid"])
            assertEquals(JsonPrimitive(false), ended["cleanup_confirmed"])
        }
    }

    @Test fun eventPaginationReturnsEveryRetainedEventWithoutSkippingTheSecondPage(): Unit = runBlocking {
        DebugFixture().use { fixture ->
            val target = call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
            val attached = call("debug_attach", buildJsonObject { put("pid", fixture.pid); put("expected_identity", target["process_identity"]!!) })
            val id = attached["session_id"]!!.jsonPrimitive.content
            try {
                repeat(7) { call("debug_continue", args(id)); delay(20); call("debug_pause", args(id)); stopped(id) }
                val ids = mutableListOf<Long>(); var cursor = 0L
                var page: JsonObject
                do {
                    page = call("debug_status", args(id) { put("after_event", cursor) })
                    ids += page["events"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long }
                    cursor = page["next_after_event"]!!.jsonPrimitive.long
                } while (page["has_more_events"] == JsonPrimitive(true))
                assertTrue(ids.size > 20); assertEquals(ids.size, ids.distinct().size)
                assertEquals(page["last_event"]!!.jsonPrimitive.long, ids.last())
                assertTrue(ids.zipWithNext().all { (a, b) -> b == a + 1 })
            } finally { tools.executeTool("debug_detach", args(id)) }
        }
    }

    @Test fun execEndsTheOldSessionAndRequiresFreshBinding(): Unit = runBlocking {
        DebugFixture().use { fixture ->
            val target = call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
            val attached = call("debug_attach", buildJsonObject { put("pid", fixture.pid); put("expected_identity", target["process_identity"]!!) })
            val id = attached["session_id"]!!.jsonPrimitive.content
            tools.executeTool("debug_continue", args(id) { put("signal", 10) })
            withTimeout(10000) { while (call("debug_status", args(id))["old_addresses_valid"] == JsonPrimitive(true)) delay(100) }
            val old = Json.parseToJsonElement(tools.executeTool("debug_registers", args(id))).jsonObject
            assertEquals(JsonPrimitive(true), old["is_error"])
        }
    }

    @Test fun targetExitInvalidatesSessionAndOldMemoryRequestsFail(): Unit = runBlocking {
        DebugFixture().use { fixture ->
            val target = call("debug_target_status", buildJsonObject { put("pid", fixture.pid) })
            val attached = call("debug_attach", buildJsonObject { put("pid", fixture.pid); put("expected_identity", target["process_identity"]!!) })
            val id = attached["session_id"]!!.jsonPrimitive.content
            fixture.terminate()
            withTimeout(10000) {
                while (call("debug_status", args(id))["old_addresses_valid"] == JsonPrimitive(true)) delay(100)
            }
            val read = Json.parseToJsonElement(tools.executeTool("debug_memory_read", args(id) { put("address", fixture.marker); put("size", 8) })).jsonObject
            assertEquals(JsonPrimitive(true), read["is_error"])
        }
    }
}
