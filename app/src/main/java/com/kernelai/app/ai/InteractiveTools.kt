package com.kernelai.app.ai

import com.kernelai.app.mcp.McpToolExecutor
import kotlinx.serialization.json.*

internal object InteractiveTools {
    val askUser = McpToolExecutor.ToolDefinition("ask_user", "需要用户补充目标、地址、字段布局或偏好时，提出简短问题并等待回答；不得猜测后继续执行。", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") { put("type", "string"); put("maxLength", 2000) }
            putJsonObject("options") { put("type", "array"); put("maxItems", 8); putJsonObject("items") { put("type", "string"); put("maxLength", 200) } }
            putJsonObject("_purpose") { put("type", "string"); put("description", "本次提问的目的") }
        }
        putJsonArray("required") { add("question") }
    })
}
