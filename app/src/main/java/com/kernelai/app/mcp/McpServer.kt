package com.kernelai.app.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Server - Model Context Protocol 服务器
 * 内嵌于 App 进程，通过 Streamable HTTP 暴露驱动能力
 */
class McpServer(
    private val toolExecutor: McpToolExecutor
) {
    private val sessions = ConcurrentHashMap<String, McpSession>()
    
    data class McpSession(
        val id: String,
        val createdAt: Long = System.currentTimeMillis(),
        var lastActive: Long = System.currentTimeMillis()
    )
    
    // MCP 能力声明
    val capabilities = buildJsonObject {
        putJsonObject("tools") {
            put("listChanged", false)
        }
    }
    
    // 处理 JSON-RPC 消息
    suspend fun handleMessage(message: String): String? {
        val parsed = try {
            Json.parseToJsonElement(message)
        } catch (e: SerializationException) {
            return buildJsonRpcError(null, -32700, "Parse error")
        }

        val msg = parsed as? JsonObject
            ?: return buildJsonRpcError(null, -32600, "Invalid Request")
        val id = msg["id"]
        val method = msg["method"] as? JsonPrimitive
        val validId = id == null || id is JsonPrimitive &&
            (id === JsonNull || id.isString || id.longOrNull != null)
        if (msg["jsonrpc"] != JsonPrimitive("2.0") || method?.isString != true || !validId) {
            return buildJsonRpcError(null, -32600, "Invalid Request")
        }

        val rawParams = msg["params"]
        val params = rawParams as? JsonObject
        if (rawParams != null && params == null) {
            return id?.let { buildJsonRpcError(it, -32602, "Invalid params") }
        }

        if (id == null) return null

        return when (method.content) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, params)
            "ping" -> buildJsonRpcResponse(id, buildJsonObject { })
            else -> buildJsonRpcError(id, -32601, "Method not found: " + method.content)
        }
    }

    private fun handleInitialize(id: JsonElement): String {
        val result = buildJsonObject {
            put("protocolVersion", "2025-03-26")
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", "qingwei-mcp")
                put("version", "1.0.0")
            }
        }
        return buildJsonRpcResponse(id, result)
    }
    
    private fun handleToolsList(id: JsonElement?): String {
        val tools = toolExecutor.getToolDefinitions()
        val result = buildJsonObject {
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                        putJsonObject("annotations") {
                            val mutates = tool.name in DebugTools.mutating || tool.name in setOf("memory_write", "breakpoint_set", "breakpoint_remove", "workspace_write_file", "workspace_edit_file", "workspace_create_directory")
                            put("readOnlyHint", !mutates); put("destructiveHint", mutates); put("openWorldHint", false)
                        }
                    }
                }
            }
        }
        return buildJsonRpcResponse(id, result)
    }
    
    private suspend fun handleToolsCall(id: JsonElement?, params: JsonObject?): String {
        if (params == null) {
            return buildJsonRpcError(id, -32602, "Missing params")
        }
        
        val toolName = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return buildJsonRpcError(id, -32602, "Missing or invalid tool name")
        val rawArguments = params["arguments"]
        val arguments = when (rawArguments) {
            null -> JsonObject(emptyMap())
            is JsonObject -> rawArguments
            else -> return buildJsonRpcError(id, -32602, "Invalid tool arguments")
        }
        
        return try {
            val result = toolExecutor.executeTool(toolName, arguments)
            buildJsonRpcResponse(id, buildJsonObject {
                val payloadError = runCatching { Json.parseToJsonElement(result).jsonObject["is_error"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
                if (payloadError) put("isError", true)
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", result)
                    }
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            buildJsonRpcResponse(id, buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", "Error: ${e.message}")
                    }
                }
                put("isError", true)
            })
        }
    }
    
    private fun buildJsonRpcResponse(id: JsonElement?, result: JsonObject): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", result)
        }.toString()
    }
    
    private fun buildJsonRpcError(id: JsonElement?, code: Int, message: String): String {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()
    }
}

/**
 * MCP 工具执行器接口
 */
interface McpToolExecutor {
    data class ToolDefinition(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )
    
    fun getToolDefinitions(): List<ToolDefinition>
    suspend fun executeTool(name: String, arguments: JsonObject): String
}
