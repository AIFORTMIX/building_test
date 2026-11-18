package com.kernelai.app.ui.screen.chat

import com.kernelai.app.data.model.ChatMessage
import com.kernelai.app.data.model.MessageRole
import com.kernelai.app.data.model.AssistantPhase

/** Presentation only: never changes persisted messages or the model's tool/result ordering. */
internal data class ConversationEntry(val messages: List<ChatMessage>, val execution: Boolean) {
    val id: String get() = messages.first().id
}

internal fun conversationEntries(messages: List<ChatMessage>): List<ConversationEntry> {
    val result = mutableListOf<ConversationEntry>()
    for (message in messages) {
        if (message.role == MessageRole.TOOL) continue
        val waitingInRun = result.lastOrNull()?.execution == true && message.isStreaming && message.content.isBlank() &&
            message.phase in listOf(AssistantPhase.THINKING, AssistantPhase.SUMMARIZING)
        val execution = message.role == MessageRole.ASSISTANT && (!message.toolCalls.isNullOrEmpty() || waitingInRun)
        if (execution && result.lastOrNull()?.execution == true) {
            val previous = result.removeAt(result.lastIndex)
            result.add(previous.copy(messages = previous.messages + message))
        } else result.add(ConversationEntry(listOf(message), execution))
    }
    return result
}
