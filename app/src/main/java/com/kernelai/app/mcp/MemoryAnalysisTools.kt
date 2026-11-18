package com.kernelai.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Read-only analysis on top of the existing RAW_READ ABI. Addresses remain lossless hex strings. */
internal class MemoryAnalysisTools(
    private val resolve: (JsonObject) -> Int,
    private val read: (Int, Long, Int) -> ByteArray
) {
    companion object {
        private val MAX_ADDRESS = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private val TAG_MASK = BigInteger("00ffffffffffffff", 16)
        fun number(value: JsonElement?, label: String): BigInteger {
            val text = (value as? JsonPrimitive)?.contentOrNull?.trim()
                ?: throw IllegalArgumentException("缺少或无效参数: $label")
            require(text.length in 1..80) { "$label 数字过长" }
            require(text.matches(Regex("[+-]?(0[xX][0-9a-fA-F]+|[0-9]+)"))) { "$label 必须是十进制或十六进制整数" }
            val negative = text.startsWith('-')
            val body = text.removePrefix("-").removePrefix("+")
            val result = if (body.startsWith("0x", true)) body.drop(2).toBigInteger(16) else body.toBigInteger(10)
            return if (negative) result.negate() else result
        }
        fun address(value: BigInteger): BigInteger {
            require(value.signum() >= 0 && value <= MAX_ADDRESS) { "地址运算溢出 64 位范围" }
            return value
        }
        fun hex(value: BigInteger) = "0x" + address(value).toString(16)
        fun hex(value: Long) = "0x" + java.lang.Long.toUnsignedString(value, 16)
        fun calculate(args: JsonObject): JsonObject {
            val base = address(number(args["base_address"], "base_address"))
            val offsets = args["offsets"]?.jsonArray ?: JsonArray(emptyList())
            require(offsets.size <= 64) { "最多 64 次偏移计算" }
            var current = base
            val steps = buildJsonArray { offsets.forEach { offset ->
                val delta = number(offset, "offsets")
                val previous = current
                current = address(current + delta)
                addJsonObject { put("from", hex(previous)); put("offset_decimal", delta.toString()); put("address", hex(current)) }
            } }
            return buildJsonObject { put("base_address", hex(base)); put("address", hex(current)); put("steps", steps); put("dereferenced", false) }
        }
    }
    private fun JsonObject.int(name: String, default: Int) = this[name]?.jsonPrimitive?.intOrNull ?: default
    private fun JsonObject.bool(name: String, default: Boolean) = this[name]?.jsonPrimitive?.booleanOrNull ?: default
    private fun JsonObject.text(name: String, default: String = "") = this[name]?.jsonPrimitive?.contentOrNull ?: default
    private fun order(args: JsonObject): ByteOrder = when (args.text("byte_order", "little")) {
        "little" -> ByteOrder.LITTLE_ENDIAN; "big" -> ByteOrder.BIG_ENDIAN
        else -> throw IllegalArgumentException("byte_order 只能为 little 或 big")
    }
    private fun pointerSize(args: JsonObject) = args.int("pointer_size", 8).also { require(it == 4 || it == 8) { "pointer_size 只能是 4 或 8" } }
    private fun bytes(pid: Int, location: BigInteger, size: Int): ByteArray {
        require(size in 1..4096) { "单次读取必须在 1..4096 字节" }
        require(location.signum() > 0) { "不能解引用空地址" }
        address(location + BigInteger.valueOf(size.toLong() - 1))
        return read(pid, location.toLong(), size).also { require(it.size <= size) { "驱动返回的字节数超过请求" } }
    }
    private fun raw(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun unsigned(data: ByteArray, order: ByteOrder) = BigInteger(1, if (order == ByteOrder.LITTLE_ENDIAN) data.reversedArray() else data)
    private fun width(type: String, pointer: Int): Int = when (type) {
        "i8", "u8", "bool", "bytes" -> 1
        "i16", "u16" -> 2
        "i32", "u32", "f32" -> 4
        "i64", "u64", "f64" -> 8
        "pointer" -> pointer
        else -> throw IllegalArgumentException("不支持的类型 $type；使用 i8/u8/i16/u16/i32/u32/i64/u64/f32/f64/bool/pointer/bytes")
    }
    private fun decode(data: ByteArray, type: String, count: Int, pointer: Int, order: ByteOrder): JsonElement {
        if (type == "bytes") return JsonPrimitive(raw(data))
        val width = width(type, pointer)
        val values = (0 until count).map { index ->
            val item = data.copyOfRange(index * width, (index + 1) * width)
            val buf = ByteBuffer.wrap(item).order(order)
            when (type) {
                "bool" -> JsonPrimitive(item[0].toInt() != 0)
                "i8" -> JsonPrimitive(item[0].toInt())
                "i16" -> JsonPrimitive(buf.short.toInt())
                "i32" -> JsonPrimitive(buf.int)
                "i64" -> JsonPrimitive(buf.long.toString())
                "pointer" -> JsonPrimitive(hex(unsigned(item, order)))
                "f32" -> buf.float.let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
                "f64" -> buf.double.let { if (it.isFinite()) JsonPrimitive(it) else JsonPrimitive(it.toString()) }
                "u64" -> JsonPrimitive(unsigned(item, order).toString())
                else -> JsonPrimitive(unsigned(item, order).toLong())
            }
        }
        return if (count == 1) values.single() else JsonArray(values)
    }
    fun typed(args: JsonObject): JsonObject {
        val pid = resolve(args); val location = address(number(args["address"], "address"))
        val type = args.text("type"); val count = args.int("count", 1)
        val pointer = pointerSize(args); val order = order(args)
        require(count in 1..256) { "count 必须在 1..256" }
        val size = width(type, pointer) * count
        val data = bytes(pid, location, size)
        return buildJsonObject {
            put("pid", pid); put("address", hex(location)); put("type", type); put("count", count)
            put("requested_size", size); put("actual_size", data.size); put("raw_hex", raw(data))
            put("complete", data.size == size); put("is_error", data.size != size)
            if (data.size == size) put("value", decode(data, type, count, pointer, order))
            else put("error", "部分读取，缺失字节未补零，不能完整解码")
        }
    }
    fun string(args: JsonObject): JsonObject {
        val pid = resolve(args); val location = address(number(args["address"], "address"))
        val size = args.int("max_bytes", 256)
        val encoding = args.text("encoding", "UTF-8").uppercase()
        require(encoding in setOf("UTF-8", "UTF-16LE", "UTF-16BE", "US-ASCII")) { "encoding 不受支持" }
        val unit = if (encoding.startsWith("UTF-16")) 2 else 1
        require(size in unit..4096 && size % unit == 0) { "max_bytes 须在 $unit..4096 且与字符宽度对齐" }
        val data = bytes(pid, location, size)
        val stop = if (args.bool("null_terminated", true)) (0..data.size - unit step unit)
            .firstOrNull { index -> (0 until unit).all { data[index + it] == 0.toByte() } } else null
        val content = data.copyOfRange(0, stop ?: data.size)
        val decoded = runCatching { charset(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString() }
        return buildJsonObject {
            put("pid", pid); put("address", hex(location)); put("encoding", encoding)
            put("requested_size", size); put("actual_size", data.size); put("terminated", stop != null); put("raw_hex", raw(content))
            put("complete", data.size == size || stop != null)
            put("limit_reached", args.bool("null_terminated", true) && stop == null)
            put("text", decoded.getOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("is_error", decoded.isFailure || (data.size != size && stop == null))
            if (decoded.isFailure) put("error", "字符解码失败或读取截断了字符，请核对编码和长度")
            else if (data.size != size && stop == null) put("error", "部分读取，未找到终止符，字符串可能被截断")
        }
    }
    fun structure(args: JsonObject): JsonObject {
        val pid = resolve(args); val location = address(number(args["address"], "address"))
        val pointer = pointerSize(args); val order = order(args)
        val fields = args["fields"]?.jsonArray ?: throw IllegalArgumentException("缺少 fields 字段布局")
        require(fields.size in 1..64) { "fields 必须有 1..64 个字段" }
        data class Field(val name: String, val offset: Int, val type: String, val count: Int, val size: Int)
        val layouts = fields.map { value ->
            val field = value.jsonObject
            val offset = number(field["offset"], "field.offset")
            require(offset.signum() >= 0 && offset <= BigInteger.valueOf(4095)) { "字段偏移超出 0..4095" }
            val type = field.text("type"); val count = field.int("count", 1)
            require(count in 1..256) { "字段 count 必须在 1..256" }
            val size = width(type, pointer) * count
            val name = field.text("name")
            require(name.isNotBlank() && name.length <= 64 && offset.toInt() + size <= 4096) { "字段名或范围无效" }
            Field(name, offset.toInt(), type, count, size)
        }
        require(layouts.map { it.name }.toSet().size == layouts.size) { "字段名不能重复" }
        require(layouts.sumOf { it.count } <= 1024) { "字段解码值总数不能超过 1024" }
        val size = layouts.maxOf { it.offset + it.size }
        val data = bytes(pid, location, size)
        return buildJsonObject {
            put("pid", pid); put("address", hex(location)); put("requested_size", size); put("actual_size", data.size)
            put("raw_hex", raw(data)); put("complete", data.size == size); put("is_error", data.size != size)
            put("layout_source", "caller_supplied"); put("snapshot_consistent", false)
            putJsonArray("fields") { layouts.forEach { field -> addJsonObject {
                put("name", field.name); put("offset", field.offset); put("address", hex(address(location + BigInteger.valueOf(field.offset.toLong()))))
                put("type", field.type); put("count", field.count)
                val available = field.offset + field.size <= data.size
                put("available", available)
                if (available) put("value", decode(data.copyOfRange(field.offset, field.offset + field.size), field.type, field.count, pointer, order))
                else { put("value", JsonNull); put("error", "字段超出实际读取长度") }
            } } }
        }
    }
    suspend fun chain(args: JsonObject): JsonObject {
        val pid = resolve(args); val base = address(number(args["base_address"], "base_address"))
        val pointer = pointerSize(args); val order = order(args)
        val requested = args.int("read_size", 0)
        require(requested in 0..4096) { "read_size 须为 0..4096" }
        val steps = args["steps"]?.jsonArray ?: throw IllegalArgumentException("缺少 steps")
        require(steps.size in 1..32) { "指针链长度须为 1..32" }
        val parsed = steps.map { it.jsonObject.let { step -> number(step["offset"], "step.offset") to step.bool("dereference", true) } }
        val trace = mutableListOf<JsonElement>()
        var current = base
        val visited = mutableSetOf<BigInteger>()
        var failure: String? = null
        var finalData: ByteArray? = null
        for ((index, step) in parsed.withIndex()) {
            currentCoroutineContext().ensureActive()
            val from = current
            try {
                val at = address(current + step.first)
                if (step.second) {
                    require(visited.add(at)) { "检测到重复解引用地址，指针链可能成环" }
                    val data = bytes(pid, at, pointer)
                    require(data.size == pointer) { "指针读取不完整: ${data.size}/$pointer" }
                    val original = unsigned(data, order)
                    current = if (args.bool("strip_top_byte", false) && pointer == 8) original.and(TAG_MASK) else original
                    trace.add(buildJsonObject { put("index", index); put("from", hex(from)); put("offset_decimal", step.first.toString())
                        put("read_address", hex(at)); put("raw_hex", raw(data)); put("raw_pointer", hex(original)); put("address", hex(current)); put("dereferenced", true) })
                    require(current.signum() != 0) { "遇到空指针" }
                } else {
                    current = at
                    trace.add(buildJsonObject { put("index", index); put("from", hex(from)); put("offset_decimal", step.first.toString()); put("address", hex(current)); put("dereferenced", false) })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = "step[$index]: ${error.message}"; break }
        }
        if (failure == null && requested > 0) try {
            finalData = bytes(pid, current, requested)
            if (finalData.size != requested) failure = "末端读取不完整: ${finalData.size}/$requested"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = "末端读取失败: ${error.message}" }
        return buildJsonObject {
            put("pid", pid); put("base_address", hex(base)); put("address", hex(current)); put("pointer_size", pointer)
            put("steps", JsonArray(trace)); put("completed_steps", trace.size); put("is_error", failure != null)
            put("complete", failure == null); put("snapshot_consistent", false)
            failure?.let { put("error", it) }
            finalData?.let { put("raw_hex", raw(it)); put("actual_size", it.size) }
        }
    }
    fun inspect(args: JsonObject): JsonObject {
        val pid = resolve(args); val location = address(number(args["address"], "address"))
        val size = args.int("size", 128)
        require(size in 1..1024) { "inspect.size 须为 1..1024" }
        val data = bytes(pid, location, size)
        return buildJsonObject {
            put("pid", pid); put("address", hex(location)); put("requested_size", size); put("actual_size", data.size)
            put("complete", data.size == size); put("is_error", data.size != size)
            put("note", "候选指针只是字节解释，不证明地址有效或对象类型；结构语义需字段布局、符号或运行时信息。")
            putJsonArray("rows") { data.asList().chunked(16).forEachIndexed { index, row -> addJsonObject {
                put("offset", index * 16); put("address", hex(address(location + BigInteger.valueOf(index * 16L))))
                put("hex", raw(row.toByteArray())); put("ascii", row.map { if ((it.toInt() and 255) in 32..126) it.toInt().toChar() else '.' }.joinToString(""))
            } } }
            putJsonArray("aligned_u64") { for (index in 0..data.size - 8 step 8) {
                val value = unsigned(data.copyOfRange(index, index + 8), ByteOrder.LITTLE_ENDIAN)
                addJsonObject { put("offset", index); put("value_decimal", value.toString()); put("value_hex", hex(value))
                    put("pointer_candidate", value >= BigInteger.valueOf(65536) && value < BigInteger.ONE.shiftLeft(56) && value.mod(BigInteger.valueOf(4)) == BigInteger.ZERO) }
            } }
        }
    }
}
