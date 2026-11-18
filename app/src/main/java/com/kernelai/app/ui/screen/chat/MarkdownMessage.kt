package com.kernelai.app.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import com.kernelai.app.ui.components.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TableCell

private val markdownDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Native markdown; image previews are bounded, raw HTML remains literal until explicitly previewed. */
@Composable
fun MarkdownMessage(source: String, streaming: Boolean, textColor: Color, modifier: Modifier = Modifier, onRendering: (Boolean) -> Unit = {}) {
    val presented = progressiveMarkdown(source, streaming)
    val rendered = presented.text
    val rendering = presented.active
    val callback by rememberUpdatedState(onRendering)
    LaunchedEffect(rendering) { callback(rendering) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
    var blocks by remember { mutableStateOf(MarkdownDocument.cached(rendered)
        ?: if (rendered.length > MarkdownDocument.MAX_SOURCE_CHARS) MarkdownDocument.parse(rendered) else null) }
    // A steady-rate worker keeps up with token streams without parsing once per network chunk.
    val latest by rememberUpdatedState(rendered)
    LaunchedEffect(rendering) {
        if (rendering) {
            while (true) {
                val snapshot = latest
                blocks = withContext(markdownDispatcher) { MarkdownDocument.parse(snapshot) }
                delay(80)
            }
        }
    }
    // Also supports a completed message being replaced (error text, history restoration).
    LaunchedEffect(if (rendering) null else rendered) {
        if (!rendering) blocks = withContext(markdownDispatcher) { MarkdownDocument.parse(rendered) }
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val document = blocks
            if (document == null) Text("正在加载内容…", color = textColor, fontSize = 13.sp, lineHeight = 23.sp)
            else {
                CompositionLocalProvider(LocalInlineImages provides remember(document) { previewImages(document) }) {
                    MarkdownBlocks(document, textColor, rendering)
                }
                if (!rendering) remember(document) { ArtifactLinks.from(document).filterNot { it.inlineImage } }.forEach { RemoteArtifactCard(it) }
            }
        }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, color: Color, streaming: Boolean = false) {
    blocks.forEachIndexed { index, block -> key(index, block::class) { when (block) {
        is MdBlock.Paragraph -> {
            val size = when (block.level) { 1 -> 25; 2 -> 22; 3 -> 20; 4 -> 18; 5 -> 16; else -> 15 }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val parts = remember(block.text) { paragraphParts(block.text) }
                parts.forEachIndexed { partIndex, part -> key(partIndex, part::class) {
                    when (part) {
                        is ParagraphPart.Picture -> InlinePicture(part.image)
                        is ParagraphPart.TextRun -> {
                            val text = inlineText(part.items, color)
                            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                            Text(text, color = color, fontSize = size.sp, lineHeight = (size + 8).sp,
                                modifier = streamingInk(text, streaming && index == blocks.lastIndex && partIndex == parts.lastIndex) { layout },
                                onTextLayout = { layout = it }, fontWeight = if (block.level > 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                } }
            }
        }
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Spacer(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = .35f)))
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkdownBlocks(block.blocks, color.copy(alpha = .85f), streaming && index == blocks.lastIndex)
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item -> Row(Modifier.fillMaxWidth()) {
                Text(item.marker, color = color, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.widthIn(min = 28.dp).padding(end = 6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { MarkdownBlocks(item.blocks, color, streaming && index == blocks.lastIndex && item == block.items.last()) }
            } }
        }
        is MdBlock.Code -> {
            if (block.language.isNotBlank() && (block.text.length >= 80 || block.language.lowercase() in setOf("html", "svg"))) {
                CodeArtifactCard(block)
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起代码" else "显示代码", fontSize = 12.sp) }
                if (expanded) CodeBlock(block, color)
            } else CodeBlock(block, color)
        }
        is MdBlock.Table -> MarkdownTable(block, color)
        is MdBlock.Literal -> Text(block.text, color = color, fontSize = 14.sp, lineHeight = 22.sp)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = color.copy(alpha = .25f))
    } } }
}

@Suppress("DEPRECATION")
@Composable
private fun CodeBlock(block: MdBlock.Code, color: Color) {
    val copyText = rememberChatCopy()
    Column(Modifier.fillMaxWidth().background(color.copy(alpha = .06f), RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(block.language.ifBlank { "代码" }, color = color.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            TextButton(onClick = { copyText(block.text) }) { Text("复制代码", color = color, fontSize = 11.sp) }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(block.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, softWrap = false)
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, color: Color) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        table.rows.forEach { row -> Row(Modifier.height(IntrinsicSize.Min)) {
            row.forEach { cell -> Column(Modifier.width(168.dp).fillMaxHeight()
                .background(if (cell.header) color.copy(alpha = .08f) else Color.Transparent)
                .border(.5.dp, color.copy(alpha = .2f)).padding(10.dp)) {
                paragraphParts(cell.text).forEach { part -> when (part) {
                is ParagraphPart.Picture -> InlinePicture(part.image)
                is ParagraphPart.TextRun -> Text(inlineText(part.items, color), color = color, fontSize = 14.sp, lineHeight = 21.sp,
                    fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (cell.alignment) { TableCell.Alignment.CENTER -> TextAlign.Center; TableCell.Alignment.RIGHT -> TextAlign.End; else -> TextAlign.Start },
                    modifier = Modifier.fillMaxWidth())
                } }
            } }
        } }
    }
}

@Composable
private fun inlineText(items: List<MdInline>, color: Color): AnnotatedString {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(items, color, context) { buildAnnotatedString {
        fun AnnotatedString.Builder.render(children: List<MdInline>) {
            children.forEach { inline -> when (inline) {
                is MdInline.Text -> append(inline.value)
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = color.copy(alpha = .09f))) { append(inline.value) }
                is MdInline.Styled -> withStyle(when (inline.kind) {
                    MdInline.Kind.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
                    MdInline.Kind.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
                    MdInline.Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                }) { render(inline.children) }
                is MdInline.Link -> if (inline.destination != null) {
                    withLink(LinkAnnotation.Url(inline.destination,
                        TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { runCatching { com.kernelai.app.ui.preview.ArtifactPreview.open(context, inline.destination, "网页预览") } })) { render(inline.label) }
                } else render(inline.label)
                is MdInline.Image -> {
                    append("[图片：")
                    render(inline.alt.ifEmpty { listOf(MdInline.Text("未命名")) })
                    append("]")
                    if (inline.destination != null) withLink(LinkAnnotation.Url(inline.destination,
                        TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { runCatching { com.kernelai.app.ui.preview.ArtifactPreview.open(context, inline.destination, "网页预览") } })) { append(" 打开图片") }
                }
                MdInline.Break -> append('\n')
            } }
        }
        render(items)
    } }
}
