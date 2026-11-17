package com.kernelai.app.ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/** Native text only: no WebView, Javascript, HTML execution or automatic remote-image requests. */
@Composable
fun MarkdownMessage(source: String, streaming: Boolean, textColor: Color, modifier: Modifier = Modifier) {
    var blocks by remember { mutableStateOf<List<MdBlock>?>(null) }
    // A steady-rate worker keeps up with token streams without parsing once per network chunk.
    val latest by rememberUpdatedState(source)
    LaunchedEffect(streaming) {
        if (streaming) {
            while (true) {
                val snapshot = latest
                blocks = withContext(markdownDispatcher) { MarkdownDocument.parse(snapshot) }
                delay(80)
            }
        }
    }
    // Also supports a completed message being replaced (error text, history restoration).
    LaunchedEffect(if (streaming) null else source) {
        if (!streaming) blocks = withContext(markdownDispatcher) { MarkdownDocument.parse(source) }
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val document = blocks
            if (document == null) Text(source, color = textColor, fontSize = 15.sp, lineHeight = 23.sp)
            else MarkdownBlocks(document, textColor)
        }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, color: Color) {
    blocks.forEach { block -> when (block) {
        is MdBlock.Paragraph -> {
            val size = when (block.level) { 1 -> 25; 2 -> 22; 3 -> 20; 4 -> 18; 5 -> 16; else -> 15 }
            Text(inlineText(block.text, color), color = color, fontSize = size.sp,
                lineHeight = (size + 8).sp,
                fontWeight = if (block.level > 0) FontWeight.Bold else FontWeight.Normal)
        }
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Spacer(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = .35f)))
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkdownBlocks(block.blocks, color.copy(alpha = .85f))
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item -> Row(Modifier.fillMaxWidth()) {
                Text(item.marker, color = color, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.widthIn(min = 28.dp).padding(end = 6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { MarkdownBlocks(item.blocks, color) }
            } }
        }
        is MdBlock.Code -> CodeBlock(block, color)
        is MdBlock.Table -> MarkdownTable(block, color)
        is MdBlock.Literal -> Text(block.text, color = color, fontSize = 14.sp, lineHeight = 22.sp)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = color.copy(alpha = .25f))
    } }
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
            row.forEach { cell -> Box(Modifier.width(168.dp).fillMaxHeight()
                .background(if (cell.header) color.copy(alpha = .08f) else Color.Transparent)
                .border(.5.dp, color.copy(alpha = .2f)).padding(10.dp)) {
                Text(inlineText(cell.text, color), color = color, fontSize = 14.sp, lineHeight = 21.sp,
                    fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = when (cell.alignment) { TableCell.Alignment.CENTER -> TextAlign.Center; TableCell.Alignment.RIGHT -> TextAlign.End; else -> TextAlign.Start },
                    modifier = Modifier.fillMaxWidth())
            } }
        } }
    }
}

@Composable
private fun inlineText(items: List<MdInline>, color: Color): AnnotatedString {
    val uriHandler = LocalUriHandler.current
    return remember(items, color, uriHandler) { buildAnnotatedString {
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
                        linkInteractionListener = { runCatching { uriHandler.openUri(inline.destination) } })) { render(inline.label) }
                } else render(inline.label)
                is MdInline.Image -> {
                    append("[图片：")
                    render(inline.alt.ifEmpty { listOf(MdInline.Text("未命名")) })
                    append("]")
                    if (inline.destination != null) withLink(LinkAnnotation.Url(inline.destination,
                        TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { runCatching { uriHandler.openUri(inline.destination) } })) { append(" 打开图片") }
                }
                MdInline.Break -> append('\n')
            } }
        }
        render(items)
    } }
}
