package com.kernelai.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面统一使用 Miuix；旧设置值自动回退
 */
enum class UiMode(val value: String) {
    Miuix("miuix");

    companion object {
        fun fromValue(value: String): UiMode = Miuix

        val DEFAULT_VALUE = Miuix.value
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }
