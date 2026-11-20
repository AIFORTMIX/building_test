package com.kernelai.app

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream

/** Only shipped in debug APKs; never attaches to or kills an unrelated application. */
internal class DebugFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val executable = File(instrumentation.targetContext.applicationInfo.nativeLibraryDir, "libqingwei_debug_fixture.so")
    private val descriptor: ParcelFileDescriptor
    private val reader: java.io.BufferedReader
    val pid: Int
    val tick: String
    val marker: String
    val counter: String
    init {
        check(executable.isFile) { "Debug fixture is not packaged" }
        // UiAutomation executes argv directly; shell quotes/redirection would become literal args.
        check(executable.absolutePath.none { it.isWhitespace() })
        descriptor = instrumentation.uiAutomation.executeShellCommand("su 0 ${executable.absolutePath}")
        reader = FileInputStream(descriptor.fileDescriptor).bufferedReader()
        val line = reader.readLine().orEmpty()
        val fields = line.trim().split(Regex("\\s+"))
        if (!(fields.size == 4 && fields[0].toIntOrNull() != null && fields.drop(1).all { it.matches(Regex("0x[0-9a-f]+")) })) {
            reader.close(); descriptor.close(); error("Fixture launch failed: $line")
        }
        pid = fields[0].toInt(); tick = fields[1]; marker = fields[2]; counter = fields[3]
    }
    fun terminate() {
        // Check the executable path before using this recorded PID, in case it has exited.
        val command = instrumentation.uiAutomation.executeShellCommand("su 0 readlink /proc/$pid/exe").use { fd -> FileInputStream(fd.fileDescriptor).use { it.readBytes().decodeToString().trim().removeSuffix(" (deleted)") } }
        if (command == executable.absolutePath) instrumentation.uiAutomation.executeShellCommand("su 0 kill -KILL $pid").use { fd -> FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
    }
    fun tracerPid(): Int {
        val status = instrumentation.uiAutomation.executeShellCommand("su 0 cat /proc/$pid/status").use { fd -> FileInputStream(fd.fileDescriptor).use { it.readBytes().decodeToString() } }
        return status.lineSequence().first { it.startsWith("TracerPid:") }.substringAfter(':').trim().toInt()
    }
    override fun close() { try { terminate() } finally { reader.close(); descriptor.close() } }
}
