package com.kernelai.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.kernelai.app.data.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

class AttachmentStore(private val context: Context) {
    suspend fun import(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.take(160) ?: "附件"
        val mime = resolver.getType(uri).orEmpty()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val image = mime.startsWith("image/") || name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "heic")
        if (image) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (resolver.openInputStream(uri) ?: error("无法打开图像")).use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 200_000_000L) { "图像尺寸无效或过大" }
            options.inSampleSize = 1
            while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("图像解码失败")
            val data = try { ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output); output.toByteArray() } }
                finally { bitmap.recycle() }
            require(data.size <= 4_194_304) { "处理后的图像超过 4 MiB" }
            val file = File(directory, UUID.randomUUID().toString() + ".jpg").apply { writeBytes(data) }
            Attachment(name = name, mime = "image/jpeg", size = file.length(), localPath = file.absolutePath)
        } else {
            val data = resolver.openInputStream(uri)?.use { input -> val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 524_288) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 524_289 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray() } ?: error("无法打开文件")
            require(data.size <= 524_288) { "文本附件上限 512 KiB，请拆分文件" }
            require(data.none { it == 0.toByte() }) { "该文件不是 UTF-8 文本，请转换为文本或图像后添加" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
            val file = File(directory, UUID.randomUUID().toString() + ".txt").apply { writeBytes(data) }
            Attachment(name = name, mime = "text/plain", size = data.size.toLong(), localPath = file.absolutePath, textContent = text)
        }
    }
}
