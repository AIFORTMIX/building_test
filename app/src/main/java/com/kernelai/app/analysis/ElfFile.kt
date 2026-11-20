package com.kernelai.app.analysis

import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded ELF64 metadata reader. Every file range is validated before allocation/read. */
internal class ElfFile(private val file: File, private val checkCancelled: () -> Unit = {}) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    val length = input.length()
    private var order = ByteOrder.LITTLE_ENDIAN
    val machine: Int
    val entry: ULong
    val type: Int
    private val sections: List<Section>
    private val segments: List<Segment>
    private val names: Section?

    private data class Section(val nameIndex: Long, val type: Long, val flags: ULong, val address: ULong,
        val offset: Long, val size: Long, val link: Long, val info: Long, val entrySize: Long)
    private data class Segment(val type: Long, val flags: Long, val offset: Long, val address: ULong,
        val fileSize: Long, val memorySize: Long)

    init {
        try {
            require(length in 64..MAX_FILE_BYTES) { "ELF 文件应为 64 字节至 64 MiB" }
            val ident = bytes(0, 16)
            require(ident.take(4) == listOf(0x7f.toByte(), 69.toByte(), 76.toByte(), 70.toByte())) { "不是 ELF 文件" }
            require(ident[4].toInt() == 2 && ident[6].toInt() == 1) { "当前支持 ELF64 version 1" }
            order = when (ident[5].toInt()) { 1 -> ByteOrder.LITTLE_ENDIAN; 2 -> ByteOrder.BIG_ENDIAN; else -> invalid("ELF 字节序无效") }
            val h = buffer(0, 64)
            type = u16(h, 16); machine = u16(h, 18); entry = h.getLong(24).toULong()
            require(h.getInt(20) == 1 && u16(h, 52) >= 64) { "ELF 头无效" }
            val phoff = h.getLong(32); val shoff = h.getLong(40)
            val phsize = u16(h, 54); val shsize = u16(h, 58)
            var phcount = u16(h, 56); var shcount = u16(h, 60); var strindex = u16(h, 62)
            val sectionZero = if (shoff != 0L && (shcount == 0 || phcount == 0xffff || strindex == 0xffff)) {
                require(shsize >= 64); section(shoff)
            } else null
            if (shoff != 0L && shcount == 0) shcount = sectionZero!!.size.checkedInt(32768)
            if (phcount == 0xffff) phcount = sectionZero?.info?.checkedInt(8192) ?: invalid("缺少扩展 ELF 段数量")
            if (strindex == 0xffff) strindex = sectionZero?.link?.checkedInt(32768) ?: invalid("缺少扩展 ELF 字符串表索引")
            require(shcount in 0..32768 && phcount in 0..8192) { "ELF 表数量超出解析上限" }
            if (shcount > 0) { require(shoff >= 64 && shsize >= 64); range(shoff, shsize.toLong() * shcount) }
            if (phcount > 0) { require(phoff >= 64 && phsize >= 56); range(phoff, phsize.toLong() * phcount) }
            sections = List(shcount) { section(shoff + it.toLong() * shsize).also { s ->
                if (s.type != 8L && s.type != 0L) range(s.offset, s.size)
                require(s.address <= ULong.MAX_VALUE - s.size.toULong()) { "节虚拟地址溢出" }
            } }
            segments = List(phcount) { index ->
                val p = buffer(phoff + index.toLong() * phsize, 56)
                Segment(u32(p, 0), u32(p, 4), p.getLong(8), p.getLong(16).toULong(), p.getLong(32), p.getLong(40)).also {
                    range(it.offset, it.fileSize)
                    require(it.memorySize >= 0 && it.address <= ULong.MAX_VALUE - it.memorySize.toULong()) { "段虚拟地址溢出" }
                    if (it.type == 1L) require(it.fileSize <= it.memorySize) { "PT_LOAD 文件大小超过内存大小" }
                }
            }
            names = if (strindex == 0) null else sections.getOrNull(strindex)?.also { require(it.type == 3L) { "节名称表不是字符串表" } }
                ?: if (strindex != 0) invalid("节名称表索引越界") else null
            sections.filter { it.type == 2L || it.type == 11L }.forEach {
                require(it.entrySize >= 24 && it.size % it.entrySize == 0L && it.size / it.entrySize <= 1000000) { "符号表条目无效或过大" }
                require(sections.getOrNull(it.link.toInt())?.type == 3L) { "符号字符串表索引无效" }
            }
        } catch (error: Throwable) { input.close(); throw error }
    }

    private fun Long.checkedInt(maximum: Int): Int { require(this in 0..maximum.toLong()); return toInt() }
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
    private fun u16(b: ByteBuffer, offset: Int) = b.getShort(offset).toInt() and 0xffff
    private fun u32(b: ByteBuffer, offset: Int) = b.getInt(offset).toLong() and 0xffffffffL
    private fun range(offset: Long, size: Long) { require(offset >= 0 && size >= 0 && offset <= length && size <= length - offset) { "ELF 文件范围越界或被截断" } }
    private fun bytes(offset: Long, size: Int): ByteArray {
        checkCancelled()
        require(size in 0..65536); range(offset, size.toLong())
        return ByteArray(size).also { input.seek(offset); input.readFully(it) }
    }
    private fun buffer(offset: Long, size: Int) = ByteBuffer.wrap(bytes(offset, size)).order(order)
    private fun section(offset: Long): Section {
        val s = buffer(offset, 64)
        return Section(u32(s, 0), u32(s, 4), s.getLong(8).toULong(), s.getLong(16).toULong(), s.getLong(24),
            s.getLong(32).also { require(it >= 0) }, u32(s, 40), u32(s, 44), s.getLong(56))
    }
    private fun string(table: Section?, offset: Long): String {
        if (table == null) return ""
        require(offset in 0 until table.size) { "ELF 字符串索引越界" }
        val raw = bytes(table.offset + offset, minOf(4096L, table.size - offset).toInt())
        val end = raw.indexOf(0)
        require(end >= 0) { "ELF 名称未终止或超过 4095 字节" }
        return String(raw, 0, end, Charsets.UTF_8)
    }
    private fun name(s: Section) = string(names, s.nameIndex)
    fun info(): JsonObject = buildJsonObject {
        put("format", "ELF64"); put("machine", machine); put("architecture", when (machine) { 183 -> "arm64"; 62 -> "x86_64"; else -> "machine_$machine" })
        put("type", type); put("endian", if (order == ByteOrder.LITTLE_ENDIAN) "little" else "big")
        put("entry", hex(entry)); put("file_bytes", length); put("section_count", sections.size); put("segment_count", segments.size)
        put("symbol_tables_available", sections.any { it.type == 2L || it.type == 11L })
        put("arm64_disassembly_available", machine == 183 && order == ByteOrder.LITTLE_ENDIAN)
        put("analysis_scope", "ELF metadata, symbol-derived functions and explicit instruction decoding; no decompiler or inferred complete call graph")
        if (sections.isEmpty()) put("warning", "没有节表；符号/导入导出查询不可用，不能据此判断文件没有符号")
    }

    fun query(kind: String, offset: Int, limit: Int, query: String): JsonObject {
        require(offset in 0..(if (kind == "strings") MAX_FILE_BYTES.toInt() else 1000000) && limit in 1..200 && query.length <= 256)
        if (kind == "strings") return strings(offset, limit, query)
        val rows: Sequence<JsonObject> = when (kind) {
            "sections" -> sections.asSequence().mapIndexed { i, s -> buildJsonObject {
                put("index", i); put("name", name(s)); put("type", s.type); put("address", hex(s.address)); put("file_offset", s.offset)
                put("size", s.size); put("flags", hex(s.flags)); put("file_backed", s.type != 8L && s.type != 0L)
            } }
            "segments" -> segments.asSequence().mapIndexed { i, p -> buildJsonObject {
                put("index", i); put("type", p.type); put("flags", p.flags); put("address", hex(p.address)); put("file_offset", p.offset)
                put("file_size", p.fileSize); put("memory_size", p.memorySize)
            } }
            "symbols", "imports", "exports", "functions" -> symbols(kind)
            else -> invalid("kind 应为 sections/segments/symbols/imports/exports/functions/strings")
        }
        val found = rows.filter { query.isEmpty() || it.toString().contains(query, ignoreCase = true) }.drop(offset).take(limit + 1).toList()
        return buildJsonObject {
            put("kind", kind); put("entries", JsonArray(found.take(limit))); put("offset", offset); put("has_more", found.size > limit)
            if (found.size > limit) put("next_offset", offset + limit)
            put("symbol_tables_available", sections.any { it.type == 2L || it.type == 11L })
            put("pagination_unit", "matching_entries")
        }
    }

    private fun symbols(kind: String): Sequence<JsonObject> = sequence {
        sections.filter { it.type == 2L || it.type == 11L }.forEach { table ->
            val strings = sections[table.link.toInt()]
            for (index in 0 until table.size / table.entrySize) {
                val s = buffer(table.offset + index * table.entrySize, 24)
                val info = s.get(4).toInt() and 255; val binding = info ushr 4; val symbolType = info and 15
                val section = u16(s, 6); val visibility = s.get(5).toInt() and 3
                val undefined = section == 0; val global = binding == 1 || binding == 2
                if (kind == "imports" && (!undefined || !global || table.type != 11L)) continue
                if (kind == "exports" && (undefined || !global || visibility !in listOf(0, 3) || table.type != 11L)) continue
                if (kind == "functions" && (undefined || symbolType != 2)) continue
                yield(buildJsonObject {
                    put("index", index); put("table", name(table)); put("name", string(strings, u32(s, 0))); put("type", symbolType)
                    put("binding", binding); put("visibility", visibility); put("undefined", undefined); put("section_index", section)
                    put("address", hex(s.getLong(8).toULong())); put("size", s.getLong(16).toULong().toString())
                })
            }
        }
    }

    fun mappings(value: ULong, from: String): JsonObject {
        require(from == "virtual_address" || from == "file_offset")
        val rows = segments.filter { it.type == 1L }.mapNotNull { p ->
            if (from == "virtual_address") {
                if (value < p.address || value - p.address >= p.memorySize.toULong()) null else buildJsonObject {
                    val delta = value - p.address
                    put("virtual_address", hex(value)); put("zero_fill", delta >= p.fileSize.toULong())
                    if (delta < p.fileSize.toULong()) put("file_offset", p.offset + delta.toLong()) else put("file_offset", JsonNull)
                }
            } else {
                if (value < p.offset.toULong() || value - p.offset.toULong() >= p.fileSize.toULong()) null else buildJsonObject {
                    put("file_offset", value.toLong()); put("virtual_address", hex(p.address + (value - p.offset.toULong()))); put("zero_fill", false)
                }
            }
        }
        return buildJsonObject { put("mappings", JsonArray(rows)); put("mapped", rows.isNotEmpty()); put("ambiguous", rows.size > 1); put("address_space", "ELF link-time address; live addresses require the current module load bias") }
    }

    fun disassemble(value: ULong, from: String, count: Int, decoder: (ByteArray, ULong, Int) -> JsonObject): JsonObject {
        require(machine == 183 && order == ByteOrder.LITTLE_ENDIAN) { "当前反汇编器支持小端 ARM64 ELF" }
        require(count in 1..256)
        val mapping = mappings(value, from)["mappings"]!!.jsonArray
        require(mapping.size == 1) { "地址无唯一 PT_LOAD 文件映射；BSS 和段间空洞不可反汇编" }
        val m = mapping.single().jsonObject
        val offset = m["file_offset"]?.jsonPrimitive?.longOrNull ?: invalid("零填充区域没有文件指令字节")
        val address = parseAddress(m["virtual_address"]!!.jsonPrimitive.content)
        val segment = segments.single { it.type == 1L && address >= it.address && address - it.address < it.memorySize.toULong() }
        require(segment.flags and 1L != 0L) { "此映射段没有执行权限，不将数据误当指令" }
        val available = minOf(count * 4L, segment.fileSize - (offset - segment.offset)).toInt() / 4 * 4
        require(available >= 4) { "段尾不足一条完整指令" }
        return buildJsonObject {
            decoder(bytes(offset, available), address, count).forEach { (key, entry) -> put(key, entry) }
            put("file_offset", offset); put("requested_count", count); put("limited_by_segment", available < count * 4)
            put("next_file_offset", offset + available)
        }
    }

    private fun strings(offset: Int, limit: Int, query: String): JsonObject {
        require(offset.toLong() <= length)
        val entries = mutableListOf<JsonObject>(); var cursor = offset.toLong(); var start = cursor
        val text = StringBuilder(); var size = 0L
        file.inputStream().use { stream ->
            stream.channel.position(cursor)
            val buffer = ByteArray(65536)
            loop@ while (true) {
                checkCancelled()
                val n = stream.read(buffer)
                if (n < 0) break
                for (i in 0 until n) {
                    val c = buffer[i].toInt() and 255
                    if (c in 32..126) {
                        if (size == 0L) start = cursor
                        if (text.length < 512) text.append(c.toChar())
                        size++
                    } else {
                        if (size >= 4 && (query.isEmpty() || text.contains(query, ignoreCase = true))) entries += buildJsonObject {
                            put("file_offset", start); put("text", text.toString()); put("byte_length", size); put("text_truncated", size > text.length)
                        }
                        text.setLength(0); size = 0
                    }
                    cursor++
                    if (entries.size == limit) break@loop
                }
            }
        }
        if (cursor == length && size >= 4 && entries.size < limit && (query.isEmpty() || text.contains(query, ignoreCase = true))) entries += buildJsonObject {
            put("file_offset", start); put("text", text.toString()); put("byte_length", size); put("text_truncated", size > text.length)
        }
        return buildJsonObject {
            put("kind", "strings"); put("encoding", "ASCII printable, minimum length 4"); put("entries", JsonArray(entries)); put("offset", offset)
            put("has_more", cursor < length); if (cursor < length) put("next_offset", cursor)
            put("pagination_unit", "file_bytes"); put("scanned_bytes", cursor - offset)
            put("query_scope", "query matches the displayed prefix of at most 512 characters")
        }
    }

    override fun close() = input.close()
    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        fun hex(value: ULong) = "0x${value.toString(16)}"
        fun parseAddress(value: String): ULong = if (value.startsWith("0x", true)) value.substring(2).toULong(16) else value.toULong()
    }
}
