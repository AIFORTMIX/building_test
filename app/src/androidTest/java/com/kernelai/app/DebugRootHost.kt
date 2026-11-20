package com.kernelai.app

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.mcp.DebugTools
import kotlinx.serialization.json.*
import java.io.FileInputStream
import java.util.UUID

/** AOSP emulator su grants shell, not app UIDs. Bootstrap the actual backend as Root
 * from instrumentation shell; this does not fake ptrace or weaken the production Root gate. */
internal class DebugRootHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val listener = LocalServerSocket("qingwei-debug-test-${UUID.randomUUID()}")
    private val processOutput: android.os.ParcelFileDescriptor
    private val socket: LocalSocket
    private val input: java.io.InputStream
    private val output: java.io.OutputStream
    val tools = DebugTools(::command)
    init {
        val apk = context.applicationInfo.sourceDir
        require(apk.none { it.isWhitespace() })
        val line = "su 0 env CLASSPATH=$apk /system/bin/app_process /system/bin com.kernelai.app.debugger.DebuggerTestHost ${context.packageName} ${listener.localSocketAddress.name} ${Process.myUid()} ${Process.myPid()}"
        processOutput = instrumentation.uiAutomation.executeShellCommand(line)
        val poll = StructPollfd().apply { fd = listener.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        check(Os.poll(arrayOf(poll), 10000) > 0) { "Root test host did not connect" }
        socket = listener.accept(); listener.close()
        check(socket.peerCredentials.uid == 0)
        socket.soTimeout = 20000
        input = socket.inputStream.buffered(8192); output = socket.outputStream.buffered(8192)
        check(Json.parseToJsonElement(readLine()).jsonObject["ready"] == JsonPrimitive(true))
    }
    private fun readLine(): String {
        val data = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read(); check(next >= 0) { "Root test host closed the connection" }
            if (next == '\n'.code) break
            check(data.size() < 512000); data.write(next)
        }
        return data.toString("UTF-8")
    }
    @Synchronized private fun command(action: String, raw: String): String {
        val text = buildJsonObject { put("action", action); put("arguments", Json.parseToJsonElement(raw)) }.toString()
        output.write((text + "\n").toByteArray()); output.flush()
        return readLine()
    }
    fun disconnectDebuggerTransport() { check(Json.parseToJsonElement(command("disconnect_transport", "{}")).jsonObject["disconnected"] == JsonPrimitive(true)) }
    override fun close() {
        runCatching { command("close", "{}") }
        runCatching { socket.close() }; runCatching { listener.close() }; processOutput.close()
    }
}
