package com.kernelai.app.debugger

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.serialization.json.*

/** Instrumentation bootstrap only. Release APKs never contain this entry point. */
object DebuggerTestHost {
    @JvmStatic fun main(args: Array<String>) {
        require(android.system.Os.getuid() == 0 && args.size == 4)
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext(args[0], Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(args[1], LocalSocketAddress.Namespace.ABSTRACT))
        require(socket.peerCredentials.uid == args[2].toInt())
        val debugger = RootDebugger(context)
        val output = socket.outputStream.bufferedWriter()
        fun send(text: String) { output.write(text); output.newLine(); output.flush() }
        try {
            send("{\"ready\":true,\"root_uid\":0,\"host_pid\":${android.os.Process.myPid()}}")
            socket.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    require(line.length <= 32768)
                    val request = Json.parseToJsonElement(line).jsonObject
                    val action = request["action"]!!.jsonPrimitive.content
                    if (action == "close") { debugger.close(); send("{\"closed\":true}"); break }
                    if (action == "disconnect_transport") {
                        RootDebugger::class.java.getDeclaredMethod("closeSocket").apply { isAccessible = true }.invoke(debugger)
                        send("{\"disconnected\":true}"); continue
                    }
                    send(debugger.call(action, request["arguments"]!!.toString(), args[3].toInt()))
                }
            }
        } finally { debugger.close(); socket.close() }
        kotlin.system.exitProcess(0)
    }
}
