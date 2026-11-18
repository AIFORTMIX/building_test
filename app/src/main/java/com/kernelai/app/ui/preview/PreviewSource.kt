package com.kernelai.app.ui.preview

import java.io.File
import java.net.URI

internal enum class PreviewKind { IMAGE, VIDEO, AUDIO, WEB, TEXT, MARKDOWN, LOCAL_HTML }

internal object PreviewSource {
    val images = setOf("image", "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif", "heic")
    val videos = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp")
    val audio = setOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "amr")
    val text = setOf("txt", "json", "csv", "xml", "yaml", "yml", "log", "c", "cpp", "h", "kt", "java", "py", "js", "ts", "css", "sh", "toml", "ini")
    fun http(value: String): String {
        val url = URI(value)
        require(url.scheme?.lowercase() in setOf("http", "https") && !url.host.isNullOrBlank() && url.rawUserInfo == null) { "仅支持无嵌入凭据的 HTTP/HTTPS 链接" }
        return value
    }
    fun kind(value: String, extension: String = ""): PreviewKind {
        val ext = extension.lowercase().ifBlank { runCatching { URI(value).path.substringAfterLast('.', "").lowercase() }.getOrDefault("") }
        return when (ext) { in images -> PreviewKind.IMAGE; in videos -> PreviewKind.VIDEO; in audio -> PreviewKind.AUDIO;
            "md", "markdown" -> PreviewKind.MARKDOWN; in text -> PreviewKind.TEXT; else -> PreviewKind.WEB }
    }
    fun local(root: File, value: String): File {
        val file = File(value).canonicalFile
        require(listOf("artifacts", "attachments").any { file.toPath().startsWith(File(root, it).canonicalFile.toPath()) } && file.isFile) { "文件不在本应用的预览目录中" }
        return file
    }
}
