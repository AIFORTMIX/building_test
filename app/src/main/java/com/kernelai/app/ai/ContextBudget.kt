package com.kernelai.app.ai

import com.kernelai.app.data.model.ApiChatMessage
import com.kernelai.app.data.model.ContextUsage
import com.kernelai.app.mcp.McpToolExecutor

/** Approximation only: providers have different tokenizers and vision pricing. */
internal object ContextBudget {
    private fun tokens(value: String): Int {
        val nonAscii = value.count { it.code > 127 }
        return ((value.length.toLong() + 2L * nonAscii + 2) / 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun breakdown(messages: List<ApiChatMessage>, tools: List<McpToolExecutor.ToolDefinition>, output: Int): com.kernelai.app.data.model.ContextBreakdown {
        fun cost(message: ApiChatMessage) = tokens(message.content.orEmpty()) + tokens(message.reasoningContent.orEmpty()) + 8 +
            message.toolCalls.orEmpty().sumOf { tokens(it.function.name) + tokens(it.function.arguments) } +
            message.attachments.filterNot { it.isImage }.sumOf { tokens(it.textContent.orEmpty()) }
        return com.kernelai.app.data.model.ContextBreakdown(messages.filter { it.role == "system" }.sumOf(::cost),
            tools.sumOf { tokens(it.description) + tokens(it.inputSchema.toString()) }, messages.filterNot { it.role == "system" }.sumOf(::cost),
            messages.sumOf { message -> message.attachments.count { it.isImage } } * 2048, output.coerceAtLeast(0))
    }
    fun estimate(messages: List<ApiChatMessage>, tools: List<McpToolExecutor.ToolDefinition> = emptyList()): ContextUsage {
        return ContextUsage(breakdown(messages, tools, 0).input)
    }
}
