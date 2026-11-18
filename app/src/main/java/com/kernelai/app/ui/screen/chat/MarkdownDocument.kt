package com.kernelai.app.ui.screen.chat

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import java.net.URI

/** Presentation data only. HTML, images and links never execute or fetch during parsing. */
sealed interface MdInline {
    data class Text(val value: String) : MdInline
    data class Styled(val kind: Kind, val children: List<MdInline>) : MdInline
    data class Code(val value: String) : MdInline
    data class Link(val label: List<MdInline>, val destination: String?) : MdInline
    data class Image(val alt: List<MdInline>, val destination: String?) : MdInline
    data object Break : MdInline
    enum class Kind { EMPHASIS, STRONG, STRIKE }
}
sealed interface MdBlock {
    data class Paragraph(val text: List<MdInline>, val level: Int = 0) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val items: List<Item>) : MdBlock
    data class Item(val marker: String, val blocks: List<MdBlock>)
    data class Code(val text: String, val language: String) : MdBlock
    data class Table(val rows: List<List<Cell>>) : MdBlock
    data class Cell(val text: List<MdInline>, val header: Boolean, val alignment: TableCell.Alignment?)
    data class Literal(val text: String) : MdBlock
    data object Rule : MdBlock
}

object MarkdownDocument {
    const val MAX_SOURCE_CHARS = 128_000
    private const val MAX_DEPTH = 32
    private const val MAX_NODES = 8_000
    private const val CACHE_CHARS = 512_000
    private val parser = Parser.builder().extensions(listOf(
        TablesExtension.create(), StrikethroughExtension.create(),
        AutolinkExtension.create(), TaskListItemsExtension.create()
    )).build()
    private val cache = LinkedHashMap<String, List<MdBlock>>(16, .75f, true)
    private var cachedChars = 0

    /** Called on a worker dispatcher; the weighted LRU avoids reparsing completed messages on switch. */
    fun cached(source: String): List<MdBlock>? = synchronized(cache) { cache[source] }

    fun parse(source: String): List<MdBlock> {
        cached(source)?.let { return it }
        if (source.length > MAX_SOURCE_CHARS) return listOf(MdBlock.Literal(
            source.take(MAX_SOURCE_CHARS) + "\n\n[内容较长，仅预览前 $MAX_SOURCE_CHARS 字；可复制完整消息]"))
        val result = try {
            val root = synchronized(parser) { parser.parse(source) }
            // Walk iteratively before recursive conversion/composition, bounding adversarial nesting.
            val pending = ArrayDeque<Pair<Node, Int>>()
            pending.add(root to 0)
            var count = 0
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                require(++count <= MAX_NODES && depth <= MAX_DEPTH)
                node.children().forEach { pending.add(it to depth + 1) }
                if (node is TableRow) require(node.children().size <= 24)
                if (node is TableBody) require(node.children().size <= 200)
            }
            root.children().filterNot { it is LinkReferenceDefinition }.map(::block)
        } catch (_: IllegalArgumentException) {
            listOf(MdBlock.Literal(source))
        }
        synchronized(cache) {
            cache[source]?.let { return it }
            cache[source] = result
            cachedChars += source.length
            while (cache.size > 48 || cachedChars > CACHE_CHARS) {
                val first = cache.entries.iterator().next()
                cachedChars -= first.key.length
                cache.remove(first.key)
            }
        }
        return result
    }
    private fun block(node: Node): MdBlock = when (node) {
        is Paragraph -> MdBlock.Paragraph(inlines(node))
        is Heading -> MdBlock.Paragraph(inlines(node), node.level)
        is BlockQuote -> MdBlock.Quote(node.children().map(::block))
        is BulletList, is OrderedList -> {
            var number = (node as? OrderedList)?.markerStartNumber ?: 1
            MdBlock.ListBlock(node.children().map { item ->
                val task = item.children().asSequence().flatMap { sequenceOf(it) + it.children().asSequence() }
                    .filterIsInstance<TaskListItemMarker>().firstOrNull()
                val marker = if (task != null) { if (task.isChecked) "☑" else "☐" }
                    else if (node is OrderedList) "${number++}." else "•"
                MdBlock.Item(marker, item.children().filterNot { it is TaskListItemMarker }.map(::block))
            })
        }
        is FencedCodeBlock -> MdBlock.Code(node.literal.removeSuffix("\n"), node.info.substringBefore(' ').take(40))
        is IndentedCodeBlock -> MdBlock.Code(node.literal.removeSuffix("\n"), "")
        is ThematicBreak -> MdBlock.Rule
        is HtmlBlock -> MdBlock.Literal(node.literal.trimEnd())
        is TableBlock -> MdBlock.Table(node.children().flatMap { it.children() }.map { row ->
            row.children().filterIsInstance<TableCell>().map { MdBlock.Cell(inlines(it), it.isHeader, it.alignment) }
        })
        else -> MdBlock.Paragraph(inlines(node))
    }
    private fun inlines(node: Node): List<MdInline> = node.children().flatMap { child -> when (child) {
        is Text -> listOf(MdInline.Text(child.literal))
        is Code -> listOf(MdInline.Code(child.literal))
        is Emphasis -> listOf(MdInline.Styled(MdInline.Kind.EMPHASIS, inlines(child)))
        is StrongEmphasis -> listOf(MdInline.Styled(MdInline.Kind.STRONG, inlines(child)))
        is Strikethrough -> listOf(MdInline.Styled(MdInline.Kind.STRIKE, inlines(child)))
        is Link -> listOf(MdInline.Link(inlines(child), safeMarkdownUrl(child.destination)))
        is Image -> listOf(MdInline.Image(inlines(child), safeMarkdownUrl(child.destination)))
        is SoftLineBreak -> listOf(MdInline.Text("\n"))
        is HardLineBreak -> listOf(MdInline.Break)
        is HtmlInline -> listOf(MdInline.Text(child.literal))
        is TaskListItemMarker -> emptyList()
        else -> inlines(child)
    } }
    private fun Node.children(): List<Node> = buildList {
        var child = firstChild
        while (child != null) { add(child); child = child.next }
    }
}

/** Only ordinary browser URLs; no file/content/intent/data/javascript or embedded credentials. */
fun safeMarkdownUrl(value: String): String? {
    if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    return runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme?.lowercase() in setOf("https", "http") && !it.host.isNullOrBlank() && it.rawUserInfo == null
    }?.toASCIIString()
}
