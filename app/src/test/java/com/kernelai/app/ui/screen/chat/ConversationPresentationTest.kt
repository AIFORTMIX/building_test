package com.kernelai.app.ui.screen.chat

import com.kernelai.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationPresentationTest {
    private fun tool(id: String) = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "",
        toolCalls = listOf(ToolCallInfo("call_$id", "process_list", "{}", status = ToolCallStatus.COMPLETED)))

    @Test fun consecutiveBatchesFoldTogetherWithoutChangingReceiptsOrOrder() {
        val user = ChatMessage(role = MessageRole.USER, content = "分析")
        val first = tool("one"); val second = tool("two")
        val receipt = ChatMessage(role = MessageRole.TOOL, content = "真实结果")
        val answer = ChatMessage(role = MessageRole.ASSISTANT, content = "分析完成")
        val raw = listOf(user, first, receipt, second, answer)
        val entries = conversationEntries(raw)
        assertEquals(3, entries.size)
        assertEquals(listOf(first, second), entries[1].messages)
        assertEquals("one:tools", entries[1].id)
        assertEquals(receipt, raw[2])
        assertEquals(answer, entries.last().messages.single())
    }

    @Test fun userInterjectionSeparatesRuns() {
        val user = ChatMessage(role = MessageRole.USER, content = "补充条件")
        val entries = conversationEntries(listOf(tool("one"), user, tool("two")))
        assertEquals(3, entries.size)
        assertEquals(listOf(true, false, true), entries.map { it.execution })
    }

    @Test fun modelStatusAndAnswerAlwaysStayOutsideTheToolAccordion() {
        val first = tool("one")
        val waiting = ChatMessage(role = MessageRole.ASSISTANT, content = "", isStreaming = true, phase = AssistantPhase.SUMMARIZING)
        assertEquals(2, conversationEntries(listOf(first, waiting)).size)
        val answer = waiting.copy(content = "结果", phase = AssistantPhase.RESPONDING)
        val entries = conversationEntries(listOf(first, answer))
        assertEquals(2, entries.size)
        assertEquals("one:tools", entries.first().id)
        assertEquals(waiting.id + ":message", entries.last().id)
    }

    @Test fun mixedAssistantProseAndReasoningNeverDisappearInsideCollapsedTools() {
        val first = tool("mixed").copy(content = "我先核实目标进程", reasoning = "按身份核对")
        val answer = ChatMessage(role = MessageRole.ASSISTANT, content = "分析结论")
        val entries = conversationEntries(listOf(first, answer))
        assertEquals(listOf(false, true, false), entries.map { it.execution })
        assertEquals(first.content, entries[0].messages.single().content)
        assertEquals(first.reasoning, entries[0].messages.single().reasoning)
        assertNull(entries[0].messages.single().toolCalls)
        assertEquals("", entries[1].messages.single().content)
        assertEquals(answer.content, entries[2].messages.single().content)
        assertEquals(3, entries.map { it.id }.distinct().size)
    }

    @Test fun revealNeverCutsSurrogatePairsAndAlwaysMakesBoundedProgress() {
        val source = "中🙂文🚀".repeat(400)
        var end = 0
        while (end < source.length) {
            val next = nextRevealEnd(end, source)
            assertTrue(next > end && next - end <= 65)
            if (next < source.length) assertFalse(source[next - 1].isHighSurrogate() && source[next].isLowSurrogate())
            end = next
        }
        assertEquals(source.length, end)
    }

    @Test fun repeatedIdenticalImagesStillHaveAnOccurrenceLimitAcrossNestedBlocks() {
        val repeated = (1..20).joinToString("\n\n") { "![相同图像](https://example.invalid/test.png)" }
        val first = previewImages(MarkdownDocument.parse(repeated))
        assertEquals(16, first.size)
        assertFalse("/16/0" in first)
        val replacement = MarkdownDocument.parse(repeated + "\n\n新追加正文")
        assertEquals(first, previewImages(replacement))
        val nested = MarkdownDocument.parse("> ![引用图像](https://example.invalid/q.png)\n\n- ![列表图像](https://example.invalid/l.png)\n\n| 图像 |\n| --- |\n| ![表格图像](https://example.invalid/t.png) |")
        assertEquals(3, previewImages(nested).size)
    }
}
