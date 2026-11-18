package com.kernelai.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.data.WorkspaceRepository
import com.kernelai.app.mcp.WorkspaceTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WorkspaceToolsTest {
    @Test fun realPrivateWorkspaceChecksVersionsPermissionsAndSymlinks(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "workspace-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scopedContext = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        val repository = WorkspaceRepository(scopedContext); val tools = WorkspaceTools(repository)
        val runtime = com.kernelai.app.mcp.McpRuntime(tools)
        runtime.start(0)
        val transport = com.kernelai.app.mcp.McpHttpToolExecutor(runtime::endpoint, tools.getToolDefinitions())
        suspend fun call(name: String, fields: Map<String, String> = emptyMap()): JsonObject = Json.parseToJsonElement(tools.executeTool(name, buildJsonObject {
            if (name != "workspace_list") put("workspace_id", "default")
            fields.forEach { (key, value) -> put(key, value) }
        })).jsonObject
        try {
            assertTrue(transport.getToolDefinitions().any { it.name == "workspace_edit_file" })
            val exposed = Json.parseToJsonElement(transport.executeTool("workspace_list", buildJsonObject {})).jsonObject
            assertEquals("default", exposed["workspaces"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
            call("workspace_create_directory", mapOf("path" to "notes"))
            assertTrue(call("workspace_write_file", mapOf("path" to "notes/report.md", "content" to "# 初稿"))["saved"]!!.jsonPrimitive.boolean)
            val read = call("workspace_read_file", mapOf("path" to "notes/report.md"))
            val version = read["sha256"]!!.jsonPrimitive.content
            assertEquals("# 初稿", read["content"]!!.jsonPrimitive.content)
            assertTrue(call("workspace_edit_file", mapOf("path" to "notes/report.md", "expected_sha256" to version, "old_text" to "初稿", "new_text" to "终稿"))["saved"]!!.jsonPrimitive.boolean)
            assertTrue(call("workspace_edit_file", mapOf("path" to "notes/report.md", "expected_sha256" to version, "old_text" to "终稿", "new_text" to "不应覆盖"))["is_error"]!!.jsonPrimitive.boolean)
            assertEquals("# 终稿", call("workspace_read_file", mapOf("path" to "notes/report.md"))["content"]!!.jsonPrimitive.content)
            val outside = File(directory, "outside.txt").apply { writeText("private") }
            android.system.Os.symlink(outside.path, File(directory, "workspaces/files/default/link").path)
            assertTrue(call("workspace_read_file", mapOf("path" to "link"))["is_error"]!!.jsonPrimitive.boolean)
            assertTrue(call("workspace_read_file", mapOf("path" to "../outside.txt"))["is_error"]!!.jsonPrimitive.boolean)
            repository.enable("default", false)
            assertTrue(call("workspace_list")["workspaces"]!!.jsonArray.isEmpty())
            assertTrue(call("workspace_read_file", mapOf("path" to "notes/report.md"))["is_error"]!!.jsonPrimitive.boolean)
            repository.enable("default", true)
            val reloaded = WorkspaceRepository(scopedContext); reloaded.load()
            assertTrue(reloaded.workspaces.value.single().enabled)
            // Optional manual acceptance against a folder chosen through the real Android picker.
            InstrumentationRegistry.getArguments().getString("verify_workspace_tree")?.let { id ->
                val actual = WorkspaceTools(KernelAiApplication.instance.workspaceRepository)
                suspend fun selected(name: String, fields: Map<String, String>): JsonObject = Json.parseToJsonElement(actual.executeTool(name, buildJsonObject {
                    put("workspace_id", id); fields.forEach { (key, value) -> put(key, value) }
                })).jsonObject.also { require(it["is_error"]?.jsonPrimitive?.booleanOrNull != true) { it.toString() } }
                val path = "qingwei-verification-${UUID.randomUUID()}.md"
                selected("workspace_write_file", mapOf("path" to path, "content" to "# SAF 初稿"))
                val first = selected("workspace_read_file", mapOf("path" to path))
                selected("workspace_edit_file", mapOf("path" to path, "expected_sha256" to first["sha256"]!!.jsonPrimitive.content, "old_text" to "初稿", "new_text" to "终稿"))
                assertEquals("# SAF 终稿", selected("workspace_read_file", mapOf("path" to path))["content"]!!.jsonPrimitive.content)
            }
        } finally { transport.close(); runtime.stop(); directory.deleteRecursively() }
    }
}
