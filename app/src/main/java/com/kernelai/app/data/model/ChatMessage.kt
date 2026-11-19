package com.kernelai.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * UI 层聊天消息模型
 */
@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCallInfo>? = null,
    val isStreaming: Boolean = false,
    val phase: AssistantPhase? = null,
    val attachments: List<Attachment> = emptyList(),
    val reasoning: String = "",
    val failure: com.kernelai.app.ai.AiFailure? = null,
    val retry: RetryNotice? = null
)

/**
 * 消息角色枚举
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

/**
 * 工具调用信息（UI 展示用）
 */
@Serializable
data class ToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val status: ToolCallStatus = ToolCallStatus.PENDING,
    val startedAtMillis: Long? = null,
    val durationMs: Long? = null
)

/**
 * 工具调用状态
 */
enum class ToolCallStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class AssistantPhase { THINKING, RESPONDING, PREPARING_TOOL, EXECUTING_TOOL, SUMMARIZING, COMPLETE, FAILED, CANCELLED }

/**
 * 网络层序列化模型 - 发送到 API 的消息格式
 */
@Serializable
data class ApiChatMessage(
    val role: String,       // "user", "assistant", "system", "tool"
    val content: String?,
    val toolCalls: List<ApiToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val reasoningContent: String? = null,
    val reasoningField: String = "reasoning_content",
    val interrupted: Boolean = false
)

@Serializable
data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall
)

@Serializable
data class ApiFunctionCall(
    val name: String,
    val arguments: String  // JSON string
)

@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ApiChatMessage,
    val finishReason: String? = null
)

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

/**
 * 将 UI 模型转换为 API 模型
 */
fun ChatMessage.toApiMessage(): ApiChatMessage {
    return ApiChatMessage(
        role = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL -> "tool"
        },
        content = content,
        reasoningContent = reasoning.takeIf { it.isNotEmpty() },
        toolCalls = toolCalls?.map { tc ->
            ApiToolCall(
                id = tc.id,
                function = ApiFunctionCall(
                    name = tc.name,
                    arguments = tc.arguments
                )
            )
        },
        toolCallId = if (role == MessageRole.TOOL) toolCalls?.firstOrNull()?.id else null,
        name = if (role == MessageRole.TOOL) toolCalls?.firstOrNull()?.name else null
    )
}

@Serializable
data class Attachment(val id: String = UUID.randomUUID().toString(), val name: String, val mime: String,
    val size: Long, val localPath: String, val textContent: String? = null) {
    val isImage: Boolean get() = mime.startsWith("image/")
}
@Serializable
data class ContextUsage(val promptTokens: Int, val completionTokens: Int = 0, val estimated: Boolean = true,
    val cachedTokens: Int? = null, val reasoningTokens: Int? = null)

@Serializable
data class RetryNotice(val attempt: Int, val maximum: Int, val nextAt: Long, val reason: String)

@Serializable
data class UsageTotals(val input: Long = 0, val output: Long = 0, val cached: Long = 0,
    val requests: Int = 0, val cacheReported: Boolean = false, val cacheInput: Long = 0) {
    fun plus(usage: ContextUsage) = if (usage.estimated) this else copy(input = input + usage.promptTokens,
        output = output + usage.completionTokens, cached = cached + (usage.cachedTokens ?: 0),
        requests = requests + 1, cacheReported = cacheReported || usage.cachedTokens != null,
        cacheInput = cacheInput + if (usage.cachedTokens != null) usage.promptTokens else 0)
}

data class ContextBreakdown(val system: Int = 0, val tools: Int = 0, val messages: Int = 0,
    val images: Int = 0, val reservedOutput: Int = 0) {
    val input get() = (system.toLong() + tools + messages + images).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val total get() = (input.toLong() + reservedOutput).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

@Serializable
data class UserQuestion(val callId: String, val question: String, val options: List<String> = emptyList())
