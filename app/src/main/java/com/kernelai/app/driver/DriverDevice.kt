package com.kernelai.app.driver

/** Byte-oriented ioctl boundary, shared by the native and root IPC backends. */
interface DriverDevice {
    val isOpen: Boolean
    val lastError: String? get() = null
    fun open(): Boolean
    fun close()
    fun ioctl(command: Int, arg: ByteArray): Int
    fun processSnapshot(pid: Int): ProcessSnapshot = ProcessInspector().read(pid)
    fun processRecords(pid: Int, kind: String, offset: Int, limit: Int, identity: String?, revision: String?): String =
        ProcRecords().read(pid, kind, offset, limit, identity, revision)
}
