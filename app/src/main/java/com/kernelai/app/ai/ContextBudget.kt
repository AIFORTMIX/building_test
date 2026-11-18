package com.kernelai.app.ai

import com.kernelai.app.data.model.ApiChatMessage
import com.kernelai.app.data.model.ContextUsage
import com.kernelai.app.mcp.McpToolExecutor

/** Approximation only: providers have different tokenizers and vision pricing. */
internal object ContextBudget {
    fun estimate(messages: List<ApiChatMessage>, tools: List<McpToolExecutor.ToolDefinition> = emptyList()): ContextUsage {
        var chars = tools.sumOf { it.description.length.toLong() + it.inputSchema.toString().length }
        var images = 0
        messages.forEach { message ->
            chars += (message.content?.length ?: 0) + 24L
            message.toolCalls.orEmpty().forEach { chars += it.function.arguments.length + it.function.name.length }
            message.attachments.forEach { if (it.isImage) images++ else chars += it.textContent?.length ?: 0 }
        }
        // One token per non-ASCII char, one per ~3 ASCII chars; intentionally conservative.
        val nonAscii = messages.sumOf { it.content.orEmpty().count { c -> c.code > 127 }.toLong() }
        return ContextUsage(((chars + 2 * nonAscii + 2) / 3 + images * 2048L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
