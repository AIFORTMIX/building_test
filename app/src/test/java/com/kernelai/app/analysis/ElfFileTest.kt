package com.kernelai.app.analysis

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class ElfFileTest {
    private val fixture = File("src/androidTest/assets/analysis-fixture.so")
    private fun modified(change: (ByteBuffer) -> Unit, block: (File) -> Unit) {
        val file = File.createTempFile("elf-test-", ".so")
        try { val data = fixture.readBytes(); change(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)); file.writeBytes(data); block(file) }
        finally { file.delete() }
    }
    private fun rejects(change: (ByteBuffer) -> Unit) = modified(change) { file ->
        try { ElfFile(file).use { it.info() }; fail("Malformed ELF accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun actualCompiledElfHasExportsImportsAndStrings() {
        ElfFile(fixture).use { elf ->
            assertEquals(183, elf.machine)
            assertEquals("ELF64", elf.info()["format"]!!.jsonPrimitive.content)
            assertTrue(elf.query("exports", 0, 100, "qingwei_fixture_sum")["entries"]!!.jsonArray.isNotEmpty())
            assertTrue(elf.query("imports", 0, 100, "getpid")["entries"]!!.jsonArray.isNotEmpty())
            assertTrue(elf.query("strings", 0, 100, "Qingwei ELF64")["entries"]!!.jsonArray.isNotEmpty())
        }
    }
    @Test fun functionOffsetsRoundTripAndDecodeOnlyRequestedBytes() {
        ElfFile(fixture).use { elf ->
            val function = elf.query("functions", 0, 100, "qingwei_fixture_sum")["entries"]!!.jsonArray.first().jsonObject
            val address = ElfFile.parseAddress(function["address"]!!.jsonPrimitive.content)
            val mapping = elf.mappings(address, "virtual_address")["mappings"]!!.jsonArray.single().jsonObject
            val offset = mapping["file_offset"]!!.jsonPrimitive.long
            assertEquals(function["address"], elf.mappings(offset.toULong(), "file_offset")["mappings"]!!.jsonArray.single().jsonObject["virtual_address"])
            var called = false
            elf.disassemble(address, "virtual_address", 2) { bytes, pc, count ->
                assertEquals(8, bytes.size); assertEquals(address, pc); assertEquals(2, count); called = true
                buildJsonObject { put("bytes_consumed", bytes.size) }
            }
            assertTrue(called)
            assertFalse(elf.mappings(ULong.MAX_VALUE, "file_offset")["mapped"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun matchingPaginationHasNoLostOrRepeatedEntries() {
        ElfFile(fixture).use { elf ->
            val all = elf.query("symbols", 0, 200, "qingwei_fixture")["entries"]!!.jsonArray
            val first = elf.query("symbols", 0, 1, "qingwei_fixture")
            assertTrue(first["has_more"]!!.jsonPrimitive.boolean)
            val rest = elf.query("symbols", first["next_offset"]!!.jsonPrimitive.int, 200, "qingwei_fixture")["entries"]!!.jsonArray
            assertEquals(all.toList(), first["entries"]!!.jsonArray.toList() + rest.toList())
        }
    }
    @Test fun noSectionTableExplicitlyReportsMissingSymbolsButRetainsLoadMappings() = modified({ h ->
        h.putLong(40, 0); h.putShort(60, 0); h.putShort(62, 0)
    }) { file -> ElfFile(file).use {
        assertFalse(it.query("exports", 0, 10, "")["symbol_tables_available"]!!.jsonPrimitive.boolean)
        assertNotNull(it.info()["warning"])
        assertTrue(it.query("segments", 0, 10, "")["entries"]!!.jsonArray.isNotEmpty())
    } }
    @Test fun rejectsOverflowingTablesAndNegativeOffsets() {
        rejects { it.putLong(40, Long.MAX_VALUE - 1) }
        rejects { it.putLong(32, -1) }
        rejects { it.putShort(58, 1) }
        rejects { it.putShort(60, 32767); it.putLong(40, it.capacity().toLong() - 64) }
    }
    @Test fun rejectsBadIdentityAndUnsupportedClass() {
        rejects { it.put(0, 0) }; rejects { it.put(4, 1) }; rejects { it.put(5, 3) }; rejects { it.putInt(20, 2) }
    }
    @Test fun outOfFileStringsAndNonExecutableDataCannotBeDecoded() {
        ElfFile(fixture).use { elf ->
            try { elf.query("strings", fixture.length().toInt() + 1, 10, ""); fail() } catch (_: IllegalArgumentException) { }
            try { elf.disassemble(0u, "file_offset", 1) { _, _, _ -> error("Data decoded") }; fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun stringPagingSupportsOffsetsBeyondOneMiB() {
        val file = File.createTempFile("elf-long-", ".so")
        try {
            val data = fixture.readBytes().copyOf(2 * 1024 * 1024)
            "end-of-large-file-string\u0000".toByteArray().copyInto(data, 1500000)
            file.writeBytes(data)
            ElfFile(file).use { elf -> assertTrue(elf.query("strings", 1400000, 10, "end-of-large-file")["entries"]!!.jsonArray.isNotEmpty()) }
        } finally { file.delete() }
    }
    @Test fun rejectsOverlappingSectionsBeforeScanningAliasedSymbolTables() = rejects { header ->
        val shoff = header.getLong(40).toInt(); val size = header.getShort(58).toInt() and 0xffff
        val count = header.getShort(60).toInt() and 0xffff
        val sections = (1 until count).map { shoff + it * size }.filter { header.getInt(it + 4) != 8 && header.getLong(it + 32) > 0 }
        header.putLong(sections[1] + 24, header.getLong(sections[0] + 24))
    }
    @Test fun millionSymbolCursorContinuesWithoutReparsingEveryEarlierName() {
        val file = File.createTempFile("elf-symbols-", ".so")
        try {
            val bytes = fixture.readBytes(); val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val shoff = header.getLong(40).toInt(); val stride = header.getShort(58).toInt() and 0xffff
            val count = header.getShort(60).toInt() and 0xffff
            val symtab = (1 until count).map { shoff + it * stride }.single { header.getInt(it + 4) == 2 }
            val offset = bytes.size.toLong() + 4096
            val symbols = 1000010L
            header.putLong(symtab + 24, offset); header.putLong(symtab + 32, symbols * 24); header.putLong(symtab + 56, 24)
            file.writeBytes(bytes)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(offset + symbols * 24) }
            ElfFile(file).use { elf ->
                val page = elf.query("symbols", 1000001, 1, "")
                assertEquals(1, page["entries"]!!.jsonArray.size)
                assertTrue(page["has_more"]!!.jsonPrimitive.boolean)
                val next = elf.query("symbols", page["next_offset"]!!.jsonPrimitive.int, 1, "")
                assertNotEquals(page["entries"], next["entries"])
            }
        } finally { file.delete() }
    }
    @Test fun mutatedHeadersFailOrProduceBoundedMetadataWithoutCrashing() {
        val random = Random(6257)
        repeat(100) {
            modified({ buffer -> repeat(4) { buffer.put(random.nextInt(16, 64), random.nextInt(256).toByte()) } }) { file ->
                try { ElfFile(file).use { elf -> assertTrue(elf.query("sections", 0, 10, "").toString().length < 100000) } }
                catch (_: IllegalArgumentException) { }
            }
        }
    }
}
