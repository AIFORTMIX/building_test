package com.kernelai.app.ui.screen.chat

import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    @Test fun headingsQuotesNestedListsAndRulesAreStructured() {
        val blocks = MarkdownDocument.parse("# 标题\n\n> 引用\n\n3. 第一项\n   - 子项\n4. 第二项\n\n---")
        assertEquals(1, (blocks[0] as MdBlock.Paragraph).level)
        assertTrue(blocks[1] is MdBlock.Quote)
        val list = blocks[2] as MdBlock.ListBlock
        assertEquals(listOf("3.", "4."), list.items.map { it.marker })
        assertTrue(list.items.first().blocks.any { it is MdBlock.ListBlock })
        assertEquals(MdBlock.Rule, blocks.last())
    }
    @Test fun emphasisCodeStrikeEscapesAndEntitiesArePreserved() {
        val text = (MarkdownDocument.parse("**粗体** *斜体* ~~删除~~ `a < b` \\*普通\\* &amp;").single() as MdBlock.Paragraph).text
        assertEquals(setOf(MdInline.Kind.STRONG, MdInline.Kind.EMPHASIS, MdInline.Kind.STRIKE), text.filterIsInstance<MdInline.Styled>().map { it.kind }.toSet())
        assertEquals("a < b", text.filterIsInstance<MdInline.Code>().single().value)
        assertTrue(text.filterIsInstance<MdInline.Text>().joinToString("") { it.value }.contains("*普通* &"))
    }
    @Test fun fencedAndUnclosedStreamingCodeRemainCode() {
        val code = MarkdownDocument.parse("```rust\nlet x = 1;\n").single() as MdBlock.Code
        assertEquals("rust", code.language)
        assertEquals("let x = 1;", code.text)
        assertTrue(MarkdownDocument.parse("    indented()") .single() is MdBlock.Code)
    }
    @Test fun gfmTableHasHeadersAndAlignment() {
        val table = MarkdownDocument.parse("| 名称 | 数值 |\n| :--- | ---: |\n| 测试 | **42** |").single() as MdBlock.Table
        assertEquals(2, table.rows.size)
        assertTrue(table.rows.first().all { it.header })
        assertEquals(org.commonmark.ext.gfm.tables.TableCell.Alignment.RIGHT, table.rows[1][1].alignment)
    }
    @Test fun taskListsAreReadOnlyCheckboxes() {
        val list = MarkdownDocument.parse("- [x] 完成\n- [ ] 等待").single() as MdBlock.ListBlock
        assertEquals(listOf("☑", "☐"), list.items.map { it.marker })
    }
    @Test fun linksAutolinksReferencesAndImagesUseSafeDestinations() {
        val blocks = MarkdownDocument.parse("[引用][ref] https://example.org ![替代文字](https://example.org/p.png)\n\n[ref]: https://example.com")
        val inline = (blocks.single() as MdBlock.Paragraph).text
        assertEquals(2, inline.filterIsInstance<MdInline.Link>().size)
        assertEquals("https://example.org/p.png", inline.filterIsInstance<MdInline.Image>().single().destination)
    }
    @Test fun unsafeProtocolsCredentialsAndControlsAreBlocked() {
        listOf("javascript:alert(1)", "data:text/html,boom", "file:///sdcard/a", "content://a/b", "intent://x", "//example.com", "https://user:pass@example.com", "https://example.com\nboom", "https://example.com\\@bad.com", "https://example.com/\u0000").forEach {
            assertNull(it, safeMarkdownUrl(it))
        }
        assertEquals("https://example.com/x?a=1#b", safeMarkdownUrl("https://example.com/x?a=1#b"))
        val link = (MarkdownDocument.parse("[危险](javascript:alert)").single() as MdBlock.Paragraph).text.single() as MdInline.Link
        assertNull(link.destination)
    }
    @Test fun rawHtmlIsLiteralText() {
        assertEquals("<script>alert('x')</script>", (MarkdownDocument.parse("<script>alert('x')</script>").single() as MdBlock.Literal).text)
        val paragraph = MarkdownDocument.parse("文字 <img src=x onerror=alert(1)> 继续").single() as MdBlock.Paragraph
        assertTrue(paragraph.text.filterIsInstance<MdInline.Text>().any { it.value.startsWith("<img") })
    }
    @Test fun malformedStreamingMarkdownDoesNotThrow() {
        listOf("**正在生成", "[链接](https://", "| a | b |\n|---", "![图片]", "", "中文🙂\r\n下一行").forEach { MarkdownDocument.parse(it) }
    }
    @Test fun deepOrOversizedDocumentsFallBackWithBoundedPreview() {
        assertTrue(MarkdownDocument.parse("> ".repeat(80) + "过深").single() is MdBlock.Literal)
        val preview = MarkdownDocument.parse("a".repeat(MarkdownDocument.MAX_SOURCE_CHARS + 1)).single() as MdBlock.Literal
        assertTrue(preview.text.contains("可复制完整消息"))
        assertTrue(preview.text.length < MarkdownDocument.MAX_SOURCE_CHARS + 100)
    }
    @Test fun completedDocumentCacheReusesParsedLayout() {
        val first = MarkdownDocument.parse("## 稳定的历史消息")
        assertSame(first, MarkdownDocument.parse("## 稳定的历史消息"))
    }
}
