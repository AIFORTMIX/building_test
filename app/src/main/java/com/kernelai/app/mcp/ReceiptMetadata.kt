package com.kernelai.app.mcp

import kotlinx.serialization.json.*

/** Read only top-level scalar flags without allocating an entire large JSON tree. */
internal fun receiptMetadata(raw: String): JsonObject {
    val wanted = setOf("is_error", "requires_rebind", "old_addresses_valid", "error_code", "operation_may_have_completed", "automatic_retry_safe", "pid", "process_identity", "may_be_truncated", "complete", "has_more", "next_offset", "limit_reached", "operation_pending", "revision", "kind", "total_count", "page_complete")
    return runCatching {
        var index = 0
        fun spaces() { while (index < raw.length && raw[index].isWhitespace()) index++ }
        fun token(): String {
            val start = index; var depth = 0; var quoted = false; var escaped = false
            while (index < raw.length) {
                val c = raw[index]
                if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
                else when (c) { '"' -> quoted = true; '{', '[' -> depth++; '}', ']' -> if (depth == 0) break else depth--; ',', ':' -> if (depth == 0) break }
                index++
            }
            return if (index - start <= 2048) raw.substring(start, index).trim() else ""
        }
        spaces(); require(raw.getOrNull(index++) == '{')
        buildJsonObject {
            while (index < raw.length) {
                spaces(); if (raw.getOrNull(index) == '}') break
                val key = Json.parseToJsonElement(token()).jsonPrimitive.content
                spaces(); require(raw.getOrNull(index++) == ':'); spaces()
                val value = token()
                if (key in wanted && value.isNotEmpty()) (Json.parseToJsonElement(value) as? JsonPrimitive)?.let { put(key, it) }
                spaces(); if (raw.getOrNull(index) == '}') break
                require(raw.getOrNull(index++) == ',')
            }
        }
    }.getOrDefault(JsonObject(emptyMap()))
}
