package com.kernelai.app.data

internal object WorkspaceEdits {
    fun replace(content: String, old: String, replacement: String): String {
        require(old.isNotEmpty()) { "old_text 不可为空" }
        val at = content.indexOf(old)
        require(at >= 0 && content.indexOf(old, at + 1) < 0) { "old_text 必须在当前文件中精确出现一次，请重新读取后缩小或扩大匹配范围" }
        return content.replaceRange(at, at + old.length, replacement)
    }
}
