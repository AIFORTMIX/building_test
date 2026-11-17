package com.kernelai.app.driver

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 数据结构映射 - 与内核侧 qingwei_mcp.h 中的 struct 对应
 * 使用 ByteBuffer 进行序列化/反序列化
 */
object IoctlStructs {
    private const val PROC_HEADER_SIZE = 32
    private const val PROC_ENTRY_SIZE = 296

    data class ProcessInfo(
        val pid: Int,
        val ppid: Int,
        val tgid: Int,
        val comm: String,
        val cmdline: String,
        val state: Long,
        val threadCount: Int
    )

    data class ModuleInfo(
        val baseAddr: Long,
        val endAddr: Long,
        val size: Long,
        val flags: Int,
        val offset: Long,
        val path: String,
        val isExecutable: Boolean
    )

    data class VmaEntry(
        val start: Long,
        val end: Long,
        val flags: Int,
        val pgoff: Long,
        val name: String
    )

    data class ThreadInfo(
        val tid: Int,
        val comm: String,
        val state: Long,
        val stackPtr: Long,
        val pc: Long
    )

    data class BreakpointInfo(
        val bpIndex: Int,
        val bpAddr: Long,
        val bpType: Int,
        val bpLen: Int,
        val hitCount: Long,
        val active: Boolean
    )

    data class StackFrame(
        val pc: Long,
        val sp: Long,
        val fp: Long,
        val lr: Long,
        val symbol: String
    )

    // 构建进程列表请求
    fun buildProcListPacket(maxCount: Int, offset: Int = 0): ByteBuffer {
        val safeCount = maxCount.coerceIn(1, 512)
        val dataBytes = safeCount * PROC_ENTRY_SIZE
        val buf = ByteBuffer.allocate(PROC_HEADER_SIZE + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(safeCount)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(offset.coerceAtLeast(0))
        buf.putLong(0L)
        buf.putLong(dataBytes.toLong())
        buf.clear()
        return buf
    }

    // 解析进程信息
    fun parseProcessInfo(data: ByteArray, count: Int): List<ProcessInfo> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableListOf<ProcessInfo>()
        val availableCount = data.size / PROC_ENTRY_SIZE

        repeat(count.coerceIn(0, availableCount)) {
            val pid = buf.int
            val ppid = buf.int
            val tgid = buf.int
            val commBytes = ByteArray(16)
            buf.get(commBytes)
            val cmdlineBytes = ByteArray(256)
            buf.get(cmdlineBytes)
            val state = buf.long
            val threadCount = buf.int

            result.add(ProcessInfo(
                pid = pid,
                ppid = ppid,
                tgid = tgid,
                comm = String(commBytes).trimEnd('\u0000'),
                cmdline = String(cmdlineBytes).trimEnd('\u0000'),
                state = state,
                threadCount = threadCount
            ))
        }
        return result
    }
}
