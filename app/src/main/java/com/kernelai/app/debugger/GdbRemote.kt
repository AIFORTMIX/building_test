package com.kernelai.app.debugger

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded GDB Remote transport; one reader owns packet framing, including async stop replies. */
internal class GdbRemote(private val input: InputStream, private val output: OutputStream, private val connection: Closeable) : Closeable {
    private val packets = ArrayBlockingQueue<Result<String>>(64)
    private val acknowledgements = ArrayBlockingQueue<Boolean>(8)
    private val writeLock = Any()
    private val closed = AtomicBoolean()
    @Volatile private var noAck = false
    @Volatile private var failure: Throwable? = null
    private val reader = Thread({ readPackets() }, "qingwei-lldb-reader").apply { isDaemon = true; start() }

    init { write(byteArrayOf('+'.code.toByte())) }

    private fun write(bytes: ByteArray) = synchronized(writeLock) {
        check(!closed.get()) { "调试连接已关闭" }; output.write(bytes); output.flush()
    }

    fun send(command: String) {
        check(command.length <= 32768 && command.all { it.code in 32..126 }) { "调试命令过大或编码无效" }
        val encoded = encode(command.toByteArray(Charsets.US_ASCII))
        val checksum = encoded.sumOf { it.toInt() and 255 } and 255
        acknowledgements.clear()
        try {
            write(byteArrayOf('$'.code.toByte()) + encoded + "#%02x".format(checksum).toByteArray(Charsets.US_ASCII))
            if (!noAck) {
                val accepted = acknowledgements.poll(3, TimeUnit.SECONDS) ?: throw java.io.IOException("调试命令确认超时，不能重放操作", failure)
                check(accepted) { "调试命令校验失败，不能自动重放操作" }
            }
        } catch (error: Exception) {
            // A command may already have executed. Retire the connection before another
            // command can accidentally consume its delayed acknowledgement or response.
            close(); throw error
        }
    }

    fun request(command: String, timeoutMs: Long = 5000): String {
        send(command)
        try {
            return receive(timeoutMs) ?: throw java.io.IOException("调试响应超时，执行结果未确认，不能自动重放", failure)
        } catch (error: Exception) { close(); throw error }
    }

    fun receive(timeoutMs: Long): String? {
        require(timeoutMs in 0..15000)
        failure?.let { if (packets.isEmpty()) throw java.io.IOException("调试连接已中断", it) }
        val packet = if (timeoutMs == 0L) packets.poll() else packets.poll(timeoutMs, TimeUnit.MILLISECONDS)
        return packet?.getOrThrow()
    }

    fun negotiate() {
        request("qSupported:multiprocess+;QStartNoAckMode+")
        if (request("QStartNoAckMode") == "OK") noAck = true
    }

    fun interrupt() = write(byteArrayOf(3))

    private fun readPackets() {
        try {
            while (!closed.get()) {
                when (val prefix = input.read()) {
                    -1 -> throw EOFException("LLDB 已断开")
                    '+'.code -> acknowledgements.offer(true)
                    '-'.code -> acknowledgements.offer(false)
                    '$'.code, '%'.code -> {
                        val encoded = java.io.ByteArrayOutputStream()
                        while (true) {
                            val b = input.read(); if (b < 0) throw EOFException("调试包被截断")
                            if (b == '#'.code) break
                            check(encoded.size() < MAX_PACKET_BYTES) { "调试响应超过上限" }; encoded.write(b)
                        }
                        val high = input.read(); val low = input.read()
                        val expected = hexDigit(high) * 16 + hexDigit(low)
                        val raw = encoded.toByteArray()
                        check((raw.sumOf { it.toInt() and 255 } and 255) == expected) { "调试响应校验失败" }
                        if (!noAck) write(byteArrayOf('+'.code.toByte()))
                        val decoded = decode(raw).toString(Charsets.US_ASCII)
                        // Console O packets are not command replies. Bound and discard them here;
                        // the debugger never forwards tracee console text as protocol instructions.
                        if (decoded.startsWith('O') && decoded != "OK" && decoded.drop(1).all { it in "0123456789abcdefABCDEF" }) continue
                        check(packets.offer(Result.success(decoded))) { "调试事件队列已满，停止读取以免静默丢失事件" }
                    }
                    else -> error("非法调试协议字节 $prefix")
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                failure = error
                packets.offer(Result.failure(error))
                runCatching { connection.close() }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (failure == null) failure = EOFException("调试连接已关闭")
            runCatching { connection.close() }
            reader.interrupt()
        }
    }

    companion object {
        const val MAX_PACKET_BYTES = 1024 * 1024
        private fun hexDigit(value: Int): Int = value.toChar().digitToIntOrNull(16) ?: throw java.io.IOException("无效的调试包校验值")
        internal fun encode(bytes: ByteArray): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            bytes.forEach { b -> if (b.toInt().toChar() in listOf('$', '#', '}', '*')) { result.write('}'.code); result.write(b.toInt() xor 0x20) } else result.write(b.toInt()) }
            return result.toByteArray()
        }
        internal fun decode(bytes: ByteArray): ByteArray {
            val result = java.io.ByteArrayOutputStream(); var index = 0; var previous = -1
            while (index < bytes.size) {
                var b = bytes[index++].toInt() and 255
                when (b) {
                    '}'.code -> { require(index < bytes.size) { "调试转义被截断" }; b = (bytes[index++].toInt() and 255) xor 0x20; result.write(b); previous = b }
                    '*'.code -> {
                        require(previous >= 0 && index < bytes.size) { "调试重复编码无效" }
                        val repetitions = (bytes[index++].toInt() and 255) - 29
                        require(repetitions in 3..97 && result.size() + repetitions <= MAX_PACKET_BYTES) { "调试重复编码越界" }
                        repeat(repetitions) { result.write(previous) }
                    }
                    else -> { result.write(b); previous = b }
                }
                require(result.size() <= MAX_PACKET_BYTES) { "解码后的调试响应过大" }
            }
            return result.toByteArray()
        }
    }
}
