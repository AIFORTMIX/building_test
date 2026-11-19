package com.kernelai.app.ui.screen.chat

/** Some providers wrap their entire answer in ```markdown. Render that answer as prose. */
internal fun markdownPresentation(blocks: List<MdBlock>, depth: Int = 0): List<MdBlock> = blocks.flatMap { block -> when (block) {
    is MdBlock.Code -> if (block.language.lowercase() in setOf("md", "markdown") && depth < 4)
        markdownPresentation(MarkdownDocument.parse(block.text), depth + 1) else listOf(block)
    is MdBlock.Quote -> listOf(block.copy(blocks = markdownPresentation(block.blocks, depth)))
    is MdBlock.ListBlock -> listOf(block.copy(items = block.items.map { it.copy(blocks = markdownPresentation(it.blocks, depth)) }))
    else -> listOf(block)
} }
