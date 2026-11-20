package com.kernelai.app.ui.screen.chat

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.function.Consumer

/** A subscription owns its listener, so disposal also rejects already queued callbacks. */
internal fun interface BlurAvailability {
    fun subscribe(changed: (Boolean) -> Unit): AutoCloseable
}

internal fun systemBlurAvailability(context: Context): BlurAvailability {
    val lowRam = context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
    return if (Build.VERSION.SDK_INT >= 31 && !lowRam) platformBlurAvailability(context)
    else BlurAvailability { changed -> changed(false); AutoCloseable {} }
}

@RequiresApi(31)
private fun platformBlurAvailability(context: Context) = BlurAvailability { changed ->
    val manager = context.getSystemService(WindowManager::class.java)
    if (manager == null) {
        changed(false)
        AutoCloseable {}
    } else {
        val listener = Consumer<Boolean> { changed(it) }
        // Registration reports the initial state as well as later power/GPU policy changes.
        try {
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
            AutoCloseable { manager.removeCrossWindowBlurEnabledListener(listener) }
        } catch (_: RuntimeException) {
            runCatching { manager.removeCrossWindowBlurEnabledListener(listener) }
            changed(false)
            AutoCloseable {}
        }
    }
}

/** Used only on the UI thread, for one Dialog window. Never mutates the Activity window. */
internal class DialogBlurController(private val window: Window, private val availability: BlurAvailability) : AutoCloseable {
    var enabled by mutableStateOf(false)
        private set
    private var active = false
    private var generation = 0L
    private var subscription: AutoCloseable? = null

    fun start() {
        if (active) return
        active = true
        val current = ++generation
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.18f)
        apply(false)
        subscription = availability.subscribe { if (active && generation == current) apply(it) }
    }

    private fun apply(available: Boolean) {
        enabled = available && Build.VERSION.SDK_INT >= 31
        if (Build.VERSION.SDK_INT >= 31) {
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = if (enabled) 36 else 0 }
        }
    }

    override fun close() {
        active = false
        generation++
        subscription?.let { runCatching { it.close() } }
        subscription = null
        apply(false)
    }
}
