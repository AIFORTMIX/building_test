package com.kernelai.app

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.mcp.McpHostService
import com.kernelai.app.ui.MainActivity
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundMcpTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as KernelAiApplication
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!condition()) { if (SystemClock.uptimeMillis() > until) throw AssertionError("MCP host state timed out"); SystemClock.sleep(30) }
    }
    private fun rpc(port: Int, token: String, method: String = "ping"): String {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method)
            if (method == "tools/call") putJsonObject("params") { put("name", "address_calculate"); putJsonObject("arguments") { put("base_address", "0x1000"); putJsonArray("offsets") { add("0x20") } } }
        }
        return http.newCall(Request.Builder().url("http://127.0.0.1:$port/mcp").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
            Assert.assertEquals(200, response.code); response.body!!.string()
        }
    }
    @After fun stop() {
        if (InstrumentationRegistry.getArguments().getString("keep_external_mcp_fixture") == "true" && app.mcpRuntime.state.value.exposed) {
            // Opt-in device-lifecycle acceptance fixture. Private test output; never log the credential.
            File(app.filesDir, "external-mcp-test.json").writeText(buildJsonObject {
                put("port", app.mcpRuntime.port); put("token", app.mcpRuntime.externalAccessToken())
            }.toString())
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); return
        }
        instrumentation.runOnMainSync { McpHostService.setAccess(app, false, stopAll = true) }
        await { !McpHostService.busy.value && !McpHostService.running.value }
        File(app.filesDir, "external-mcp-test.json").delete()
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
    @Test fun externalEndpointSurvivesActivityDestructionRecreationAndHostRecovery() {
        var scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { McpHostService.setAccess(app, true) }
        await { McpHostService.running.value && !McpHostService.busy.value && app.mcpRuntime.state.value.exposed }
        val port = app.mcpRuntime.port; val token = app.mcpRuntime.externalAccessToken()
        Assert.assertTrue(McpHostService.protection.value)
        scenario.close()
        repeat(3) { Assert.assertTrue(rpc(port, token).contains("result")); Assert.assertTrue(rpc(port, token, "tools/call").contains("0x1020")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.recreate()
        await { app.mcpRuntime.state.value.exposed }
        Assert.assertEquals(port, app.mcpRuntime.port); Assert.assertEquals(token, app.mcpRuntime.externalAccessToken())
        scenario.close()
        // Simulate loss of the listener and a system service restore; keep the requested preference and encrypted key.
        app.mcpRuntime.stop()
        instrumentation.runOnMainSync { McpHostService.restore(app) }
        await { app.mcpRuntime.isRunning && app.mcpRuntime.state.value.exposed }
        Assert.assertEquals(port, app.mcpRuntime.port); Assert.assertEquals(token, app.mcpRuntime.externalAccessToken())
        Assert.assertTrue(rpc(port, token, "tools/call").contains("0x1020"))
        Assert.assertTrue(app.mcpRuntime.audit.entries.value.any { it.external && it.tool == "address_calculate" })
    }
    @Test fun explicitlyClosingExposureReleasesProtectionAndRotatesTheCredentialOnReenable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { McpHostService.setAccess(app, true) }
            await { McpHostService.running.value && !McpHostService.busy.value }
            val token = app.mcpRuntime.externalAccessToken()
            scenario.onActivity { McpHostService.setAccess(app, false) }
            await { !McpHostService.running.value && !McpHostService.busy.value && !McpHostService.protection.value }
            Assert.assertFalse(app.mcpRuntime.state.value.exposed)
            scenario.onActivity { McpHostService.setAccess(app, true) }
            await { McpHostService.running.value && !McpHostService.busy.value }
            Assert.assertNotEquals(token, app.mcpRuntime.externalAccessToken())
        }
    }
}
