package com.kernelai.app.ui.screen.chat

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.preview.*
import top.yukonga.miuix.kmp.basic.Text

internal sealed interface ParagraphPart {
    data class TextRun(val items: List<MdInline>) : ParagraphPart
    data class Picture(val image: MdInline.Image) : ParagraphPart
}

internal fun paragraphParts(items: List<MdInline>): List<ParagraphPart> {
    val result = mutableListOf<ParagraphPart>(); val text = mutableListOf<MdInline>()
    fun flush() { if (text.isNotEmpty()) { result.add(ParagraphPart.TextRun(text.toList())); text.clear() } }
    items.forEach { item -> when (item) {
        is MdInline.Image -> { flush(); result.add(ParagraphPart.Picture(item)) }
        is MdInline.Styled, is MdInline.Link -> {
            val children = if (item is MdInline.Styled) item.children else (item as MdInline.Link).label
            paragraphParts(children).forEach { part -> when (part) {
                is ParagraphPart.Picture -> { flush(); result.add(part) }
                is ParagraphPart.TextRun -> text.add(if (item is MdInline.Styled) item.copy(children = part.items) else (item as MdInline.Link).copy(label = part.items))
            } }
        }
        else -> text.add(item)
    } }
    flush(); return result
}

internal val LocalInlineImages = staticCompositionLocalOf<Set<String>> { emptySet() }

/** Limit occurrences, not just unique URLs, so repeated image tags cannot allocate unbounded surfaces. */
internal fun previewImages(blocks: List<MdBlock>): Set<String> {
    val result = linkedSetOf<String>()
    fun inline(items: List<MdInline>, path: String) { paragraphParts(items).forEachIndexed { index, part ->
        if (part is ParagraphPart.Picture && part.image.destination != null && result.size < 16) result.add("$path/$index")
    } }
    fun scan(values: List<MdBlock>, path: String) { values.forEachIndexed { index, block ->
        val key = "$path/$index"
        when (block) {
            is MdBlock.Paragraph -> inline(block.text, key)
            is MdBlock.Quote -> scan(block.blocks, "$key/q")
            is MdBlock.ListBlock -> block.items.forEachIndexed { itemIndex, item -> scan(item.blocks, "$key/l$itemIndex") }
            is MdBlock.Table -> block.rows.forEachIndexed { rowIndex, row -> row.forEachIndexed { cellIndex, cell -> inline(cell.text, "$key/t$rowIndex:$cellIndex") } }
            else -> Unit
        }
    } }
    scan(blocks, ""); return result
}

@Composable
internal fun InlinePicture(image: MdInline.Image, occurrence: String) {
    val context = LocalContext.current
    val url = image.destination
    val title = image.alt.filterIsInstance<MdInline.Text>().joinToString("") { it.value }.ifBlank { "图片" }.take(160)
    if (url != null && occurrence in LocalInlineImages.current) RemoteImagePreview(url, title)
    else if (url != null) TextButton(onClick = { ArtifactPreview.open(context, url, title, PreviewKind.IMAGE) }) { Text("图片：$title · 点按预览", fontSize = 12.sp) }
    else Text("图片：$title（链接不可用）", fontSize = 12.sp)
}
