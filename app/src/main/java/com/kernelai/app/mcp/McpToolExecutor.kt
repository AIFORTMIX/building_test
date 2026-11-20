package com.kernelai.app.mcp

import com.kernelai.app.driver.DriverDevice
import com.kernelai.app.driver.IoctlCommands
import com.kernelai.app.driver.IoctlStructs
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MCP 工具执行器实现 - 桥接 MCP 工具调用与内核驱动 ioctl 接口
 *
 * 每个 MCP 工具对应一个或多个 ioctl 命令。执行流程：
 * 1. 解析 JSON 参数
 * 2. 构建 ioctl 请求结构体（ByteBuffer -> ByteArray）
 * 3. 通过 DeviceNode 发起 ioctl 调用
 * 4. 解析内核返回的数据并格式化为 JSON 字符串
 */
class McpToolExecutorImpl(
    private val deviceNode: DriverDevice,
    private val decodeInstructions: (ByteArray, ULong, Int) -> JsonObject = com.kernelai.app.analysis.Arm64Disassembler::decode
) : McpToolExecutor {

    private val processQueries = ProcessQueries(deviceNode)
    private val processGuard = ProcessGuard(deviceNode::processSnapshot)

    companion object {
        private const val TAG = "McpToolExecutor"

        // 单条数据结构体大小（字节）
        private const val MODULE_ENTRY_SIZE = 288    // baseAddr(8)+endAddr(8)+size(8)+flags(4)+offset(8)+path(248)+isExec(4)
        private const val VMA_ENTRY_SIZE = 48        // start(8)+end(8)+flags(4)+pgoff(8)+name(16)+pad(4)
        private const val THREAD_ENTRY_SIZE = 44     // tid(4)+comm(16)+state(8)+stackPtr(8)+pc(8) -- comm 16 bytes
        private const val BP_ENTRY_SIZE = 32         // index(4)+addr(8)+type(4)+len(4)+hitCount(8)+active(4)
        private const val STACK_FRAME_SIZE = 48      // pc(8)+sp(8)+fp(8)+lr(8)+symbol(16)

        // 路径/名称缓冲区大小
        private const val MODULE_PATH_SIZE = 248
        private const val VMA_NAME_SIZE = 16
        private const val THREAD_COMM_SIZE = 16
        private const val SYMBOL_SIZE = 16

        // 搜索模式最大长度
        private const val MAX_PATTERN_SIZE = 512

        // 内存读取最大字节数
        private const val MAX_MEM_READ_SIZE = 4096
        private const val MAX_MEM_WRITE_SIZE = 80

        // ARM64 通用寄存器数量 (X0-X30, SP, PC, PSTATE)
        private const val ARM64_REG_COUNT = 34
    }

    // =========================================================================
    // McpToolExecutor 接口实现
    // =========================================================================

    override fun getToolDefinitions(): List<McpToolExecutor.ToolDefinition> {
        return McpToolDefinitions.ALL_TOOLS.map { tool ->
            McpToolExecutor.ToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = McpToolDefinitions.toJsonSchema(tool)
            )
        }
    }

    override suspend fun executeTool(name: String, arguments: JsonObject): String {
        require(McpToolDefinitions.ALL_TOOLS.any { it.name == name }) { "未知工具: $name" }
        if (name == "address_calculate") return MemoryAnalysisTools.calculate(arguments).toString()
        ensureDeviceOpen()
        if (name == "process_status") {
            val pid = arguments["pid"]?.jsonPrimitive?.intOrNull ?: run {
                val resolution = processQueries.resolve(arguments["pkg_name"]?.jsonPrimitive?.contentOrNull ?: error("缺少 pid 或 pkg_name"))
                if (!resolution.running) return buildJsonObject { put("pid", 0); put("running", false); put("state", "not_running"); put("previous_matches", false); put("safe_to_reuse_addresses", false) }.toString()
                resolution.pid
            }
            require(pid > 0)
            return ProcessGuard.status(deviceNode.processSnapshot(pid), arguments["previous_identity"]?.jsonPrimitive?.contentOrNull).toString()
        }
        if (name == "process_resolve") {
            val resolution = processQueries.resolve(arguments["pkg_name"]?.jsonPrimitive?.contentOrNull ?: error("缺少 pkg_name"))
            return buildJsonObject {
                resolution.json().forEach { (key, value) -> put(key, value) }
                if (resolution.running) put("target_status", ProcessGuard.status(deviceNode.processSnapshot(resolution.pid)))
            }.toString()
        }
        var submitted = false
        return try {
        val before = if (name in ProcessGuard.TARGET_TOOLS) {
            val expected = arguments["expected_identity"]?.jsonPrimitive?.contentOrNull
            if (name in ProcessGuard.ADDRESS_TOOLS && expected.isNullOrBlank()) return buildJsonObject {
                put("is_error", true); put("error_code", "process_identity_required"); put("requires_rebind", true)
                put("message", "先调用 process_status 获取 process_identity，核对当前模块与地址后，通过 expected_identity 传回；不能对重启后的目标沿用旧地址")
            }.toString()
            processGuard.capture(resolvePid(arguments), expected)
        } else null
        val args = if (before == null) arguments else JsonObject(arguments + ("pid" to JsonPrimitive(before.pid)))
        val analysis = MemoryAnalysisTools({ if (before != null) before.pid else resolvePid(it) }, { pid, address, size ->
            if (before != null) processGuard.verify(before)
            readMemoryBytes(pid, address, size)
        })
        submitted = name in ProcessGuard.MUTATING_TOOLS
        val result = try { when (name) {
            "process_list"     -> handleProcessList(arguments)
            "process_info"     -> handleProcessInfo(args)
            "process_resolve"  -> processQueries.resolve(arguments["pkg_name"]?.jsonPrimitive?.contentOrNull ?: error("缺少 pkg_name")).json().toString()
            "address_calculate" -> MemoryAnalysisTools.calculate(arguments).toString()
            "pointer_chain" -> analysis.chain(args).toString()
            "memory_read_typed" -> analysis.typed(args).toString()
            "memory_read_string" -> analysis.string(args).toString()
            "memory_read_struct" -> analysis.structure(args).toString()
            "memory_inspect" -> analysis.inspect(args).toString()
            "module_address" -> handleModuleAddress(args)
            "memory_read"      -> handleMemoryRead(args)
            "memory_write"     -> handleMemoryWrite(args)
            "memory_search"    -> handleMemorySearch(args)
            "module_list"      -> handleModuleList(args)
            "memory_map"       -> handleMemoryMap(args)
            "breakpoint_set"   -> handleBreakpointSet(args)
            "breakpoint_remove"-> handleBreakpointRemove(arguments)
            "breakpoint_list"  -> handleBreakpointList(arguments)
            "register_read"    -> handleRegisterRead(arguments)
            "disassemble_at"   -> handleDisassembleAt(args)
            "callstack_capture"-> handleCallstackCapture(args)
            "thread_list"      -> handleThreadList(args)
            else -> throw IllegalArgumentException("未知工具: $name")
        } } catch (error: Exception) {
            if (before != null) processGuard.verify(before)
            throw error
        }
        if (before == null) result else {
            processGuard.verify(before)
            buildJsonObject {
                Json.parseToJsonElement(result).jsonObject.forEach { (key, value) -> put(key, value) }
                put("process_identity", before.identity); put("process_checked", true); put("snapshot_atomic", false)
            }.toString()
        }
        } catch (changed: TargetProcessChanged) { ProcessGuard.failure(changed, submitted).toString() }
    }

    // =========================================================================
    // 工具处理函数
    // =========================================================================

    /**
     * process_list - 列出设备上所有正在运行的进程
     * ioctl: CMD_LIST_PROCS
     */
    private suspend fun handleProcessList(args: JsonObject): String = processQueries.list(args)

    /**
     * process_info - 获取指定进程的详细信息（模块 + 内存映射 + 线程）
     * ioctl: CMD_LIST_MODULES + CMD_LIST_VMAS + CMD_LIST_THREADS
     */
    private fun handleProcessInfo(args: JsonObject): String {
        val pid = resolvePid(args)

        // 获取模块列表
        val modules = fetchModuleList(pid)

        // 获取 VMA 内存映射
        val vmas = fetchVmaList(pid)

        // 获取线程列表
        val threads = fetchThreadList(pid)

        return buildJsonObject {
            put("pid", pid)
            putJsonArray("modules") {
                modules.forEach { m ->
                    addJsonObject {
                        put("base_addr", hex(m.baseAddr))
                        put("end_addr", hex(m.endAddr))
                        put("size", m.size)
                        put("flags", m.flags)
                        put("offset", m.offset)
                        put("path", m.path)
                        put("is_executable", m.isExecutable)
                    }
                }
            }
            putJsonArray("memory_map") {
                vmas.forEach { v ->
                    addJsonObject {
                        put("start", hex(v.start))
                        put("end", hex(v.end))
                        put("flags", v.flags)
                        put("pgoff", v.pgoff)
                        put("name", v.name)
                    }
                }
            }
            putJsonArray("threads") {
                threads.forEach { t ->
                    addJsonObject {
                        put("tid", t.tid)
                        put("comm", t.comm)
                        put("state", t.state)
                        put("stack_ptr", hex(t.stackPtr))
                        put("pc", hex(t.pc))
                    }
                }
            }
        }.toString()
    }

    /**
     * memory_read - 读取目标进程指定地址的内存内容
     * ioctl: CMD_RAW_READ
     */
    private fun handleMemoryRead(args: JsonObject): String {
        val pid = resolvePid(args)
        val address = args.requireHex("address")
        val size = args.requireInt("size", "size")

        if (size <= 0 || size > MAX_MEM_READ_SIZE) {
            throw IllegalArgumentException(
                "size 必须在 1~$MAX_MEM_READ_SIZE 之间, 收到: $size"
            )
        }

        val packet = buildRawReadPacket(pid, address, size)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_RAW_READ, packet)
        if (ret < 0) {
            throw IOException("ioctl CMD_RAW_READ 失败, address=$address, ret=$ret")
        }

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val readPid = buf.int
        buf.int // pad
        val readAddr = buf.long
        val readSize = buf.int
        val actualRead = buf.int
        buf.long // data_ptr
        buf.long // data_size

        val dataEnd = (40 + actualRead).coerceAtMost(packet.size)
        val readData = packet.copyOfRange(40, dataEnd)

        return buildJsonObject {
            put("pid", readPid)
            put("address", hex(readAddr))
            put("requested_size", readSize)
            put("actual_size", actualRead)
            put("data", bytesToHex(readData))
        }.toString()
    }

    private fun readMemoryBytes(pid: Int, address: Long, size: Int): ByteArray {
        require(size in 1..MAX_MEM_READ_SIZE)
        val packet = buildRawReadPacket(pid, address, size)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_RAW_READ, packet)
        if (ret < 0) throw IOException("CMD_RAW_READ 失败, pid=$pid, address=${hex(address)}, ret=$ret")
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val actual = buffer.getInt(20)
        if (actual !in 0..size || buffer.getInt(0) != pid || buffer.getLong(8) != address) throw IOException("内存读取回包无效")
        return packet.copyOfRange(40, 40 + actual)
    }

    private fun handleModuleAddress(args: JsonObject): String {
        val pid = resolvePid(args)
        val name = args.getString("module")?.takeIf { it.isNotBlank() } ?: error("缺少 module 文件名或完整路径")
        val modules = fetchModuleList(pid)
        val matches = modules.filter { it.path == name || it.path.substringAfterLast('/') == name }
        require(matches.size == 1) { "模块匹配数量为 ${matches.size}；请提供准确路径。当前列表上限 256，达到上限时不能据空结果断言模块未加载。" }
        val module = matches.single()
        val base = JsonPrimitive(hex(module.baseAddr))
        val offsets = args["offsets"] ?: JsonArray(emptyList())
        val calculated = MemoryAnalysisTools.calculate(buildJsonObject { put("base_address", base); put("offsets", offsets) })
        return buildJsonObject {
            put("pid", pid); put("module", module.path); put("base_address", base)
            put("address", calculated["address"]!!); put("steps", calculated["steps"]!!)
            put("mapping_end", hex(module.endAddr)); put("file_offset", hex(module.offset))
            put("base_kind", "first_mapping_start"); put("may_be_truncated", modules.size >= 256)
            put("note", "该基址为驱动返回的首个文件映射起点，不能据此假定整个 ELF 范围或加载偏置。")
        }.toString()
    }

    /**
     * memory_write - 向目标进程指定地址写入数据
     * ioctl: CMD_WRITE_MEM (legacy)
     */
    private fun handleMemoryWrite(args: JsonObject): String {
        val pid = resolvePid(args)
        val address = args.requireHex("address")
        val dataHex = args.getString("data")
            ?: throw IllegalArgumentException("缺少必需参数: data")

        val dataBytes = hexToBytes(dataHex)
        val packet = buildWriteMemPacket(pid, address, dataBytes)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_WRITE_MEM, packet)
        val written = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(20)
        if (ret < 0) {
            val detail = if (written > 0) "，已写入 $written 字节" else ""
            throw IOException("ioctl CMD_WRITE_MEM 失败, address=$address, ret=$ret$detail")
        }

        return buildJsonObject {
            put("pid", pid)
            put("address", hex(address))
            put("written_size", dataBytes.size)
            put("success", true)
        }.toString()
    }

    /**
     * memory_search - 在目标进程地址空间中搜索字节模式
     * ioctl: CMD_MEM_SEARCH
     */
    private fun handleMemorySearch(args: JsonObject): String {
        val pid = resolvePid(args)
        val patternHex = args.getString("pattern")
            ?: throw IllegalArgumentException("缺少必需参数: pattern")
        val startAddr = args.getHex("start_addr", 0L)
        val endAddr = args.getHex("end_addr", 0L)
        val maxResults = args.getInt("max_results", 64)

        // 当前内核 ABI 执行精确字节匹配；通配符掩码尚未实现。
        if (patternHex.contains("??")) {
            throw IllegalArgumentException("当前版本暂不支持 ?? 通配符，请提供完整十六进制字节")
        }
        val patternBytes = parseSearchPattern(patternHex)

        val packet = buildMemSearchPacket(
            pid, patternBytes, startAddr, endAddr, maxResults
        )
        val ret = deviceNode.ioctl(IoctlCommands.CMD_MEM_SEARCH, packet)
        if (ret < 0) {
            throw IOException("ioctl CMD_MEM_SEARCH 失败, ret=$ret")
        }

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val searchPid = buf.int
        val patternSize = buf.int
        val actualResults = buf.int
        val totalFound = buf.int

        // 解析结果地址（每个 8 字节，紧跟在头部之后）
        val resultsOffset = 568 // sizeof(kai_mem_search_packet)
        val results = mutableListOf<Long>()
        val resultBuf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val availableResults = (packet.size - resultsOffset) / Long.SIZE_BYTES
        for (i in 0 until actualResults.coerceIn(0, availableResults)) {
            val pos = resultsOffset + i * 8
            if (pos + 8 > packet.size) break
            resultBuf.position(pos)
            results.add(resultBuf.long)
        }

        return buildJsonObject {
            put("pid", pid)
            put("pattern", patternHex)
            put("total_found", totalFound)
            putJsonArray("addresses") {
                results.forEach { addr -> add(hex(addr)) }
            }
        }.toString()
    }

    /**
     * module_list - 列出目标进程加载的所有共享库/模块
     * ioctl: CMD_LIST_MODULES
     */
    private fun handleModuleList(args: JsonObject): String {
        val pid = resolvePid(args)
        val modules = fetchModuleList(pid)

        return buildJsonObject {
            put("pid", pid)
            put("count", modules.size)
            put("limit", 256); put("may_be_truncated", modules.size >= 256)
            if (modules.size >= 256) put("continuation", "调用 process_records(pid, kind=modules) 获取完整文件映射分页；其条目是映射段，不同于本工具的每文件首映射。")
            put("mapping_scope", "first_mapping_per_file")
            putJsonArray("modules") {
                modules.forEach { m ->
                    addJsonObject {
                        put("base_addr", hex(m.baseAddr))
                        put("end_addr", hex(m.endAddr))
                        put("size", m.size)
                        put("flags", m.flags)
                        put("offset", m.offset)
                        put("path", m.path)
                        put("is_executable", m.isExecutable)
                    }
                }
            }
        }.toString()
    }

    /**
     * memory_map - 列出目标进程的完整虚拟内存映射（VMA 列表）
     * ioctl: CMD_LIST_VMAS
     */
    private fun handleMemoryMap(args: JsonObject): String {
        val pid = resolvePid(args)
        val filterExec = args.getBoolean("filter_executable", false)

        val vmas = fetchVmaList(pid)
        val filtered = if (filterExec) {
            // Linux VM_EXEC = 0x4; VM_READ = 0x1.
            vmas.filter { (it.flags and 0x4) != 0 }
        } else {
            vmas
        }

        return buildJsonObject {
            put("pid", pid)
            put("count", filtered.size)
            put("raw_count", vmas.size); put("limit", 1024); put("may_be_truncated", vmas.size >= 1024)
            put("full_paths_tool", "process_records(pid, kind=maps)")
            putJsonArray("vmas") {
                filtered.forEach { v ->
                    addJsonObject {
                        put("start", hex(v.start))
                        put("end", hex(v.end))
                        put("flags", v.flags)
                        put("flags_str", vmaFlagsToString(v.flags))
                        put("pgoff", v.pgoff)
                        put("name", v.name)
                    }
                }
            }
        }.toString()
    }

    /**
     * breakpoint_set - 在目标地址设置断点
     * ioctl: CMD_HWBP_SET（硬件断点）或 CMD_SWBP_SET（软件断点）
     */
    private fun handleBreakpointSet(args: JsonObject): String {
        val pid = resolvePid(args)
        val address = args.requireHex("address")
        val type = args.getString("type")
            ?: throw IllegalArgumentException("缺少必需参数: type")

        return when (type) {
            "hw_execute", "hw_read", "hw_write", "hw_access" -> {
                val bpTypeValue = hwBreakpointTypeToInt(type)
                val watchLen = args.getInt("length", 4)
                val packet = buildHwBpSetPacket(pid, address, bpTypeValue, watchLen)
                val ret = deviceNode.ioctl(IoctlCommands.CMD_HWBP_SET, packet)
                if (ret < 0) {
                    throw IOException("设置硬件断点失败, address=$address, ret=$ret")
                }
                val bpId = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int
                buildJsonObject {
                    put("bp_id", bpId)
                    put("type", type)
                    put("address", hex(address))
                    put("watch_length", watchLen)
                    put("success", true)
                }.toString()
            }
            "sw" -> {
                val packet = buildSwBpSetPacket(pid, address)
                val ret = deviceNode.ioctl(IoctlCommands.CMD_SWBP_SET, packet)
                if (ret < 0) {
                    throw IOException("设置软件断点失败, address=$address, ret=$ret")
                }
                val resBuf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                val bpId = resBuf.int
                buildJsonObject {
                    put("bp_id", bpId)
                    put("type", "sw")
                    put("address", hex(address))
                    put("success", true)
                }.toString()
            }
            else -> throw IllegalArgumentException(
                "无效的断点类型: $type, 可选值: hw_execute/hw_read/hw_write/hw_access/sw"
            )
        }
    }

    /**
     * breakpoint_remove - 移除指定断点
     * ioctl: CMD_HWBP_REMOVE 或 CMD_SWBP_REMOVE
     */
    private fun handleBreakpointRemove(args: JsonObject): String {
        val bpId = args.requireInt("bp_id", "bp_id")
        val type = args.getString("type")
            ?: throw IllegalArgumentException("缺少必需参数: type")

        return when (type) {
            "hw" -> {
                val packet = buildHwBpRemovePacket(bpId)
                val ret = deviceNode.ioctl(IoctlCommands.CMD_HWBP_REMOVE, packet)
                if (ret < 0) {
                    throw IOException("移除硬件断点失败, bp_id=$bpId, ret=$ret")
                }
                buildJsonObject {
                    put("bp_id", bpId)
                    put("type", "hw")
                    put("removed", true)
                }.toString()
            }
            "sw" -> {
                val packet = buildSwBpRemovePacket(bpId)
                val ret = deviceNode.ioctl(IoctlCommands.CMD_SWBP_REMOVE, packet)
                if (ret < 0) {
                    throw IOException("移除软件断点失败, bp_id=$bpId, ret=$ret")
                }
                buildJsonObject {
                    put("bp_id", bpId)
                    put("type", "sw")
                    put("removed", true)
                }.toString()
            }
            else -> throw IllegalArgumentException(
                "无效的断点类型: $type, 可选值: hw/sw"
            )
        }
    }

    /**
     * breakpoint_list - 列出所有已设置的断点及其状态
     * ioctl: CMD_HWBP_LIST
     */
    private fun handleBreakpointList(args: JsonObject): String {
        val pid = args.getInt("pid", 0).also { require(it >= 0) }

        val packet = buildHwBpListPacket(pid)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_HWBP_LIST, packet)
        if (ret < 0) {
            throw IOException("ioctl CMD_HWBP_LIST 失败, ret=$ret")
        }

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val listPid = buf.int
        val totalBps = buf.int
        val actualBps = buf.int

        // 解析断点条目（紧跟在 12 字节头部之后，每条 BP_ENTRY_SIZE 字节）
        val breakpoints = mutableListOf<JsonObject>()
        for (i in 0 until actualBps) {
            val offset = 48 + i * BP_ENTRY_SIZE
            if (offset + BP_ENTRY_SIZE > packet.size) break
            buf.position(offset)
            val bpIndex = buf.int
            val bpAddr = buf.long
            val bpType = buf.int
            val bpLen = buf.int
            val hitCount = buf.long
            val active = buf.int != 0

            breakpoints.add(buildJsonObject {
                put("bp_index", bpIndex)
                put("address", hex(bpAddr))
                put("type", bpType)
                put("type_str", hwBreakpointTypeToString(bpType))
                put("length", bpLen)
                put("hit_count", hitCount)
                put("active", active)
            })
        }

        return buildJsonObject {
            put("pid", pid)
            put("total_breakpoints", totalBps)
            put("scope", "global_hardware_breakpoints"); put("pid_filter_applied", false)
            putJsonArray("breakpoints") {
                breakpoints.forEach { add(it) }
            }
        }.toString()
    }

    /**
     * register_read - 读取目标进程在断点命中时的寄存器快照
     * ioctl: CMD_READ_REGS
     */
    private fun handleRegisterRead(args: JsonObject): String {
        val bpId = args.requireInt("bp_id", "bp_id")

        val packet = ByteArray(280) // sizeof(kai_reg_read_packet)
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(bpId)

        val ret = deviceNode.ioctl(IoctlCommands.CMD_READ_REGS, packet)
        if (ret < 0) {
            throw IOException("ioctl CMD_READ_REGS 失败, bp_id=$bpId, ret=$ret")
        }

        val resBuf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val resBpId = resBuf.int          // bp_id (回读)
        val valid = resBuf.int != 0        // 寄存器数据是否有效

        // ARM64 寄存器: X0-X30 (31个) + SP + PC + PSTATE = 34 个，每个 8 字节
        val regNames = (0..30).map { "x$it" } + listOf("sp", "pc", "pstate")
        val registers = buildJsonObject {
            for (i in 0 until ARM64_REG_COUNT) {
                val regOffset = 8 + i * 8  // 头部 8 字节之后
                if (regOffset + 8 > packet.size) break
                resBuf.position(regOffset)
                val value = resBuf.long
                put(regNames[i], hex(value))
            }
        }

        return buildJsonObject {
            put("bp_id", bpId)
            put("valid", valid)
            put("registers", registers)
        }.toString()
    }

    /**
     * disassemble_at - 读取并真实解码 ARM64 指令；保持进程身份核验。
     * ioctl: CMD_RAW_READ
     */
    private fun handleDisassembleAt(args: JsonObject): String {
        val pid = resolvePid(args)
        val address = args.requireHex("address")
        val count = args.getInt("count", 16)
        require(count in 1..256 && address.toULong() % 4uL == 0uL) { "指令数量应为 1–256，地址须四字节对齐" }

        // ARM64 每条指令 4 字节
        val readSize = count * 4
        require(address.toULong() <= ULong.MAX_VALUE - readSize.toULong()) { "指令地址范围溢出" }

        val readData = readMemoryBytes(pid, address, readSize)
        val completeBytes = readData.size / 4 * 4
        val decoded = if (completeBytes > 0) decodeInstructions(readData.copyOf(completeBytes), address.toULong(), completeBytes / 4)
            else buildJsonObject { put("instructions", JsonArray(emptyList())); put("message", "读取结果不足一条完整指令，原始字节已保留") }

        return buildJsonObject {
            put("pid", pid)
            put("address", hex(address))
            put("requested_count", count)
            put("instruction_count", completeBytes / 4)
            put("byte_count", readData.size)
            put("data", bytesToHex(readData))
            put("partial_read", readData.size != readSize)
            if (completeBytes < readData.size) put("trailing_bytes", bytesToHex(readData.copyOfRange(completeBytes, readData.size)))
            decoded.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    /**
     * callstack_capture - 捕获目标进程在断点命中时的调用栈
     * ioctl: CMD_CALLSTACK
     */
    private fun handleCallstackCapture(args: JsonObject): String {
        val pid = resolvePid(args)
        val bpId = args.requireInt("bp_id", "bp_id")
        val maxFrames = args.getInt("max_frames", 32)

        val packet = buildCallstackPacket(pid, bpId, maxFrames)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_CALLSTACK, packet)
        if (ret < 0) {
            throw IOException("ioctl CMD_CALLSTACK 失败, bp_id=$bpId, ret=$ret")
        }

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val csPid = buf.int
        val csBpId = buf.int
        val actualFrames = buf.int
        val totalFrames = buf.int

        // 解析栈帧（紧跟在 16 字节头部之后，每帧 STACK_FRAME_SIZE 字节）
        val frames = mutableListOf<JsonObject>()
        for (i in 0 until actualFrames) {
            val offset = 56 + i * STACK_FRAME_SIZE
            if (offset + STACK_FRAME_SIZE > packet.size) break
            buf.position(offset)
            val pc = buf.long
            val sp = buf.long
            val fp = buf.long
            val lr = buf.long
            val symbolBytes = ByteArray(SYMBOL_SIZE)
            buf.get(symbolBytes)
            val symbol = String(symbolBytes).trimEnd('\u0000')

            frames.add(buildJsonObject {
                put("frame", i)
                put("pc", hex(pc))
                put("sp", hex(sp))
                put("fp", hex(fp))
                put("lr", hex(lr))
                put("symbol", symbol.ifBlank { "<unknown>" })
            })
        }

        return buildJsonObject {
            put("pid", pid)
            put("bp_id", bpId)
            put("total_frames", totalFrames)
            putJsonArray("frames") {
                frames.forEach { add(it) }
            }
        }.toString()
    }

    /**
     * thread_list - 列出目标进程的所有线程
     * ioctl: CMD_LIST_THREADS
     */
    private fun handleThreadList(args: JsonObject): String {
        val pid = resolvePid(args)
        val threads = fetchThreadList(pid)

        return buildJsonObject {
            put("pid", pid)
            put("count", threads.size)
            put("limit", 256); put("may_be_truncated", threads.size >= 256)
            if (threads.size >= 256) put("continuation", "调用 process_records(pid, kind=threads)，传 next_offset 继续查询全部线程；该只读来源不提供内核 PC 或栈指针。")
            putJsonArray("threads") {
                threads.forEach { t ->
                    addJsonObject {
                        put("tid", t.tid)
                        put("comm", t.comm)
                        put("state", t.state)
                        put("stack_ptr", hex(t.stackPtr))
                        put("pc", hex(t.pc))
                    }
                }
            }
        }.toString()
    }

    // =========================================================================
    // 内部辅助：批量数据获取（供 process_info 等组合工具复用）
    // =========================================================================

    private fun fetchModuleList(pid: Int): List<IoctlStructs.ModuleInfo> {
        val packet = buildModuleListPacket(pid, 256)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_LIST_MODULES, packet)
        if (ret < 0) throw IOException("ioctl CMD_LIST_MODULES 失败, pid=$pid, ret=$ret")

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val modPid = buf.int
        val maxCount = buf.int
        val actualCount = buf.int

        if (actualCount <= 0) return emptyList()

        val modules = mutableListOf<IoctlStructs.ModuleInfo>()
        for (i in 0 until actualCount) {
            val offset = 48 + i * MODULE_ENTRY_SIZE
            if (offset + MODULE_ENTRY_SIZE > packet.size) break
            buf.position(offset)
            val baseAddr = buf.long
            val endAddr = buf.long
            val size = buf.long
            val flags = buf.int
            val pgoff = buf.long
            val pathBytes = ByteArray(MODULE_PATH_SIZE)
            buf.get(pathBytes)
            val isExec = buf.int != 0

            modules.add(IoctlStructs.ModuleInfo(
                baseAddr = baseAddr,
                endAddr = endAddr,
                size = size,
                flags = flags,
                offset = pgoff,
                path = String(pathBytes).trimEnd('\u0000'),
                isExecutable = isExec
            ))
        }
        return modules
    }

    private fun fetchVmaList(pid: Int): List<IoctlStructs.VmaEntry> {
        val packet = buildVmaListPacket(pid, 1024)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_LIST_VMAS, packet)
        if (ret < 0) throw IOException("ioctl CMD_LIST_VMAS 失败, pid=$pid, ret=$ret")

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val vmaPid = buf.int
        val maxCount = buf.int
        val actualCount = buf.int

        if (actualCount <= 0) return emptyList()

        val vmas = mutableListOf<IoctlStructs.VmaEntry>()
        for (i in 0 until actualCount) {
            val offset = 48 + i * VMA_ENTRY_SIZE
            if (offset + VMA_ENTRY_SIZE > packet.size) break
            buf.position(offset)
            val start = buf.long
            val end = buf.long
            val flags = buf.int
            val pgoff = buf.long
            val nameBytes = ByteArray(VMA_NAME_SIZE)
            buf.get(nameBytes)

            vmas.add(IoctlStructs.VmaEntry(
                start = start,
                end = end,
                flags = flags,
                pgoff = pgoff,
                name = String(nameBytes).trimEnd('\u0000')
            ))
        }
        return vmas
    }

    private fun fetchThreadList(pid: Int): List<IoctlStructs.ThreadInfo> {
        val packet = buildThreadListPacket(pid, 128)
        val ret = deviceNode.ioctl(IoctlCommands.CMD_LIST_THREADS, packet)
        if (ret < 0) throw IOException("ioctl CMD_LIST_THREADS 失败, pid=$pid, ret=$ret")

        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val threadPid = buf.int
        val maxCount = buf.int
        val actualCount = buf.int

        if (actualCount <= 0) return emptyList()

        val threads = mutableListOf<IoctlStructs.ThreadInfo>()
        for (i in 0 until actualCount) {
            val offset = 48 + i * THREAD_ENTRY_SIZE
            if (offset + THREAD_ENTRY_SIZE > packet.size) break
            buf.position(offset)
            val tid = buf.int
            val commBytes = ByteArray(THREAD_COMM_SIZE)
            buf.get(commBytes)
            val state = buf.long
            val stackPtr = buf.long
            val pc = buf.long

            threads.add(IoctlStructs.ThreadInfo(
                tid = tid,
                comm = String(commBytes).trimEnd('\u0000'),
                state = state,
                stackPtr = stackPtr,
                pc = pc
            ))
        }
        return threads
    }

    // =========================================================================
    // ioctl 数据包构建
    // =========================================================================

    /**
     * 构建进程列表请求包 (32 字节头部 + 条目缓冲区)
     * 结构: max_count(4) + actual_count(4) + total_count(4) + offset(4) + out_buf(8) + out_buf_size(8)
     */
    private fun buildProcListPacket(maxCount: Int, offset: Int): ByteArray =
        IoctlStructs.buildProcListPacket(maxCount, offset).array()

    /**
     * 构建模块列表请求包 (48 字节)
     * 结构: pid(4) + max_count(4) + actual_count(4) + pad(4) + out_buf(8) + out_buf_size(8) + reserved(16)
     */
    private fun buildModuleListPacket(pid: Int, maxCount: Int): ByteArray {
        val safeCount = maxCount.coerceIn(1, 256)
        val dataBytes = safeCount * MODULE_ENTRY_SIZE
        val buf = ByteBuffer.allocate(48 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid); buf.putInt(safeCount); buf.putInt(0); buf.putInt(0)
        buf.putLong(0L); buf.putLong(dataBytes.toLong())
        buf.putLong(0L); buf.putLong(0L)
        return buf.array()
    }

    /**
     * 构建 VMA 列表请求包 (48 字节)
     * 结构: pid(4) + max_count(4) + actual_count(4) + pad(4) + out_buf(8) + out_buf_size(8) + reserved(16)
     */
    private fun buildVmaListPacket(pid: Int, maxCount: Int): ByteArray {
        val safeCount = maxCount.coerceIn(1, 1024)
        val dataBytes = safeCount * VMA_ENTRY_SIZE
        val buf = ByteBuffer.allocate(48 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid); buf.putInt(safeCount); buf.putInt(0); buf.putInt(0)
        buf.putLong(0L); buf.putLong(dataBytes.toLong())
        buf.putLong(0L); buf.putLong(0L)
        return buf.array()
    }

    /**
     * 构建线程列表请求包 (48 字节)
     * 结构: pid(4) + max_count(4) + actual_count(4) + pad(4) + out_buf(8) + out_buf_size(8) + reserved(16)
     */
    private fun buildThreadListPacket(pid: Int, maxCount: Int): ByteArray {
        val safeCount = maxCount.coerceIn(1, 256)
        val dataBytes = safeCount * THREAD_ENTRY_SIZE
        val buf = ByteBuffer.allocate(48 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid); buf.putInt(safeCount); buf.putInt(0); buf.putInt(0)
        buf.putLong(0L); buf.putLong(dataBytes.toLong())
        buf.putLong(0L); buf.putLong(0L)
        return buf.array()
    }

    /**
     * 构建原始内存读取请求包 (40 字节)
     * 结构: pid(4) + pad(4) + address(8) + size(4) + actual_size(4) + data_ptr(8) + data_size(8)
     */
    private fun buildRawReadPacket(pid: Int, address: Long, size: Int): ByteArray {
        val safeSize = size.coerceIn(1, MAX_MEM_READ_SIZE)
        val buf = ByteBuffer.allocate(40 + safeSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid); buf.putInt(0); buf.putLong(address)
        buf.putInt(safeSize); buf.putInt(0)
        buf.putLong(0L); buf.putLong(safeSize.toLong())
        return buf.array()
    }

    /**
     * 构建内存写入请求包 (legacy CMD_WRITE_MEM, 104 字节)
     * 结构: pid(4) + pad(4) + address(8) + size(4) + actual_size(4) + data[80]
     */
    private fun buildWriteMemPacket(pid: Int, address: Long, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid)
        buf.putInt(0)           // pad
        buf.putLong(address)
        buf.putInt(data.size)
        buf.putInt(0)           // actual_size
        buf.put(data)
        return buf.array()
    }

    /**
     * 构建内存搜索请求包 (568 字节头部 + 结果缓冲区)
     * 结构: pid(4) + pattern_size(4) + actual_results(4) + total_found(4)
     *       + start_addr(8) + end_addr(8) + max_results(4) + pad(4)
     *       + pattern[512] + results_ptr(8) + results_size(8)
     */
    private fun buildMemSearchPacket(
        pid: Int,
        pattern: ByteArray,
        startAddr: Long,
        endAddr: Long,
        maxResults: Int
    ): ByteArray {
        require(pattern.isNotEmpty() && pattern.size <= MAX_PATTERN_SIZE)
        val safeResults = maxResults.coerceIn(1, 256)
        val buf = ByteBuffer.allocate(568 + safeResults * 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid); buf.putInt(pattern.size); buf.putInt(0); buf.putInt(0)
        buf.putLong(startAddr); buf.putLong(endAddr)
        buf.putInt(safeResults); buf.putInt(0)
        buf.put(pattern)
        repeat(MAX_PATTERN_SIZE - pattern.size) { buf.put(0) }
        buf.putLong(0L)
        buf.putLong((safeResults * 8).toLong())
        return buf.array()
    }

    /**
     * 构建硬件断点设置请求包 (92 字节)
     * 结构: pid(4) + bp_type(4) + bp_len(4) + pad(4) + address(8) + bp_id(4) + reserved[64]
     */
    private fun buildHwBpSetPacket(pid: Int, address: Long, bpType: Int, bpLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid)
        buf.putInt(bpType)
        buf.putInt(bpLen)
        buf.putInt(0)           // pad
        buf.putLong(address)
        buf.putInt(0)           // bp_id (内核填充)
        // 剩余 64 字节保留，已默认为 0
        return buf.array()
    }

    /**
     * 构建软件断点设置请求包 (80 字节)
     * 结构: pid(4) + pad(4) + address(8) + bp_id(4) + reserved[60]
     */
    private fun buildSwBpSetPacket(pid: Int, address: Long): ByteArray {
        val buf = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid)
        buf.putInt(0)           // pad
        buf.putLong(address)
        buf.putInt(0)           // bp_id (内核填充)
        // 剩余 60 字节保留
        return buf.array()
    }

    /**
     * 构建硬件断点移除请求包 (72 字节)
     * 结构: bp_id(4) + pid(4) + reserved[64]
     */
    private fun buildHwBpRemovePacket(bpId: Int): ByteArray {
        val buf = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(bpId)
        buf.putInt(0)           // pid (可选)
        // 剩余 64 字节保留
        return buf.array()
    }

    /**
     * 构建软件断点移除请求包 (72 字节)
     * 结构: bp_id(4) + pid(4) + reserved[64]
     */
    private fun buildSwBpRemovePacket(bpId: Int): ByteArray {
        val buf = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(bpId)
        buf.putInt(0)           // pid (可选)
        // 剩余 64 字节保留
        return buf.array()
    }

    /**
     * 构建硬件断点列表请求包 (48 字节)
     * 结构: pid(4) + total_bps(4) + actual_bps(4) + pad(4) + out_buf(8) + out_buf_size(8) + reserved(16)
     */
    private fun buildHwBpListPacket(pid: Int): ByteArray {
        val dataBytes = 16 * BP_ENTRY_SIZE
        val buf = ByteBuffer.allocate(48 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        buf.putLong(0L); buf.putLong(dataBytes.toLong())
        buf.putLong(0L); buf.putLong(0L)
        return buf.array()
    }

    /**
     * 构建调用栈捕获请求包 (56 字节)
     * 结构: pid(4) + bp_id(4) + actual_frames(4) + total_frames(4)
     *       + out_buf(8) + out_buf_size(8) + max_frames(4) + pad(4) + reserved(16)
     */
    private fun buildCallstackPacket(pid: Int, bpId: Int, maxFrames: Int): ByteArray {
        val buf = ByteBuffer.allocate(56 + maxFrames.coerceIn(1, 64) * STACK_FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(pid)
        buf.putInt(bpId)
        buf.putInt(0)           // actual_frames (内核填充)
        buf.putInt(0)           // total_frames (内核填充)
        buf.putLong(0L)         // out_buf
        buf.putLong((maxFrames.coerceIn(1, 64) * STACK_FRAME_SIZE).toLong())
        buf.putInt(maxFrames.coerceIn(1, 64))
        buf.putInt(0)           // pad
        buf.putLong(0L)         // reserved[0]
        buf.putLong(0L)         // reserved[1]
        return buf.array()
    }

    // =========================================================================
    // 通用辅助函数
    // =========================================================================

    /** 确保设备节点已打开 */
    private fun ensureDeviceOpen() {
        if (!deviceNode.isOpen) {
            if (!deviceNode.open()) {
                throw IOException(deviceNode.lastError ?: "无法打开驱动设备节点，请确认内核模块已加载")
            }
        }
    }

    /**
     * 解析 PID：优先使用 pid 参数，其次通过 pkg_name 查找
     */
    private fun resolvePid(args: JsonObject): Int {
        val pid = args.getInt("pid", -1)
        if (pid > 0) return pid

        val pkgName = args.getString("pkg_name")
        if (!pkgName.isNullOrBlank()) {
            return findPidByPackageName(pkgName)
        }

        throw IllegalArgumentException("必须提供 pid 或 pkg_name 参数")
    }

    /** Kernel-native exact package lookup covers the complete process snapshot. */
    private fun findPidByPackageName(pkgName: String): Int = processQueries.requirePid(pkgName)

    /** 硬件断点类型字符串 -> 整数值 */
    private fun hwBreakpointTypeToInt(type: String): Int = when (type) {
        "hw_execute" -> 0
        "hw_read"    -> 1
        "hw_write"   -> 2
        "hw_access"  -> 3
        else -> throw IllegalArgumentException("无效的硬件断点类型: $type")
    }

    /** 硬件断点类型整数值 -> 描述字符串 */
    private fun hwBreakpointTypeToString(type: Int): String = when (type) {
        0 -> "hw_execute"
        1 -> "hw_read"
        2 -> "hw_write"
        3 -> "hw_access"
        else -> "unknown($type)"
    }

    /** VMA flags -> 可读字符串 (rwxp/s) */
    private fun vmaFlagsToString(flags: Int): String {
        val r = if (flags and 0x1 != 0) 'r' else '-'
        val w = if (flags and 0x2 != 0) 'w' else '-'
        val x = if (flags and 0x4 != 0) 'x' else '-'
        val p = if (flags and 0x8 != 0) 's' else 'p'
        return "$r$w$x$p"
    }

    /** 解析精确搜索模式字符串（十六进制）为字节数组 */
    private fun parseSearchPattern(pattern: String): ByteArray {
        val cleaned = pattern.replace(" ", "")
        if (cleaned.length % 2 != 0) {
            throw IllegalArgumentException("搜索模式长度必须为偶数: $pattern")
        }
        require(cleaned.length in 2..(MAX_PATTERN_SIZE * 2)) { "搜索模式必须为 1 到 $MAX_PATTERN_SIZE 字节" }
        val result = ByteArray(cleaned.length / 2)
        for (i in result.indices) {
            val byteStr = cleaned.substring(i * 2, i * 2 + 2)
            if (byteStr == "??") {
                throw IllegalArgumentException("当前版本不支持 ?? 通配符")
            }
            result[i] = byteStr.toInt(16).toByte()
        }
        return result
    }

    // -------------------------------------------------------------------------
    // JSON 参数提取扩展
    // -------------------------------------------------------------------------

    private fun JsonObject.getInt(key: String, default: Int): Int {
        return this[key]?.jsonPrimitive?.intOrNull ?: default
    }

    private fun JsonObject.getString(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.getBoolean(key: String, default: Boolean): Boolean {
        return this[key]?.jsonPrimitive?.booleanOrNull ?: default
    }

    /** 获取十六进制字符串参数并解析为 Long */
    private fun JsonObject.getHex(key: String, default: Long): Long {
        val str = this.getString(key) ?: return default
        return parseHexString(str)
    }

    /** 获取必需的十六进制字符串参数 */
    private fun JsonObject.requireHex(key: String): Long {
        val str = this.getString(key)
            ?: throw IllegalArgumentException("缺少必需参数: $key")
        return parseHexString(str)
    }

    /** 获取必需的整数参数 */
    private fun JsonObject.requireInt(key: String, displayName: String): Int {
        return this.getInt(key, Int.MIN_VALUE).also {
            if (it == Int.MIN_VALUE) {
                throw IllegalArgumentException("缺少必需参数: $displayName")
            }
        }
    }

    // -------------------------------------------------------------------------
    // 数值转换工具
    // -------------------------------------------------------------------------

    /** 解析十六进制字符串为 Long（支持 0x 前缀） */
    private fun parseHexString(hex: String): Long {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        return try {
            cleaned.toLong(16)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("无效的十六进制值: $hex")
        }
    }

    /** Long -> 0x 前缀十六进制字符串 */
    private fun hex(value: Long): String {
        return "0x${value.toULong().toString(16).padStart(8, '0')}"
    }

    /** 十六进制字符串 -> 0x 前缀规范化字符串 */
    private fun hex(hexStr: String): String {
        return hex(parseHexString(hexStr))
    }

    /** 字节数组 -> 十六进制字符串 */
    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /** 十六进制字符串 -> 字节数组 */
    private fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "").removePrefix("0x").removePrefix("0X")
        if (cleaned.length % 2 != 0) {
            throw IllegalArgumentException("十六进制字符串长度必须为偶数: $hex")
        }
        require(cleaned.length in 2..(MAX_MEM_WRITE_SIZE * 2)) { "数据必须为 1 到 $MAX_MEM_WRITE_SIZE 字节" }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** 自定义 IO 异常（避免与 java.io.IOException 混淆时可替换为更具体的类型） */
    private class IOException(message: String) : Exception(message)
}
