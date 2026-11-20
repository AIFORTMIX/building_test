package com.kernelai.app.debugger

import com.kernelai.app.mcp.DebugTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class DebugToolsStateTest {
    private fun status(id: String, created: Long, event: Long, state: String) = buildJsonObject {
        put("session_id", id); put("session_created_at", created); put("last_event", event); put("state", state)
    }.toString()
    @Test fun delayedOldSessionCannotReplaceTheNewSessionShownInTheApp() = runTest {
        val responses = ArrayDeque(listOf(status("old", 100, 1, "stopped"), status("new", 200, 1, "running"), status("old", 100, 20, "stopped")))
        val tools = DebugTools { _, _ -> responses.removeFirst() }
        repeat(3) { tools.executeTool("debug_status", JsonObject(emptyMap())) }
        assertEquals(JsonPrimitive("new"), tools.state.value!!["session_id"])
        assertEquals(JsonPrimitive("running"), tools.state.value!!["state"])
    }
    @Test fun arm64DisassemblyRejectsAnActualDifferentTargetArchitecture() = runTest {
        val tools = DebugTools { _, _ -> """{"architecture":"x86_64","endian":"little","data":"00000000"}""" }
        val result = Json.parseToJsonElement(tools.executeTool("debug_disassemble", buildJsonObject { put("session_id", "fixture"); put("address", "0x1000"); put("count", 1) })).jsonObject
        assertEquals(JsonPrimitive(true), result["is_error"])
        assertTrue(result["message"]!!.jsonPrimitive.content.contains("ARM64"))
    }
}
