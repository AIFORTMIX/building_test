package com.kernelai.app.ui.screen.chat

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.kernelai.app.ui.components.TextButton
import com.kernelai.app.ui.preview.*
import java.util.Collections
import java.util.IdentityHashMap
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

internal val LocalInlineImages = staticCompositionLocalOf<Set<MdInline.Image>> { emptySet() }

/** Limit occurrences, not just unique URLs, so repeated image tags cannot allocate unbounded surfaces. */
internal fun previewImages(blocks: List<MdBlock>): Set<MdInline.Image> {
    val result = Collections.newSetFromMap(IdentityHashMap<MdInline.Image, Boolean>())
    fun inline(items: List<MdInline>) { items.forEach { item -> when (item) {
        is MdInline.Image -> if (result.size < 16 && item.destination != null) result.add(item)
        is MdInline.Styled -> inline(item.children)
        is MdInline.Link -> inline(item.label)
        else -> Unit
    } } }
    fun scan(values: List<MdBlock>) { values.forEach { when (it) {
        is MdBlock.Paragraph -> inline(it.text)
        is MdBlock.Quote -> scan(it.blocks)
        is MdBlock.ListBlock -> it.items.forEach { item -> scan(item.blocks) }
        is MdBlock.Table -> it.rows.forEach { row -> row.forEach { cell -> inline(cell.text) } }
        else -> Unit
    } } }
    scan(blocks); return result
}

@Composable
internal fun InlinePicture(image: MdInline.Image) {
    val context = LocalContext.current
    val url = image.destination
    val title = image.alt.filterIsInstance<MdInline.Text>().joinToString("") { it.value }.ifBlank { "图片" }.take(160)
    if (url != null && image in LocalInlineImages.current) RemoteImagePreview(url, title)
    else if (url != null) TextButton(onClick = { ArtifactPreview.open(context, url, title, PreviewKind.IMAGE) }) { Text("图片：$title · 点按预览", fontSize = 12.sp) }
    else Text("图片：$title（链接不可用）", fontSize = 12.sp)
}
