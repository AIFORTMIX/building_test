package com.kernelai.app.ui.screen.chat

import com.kernelai.app.data.model.ChatMessage
import com.kernelai.app.data.model.MessageRole

/** Presentation only: never changes persisted messages or the model's tool/result ordering. */
internal data class ConversationEntry(val messages: List<ChatMessage>, val execution: Boolean) {
    val id: String get() = messages.first().id + if (execution) ":tools" else ":message"
}

internal fun conversationEntries(messages: List<ChatMessage>): List<ConversationEntry> {
    val result = mutableListOf<ConversationEntry>()
    for (message in messages) {
        if (message.role == MessageRole.TOOL) continue
        val execution = message.role == MessageRole.ASSISTANT && !message.toolCalls.isNullOrEmpty()
        // A tool-producing assistant message may also contain real prose/reasoning.
        // Keep that prose in its own visible timeline entry, never inside a tool accordion.
        if (execution && (message.content.isNotBlank() || message.reasoning.isNotBlank() || message.failure != null || message.retry != null)) {
            result.add(ConversationEntry(listOf(message.copy(toolCalls = null)), false))
        }
        val presentation = if (execution) message.copy(content = "", reasoning = "", failure = null, retry = null) else message
        if (execution && result.lastOrNull()?.execution == true) {
            val previous = result.removeAt(result.lastIndex)
            result.add(previous.copy(messages = previous.messages + presentation))
        } else result.add(ConversationEntry(listOf(presentation), execution))
    }
    return result
}
