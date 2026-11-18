package com.kernelai.app

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

internal fun previewPng(): ByteArray {
    val image = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
    return try { image.eraseColor(android.graphics.Color.CYAN); ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
    finally { image.recycle() }
}
