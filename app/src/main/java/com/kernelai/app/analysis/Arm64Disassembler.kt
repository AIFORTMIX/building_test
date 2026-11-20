package com.kernelai.app.analysis

import kotlinx.serialization.json.*

/** Target architecture is ARM64 regardless of the Android host ABI. */
internal object Arm64Disassembler {
    private val loaded = runCatching { System.loadLibrary("qingwei_mcp_jni") }.isSuccess
    fun decode(bytes: ByteArray, address: ULong, count: Int): JsonObject {
        require(count in 1..256 && bytes.size in 4..1024 && bytes.size % 4 == 0 && address % 4uL == 0uL)
        require(address <= ULong.MAX_VALUE - bytes.size.toULong()) { "指令地址溢出" }
        check(loaded) { "当前 ABI 无法加载 ARM64 解码器" }
        return Json.parseToJsonElement(nativeDecode(bytes, address.toLong(), count)).jsonObject
    }
    private external fun nativeDecode(bytes: ByteArray, address: Long, count: Int): String
}
