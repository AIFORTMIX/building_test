package com.kernelai.app.debugger

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GdbRemoteTest {
    private fun packet(value: String): ByteArray {
        val raw = GdbRemote.encode(value.toByteArray())
        return byteArrayOf('$'.code.toByte()) + raw + "#%02x".format(raw.sumOf { it.toInt() and 255 } and 255).toByteArray()
    }
    @Test fun escapingAndRunLengthPreserveBytesAndRejectUnboundedExpansion() {
        val value = "hello\$#}*".toByteArray()
        assertArrayEquals(value, GdbRemote.decode(GdbRemote.encode(value)))
        assertEquals("000000", GdbRemote.decode("0*\"".toByteArray()).decodeToString())
        for (bad in listOf("}", "*\"", "0*\u0001")) {
            try { GdbRemote.decode(bad.toByteArray()); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun checksummedReplyAndAsyncStopAreDeliveredInOrder() {
        ServerSocket(0).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            val remote = pool.submit {
                listener.accept().use { server ->
                    val input = server.getInputStream(); val output = server.getOutputStream()
                    assertEquals('+'.code, input.read())
                    assertEquals('$'.code, input.read())
                    while (input.read() != '#'.code) { }
                    input.read(); input.read()
                    output.write('+'.code)
                    output.write(packet("OK")); output.flush()
                    assertEquals('+'.code, input.read())
                    output.write(packet("T05thread:123;reason:breakpoint;")); output.flush()
                    assertEquals('+'.code, input.read())
                }
            }
            try {
                val socket = Socket("127.0.0.1", listener.localPort)
                GdbRemote(socket.getInputStream(), socket.getOutputStream(), socket).use { protocol ->
                    assertEquals("OK", protocol.request("QSetDetachOnError:1"))
                    assertEquals("T05thread:123;reason:breakpoint;", protocol.receive(1000))
                }
                remote.get(5, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun corruptChecksumFailsInsteadOfReturningInventedRegisters() {
        ServerSocket(0).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            val remote = pool.submit { listener.accept().use { server ->
                val input = server.getInputStream(); input.read()
                while (input.read() != '#'.code) { }; input.read(); input.read()
                server.getOutputStream().apply { write('+'.code); write("\$OK#00".toByteArray()); flush() }
            } }
            try {
                val socket = Socket("127.0.0.1", listener.localPort)
                GdbRemote(socket.getInputStream(), socket.getOutputStream(), socket).use { protocol ->
                    try { protocol.request("g"); fail() } catch (_: Exception) { }
                }
                remote.get(5, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun timedOutCommandClosesConnectionBeforeAnotherOperationCanReadItsLateReply() {
        ServerSocket(0).use { listener ->
            val pool = Executors.newSingleThreadExecutor()
            val remote = pool.submit {
                listener.accept().use { server ->
                    server.soTimeout = 5000
                    val input = server.getInputStream()
                    assertEquals('+'.code, input.read())
                    assertEquals('$'.code, input.read())
                    while (input.read() != '#'.code) { }
                    input.read(); input.read()
                    server.getOutputStream().apply { write('+'.code); flush() }
                    // No reply: the client must disconnect, without sending a new command.
                    assertEquals(-1, input.read())
                }
            }
            try {
                val socket = Socket("127.0.0.1", listener.localPort)
                GdbRemote(socket.getInputStream(), socket.getOutputStream(), socket).use { protocol ->
                    try { protocol.request("Z0,1234,4", 30); fail("Expected timeout") } catch (_: java.io.IOException) { }
                    try { protocol.request("g"); fail("Connection must not be reused") } catch (_: IllegalStateException) { }
                    try { protocol.receive(0); fail("Closed state must reach session polling") } catch (_: java.io.IOException) { }
                }
                remote.get(5, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
        }
    }
}
