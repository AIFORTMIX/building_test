package com.kernelai.app.mcp

import kotlinx.serialization.json.*

/** Explicit layouts and bounded, read-only operations. No guessed object layouts. */
internal object MemoryToolDefinitions {
    private fun prop(type: String, description: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type); put("description", description); extra()
    }
    private val numeric = prop("string", "十进制或 0x 十六进制字符串；偏移可带负号，地址无损传输")
    private val types = listOf("i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64", "f32", "f64", "bool", "pointer", "bytes")
    private val valueType = prop("string", "字段类型；i64/u64 返回十进制字符串，pointer 返回十六进制") { put("enum", JsonArray(types.map(::JsonPrimitive))) }
    private fun integer(description: String, min: Int, max: Int) = prop("integer", description) { put("minimum", min); put("maximum", max) }
    private fun array(description: String, items: JsonObject, min: Int, max: Int) = prop("array", description) {
        put("items", items); put("minItems", min); put("maxItems", max)
    }
    private val target = mapOf("pid" to integer("目标 PID；与 pkg_name 至少提供一个", 1, Int.MAX_VALUE),
        "pkg_name" to prop("string", "完整 Android 包名；通过驱动全量查找"))
    private val format = mapOf("pointer_size" to prop("integer", "指针宽度，默认 8") { put("enum", JsonArray(listOf(JsonPrimitive(4), JsonPrimitive(8)))) },
        "byte_order" to prop("string", "字节序，默认 little") { put("enum", JsonArray(listOf(JsonPrimitive("little"), JsonPrimitive("big")))) })
    private fun schema(properties: Map<String, JsonObject>, required: List<String>, needsTarget: Boolean = false) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(properties)); put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        if (needsTarget) putJsonArray("anyOf") {
            addJsonObject { putJsonArray("required") { add("pid") } }
            addJsonObject { putJsonArray("required") { add("pkg_name") } }
        }
    }
    private fun tool(name: String, description: String, properties: Map<String, JsonObject>, required: List<String>, target: Boolean = false) =
        McpToolDefinitions.ToolDef(name, description, emptyList(), schema(properties, required, target))

    val tools = listOf(
        tool("process_status", "核实进程是否仍存活及启动/执行映像身份。返回 process_identity；所有地址读取、指针链、写入与设断点须原样传回 expected_identity。previous_identity 可检测重启或 PID 复用。变化后重新解析模块和地址，不自动复用旧地址。", target + mapOf("previous_identity" to prop("string", "此前的 process_identity，可选，用于持续核对同一进程实例")), emptyList(), true),
        tool("process_resolve", "用驱动全量快照核对完整包名，返回 running 和 PID。避免将 process_list 的空页当作未运行；优先核对微信 com.tencent.mm。", mapOf("pkg_name" to prop("string", "完整包名，最多 255 UTF-8 字节")), listOf("pkg_name")),
        tool("address_calculate", "仅计算 base_address + offsets，不访问驱动、不读取内存。检测 64 位溢出，返回每次计算轨迹。", mapOf("base_address" to numeric, "offsets" to array("顺序相加的有符号偏移", numeric, 0, 64)), listOf("base_address")),
        tool("module_address", "查找唯一模块首个文件映射起点并计算偏移。该基址不保证等于 ELF load bias；先检查映射和 file_offset。", target + mapOf("module" to prop("string", "模块文件名或准确完整路径"), "offsets" to array("相对映射起点的顺序偏移", numeric, 0, 64)), listOf("module"), true),
        tool("memory_read_typed", "按类型解码最多 4096 字节真实内存，支持数组、大小端和 32/64 位指针。部分读取明确报错，不补零。", target + format + mapOf("address" to numeric, "type" to valueType, "count" to integer("元素数，默认 1", 1, 256)), listOf("address", "type"), true),
        tool("memory_read_string", "严格解码 UTF-8/UTF-16LE/UTF-16BE/US-ASCII；返回原始字节、终止符及截断状态。", target + mapOf("address" to numeric,
            "encoding" to prop("string", "默认 UTF-8") { put("enum", JsonArray(listOf("UTF-8", "UTF-16LE", "UTF-16BE", "US-ASCII").map(::JsonPrimitive))) },
            "max_bytes" to integer("最多读取字节，默认 256；UTF-16 须为偶数", 1, 4096), "null_terminated" to prop("boolean", "是否按零终止符停止，默认 true")), listOf("address"), true),
        tool("memory_read_struct", "按调用者明确提供的字段布局解码结构，最多 64 字段、4096 字节；不自动推断 C++/Java/ART 对象语义。", target + format + mapOf("address" to numeric,
            "fields" to array("字段布局，名称唯一", schema(mapOf("name" to prop("string", "字段名"), "offset" to numeric, "type" to valueType, "count" to integer("字段数组长度，默认 1", 1, 256)), listOf("name", "offset", "type")), 1, 64)), listOf("address", "fields"), true),
        tool("pointer_chain", "逐步执行 current + offset；dereference=true 时读取该位置的指针作为下一地址，false 时只相加。返回完整轨迹，遇空指针、环、部分读取即停。目标未暂停，链非原子快照。", target + format + mapOf("base_address" to numeric,
            "steps" to array("每步明确偏移及是否解引用", schema(mapOf("offset" to numeric, "dereference" to prop("boolean", "默认 true；最后一步只取字段地址可设 false")), listOf("offset")), 1, 32),
            "read_size" to integer("链末端额外读取字节，默认 0", 0, 4096), "strip_top_byte" to prop("boolean", "显式移除 64 位指针的顶字节标签，默认 false；不自动移除 PAC")), listOf("base_address", "steps"), true),
        tool("memory_inspect", "最多 1024 字节的十六进制、ASCII、对齐 u64 和候选指针视图。候选仅为启发式字节解释，不证明地址有效或对象类型。", target + mapOf("address" to numeric, "size" to integer("读取字节，默认 128", 1, 1024)), listOf("address"), true)
    )
}
