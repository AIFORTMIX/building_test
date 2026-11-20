package com.kernelai.app

import android.app.Dialog
import android.os.Build
import android.view.WindowManager
import android.content.Context
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernelai.app.ui.components.GlassCard
import com.kernelai.app.ui.components.GlassScene
import com.kernelai.app.ui.components.LocalPanelBackdrop
import com.kernelai.app.ui.screen.chat.*
import com.kernelai.app.ui.theme.KernelAiTheme
import com.kernelai.app.ui.theme.KernelAiAppSettings
import com.kernelai.app.ui.theme.KernelAiColorMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Text

@RunWith(AndroidJUnit4::class)
class GlassCompatibilityTest {
    @get:Rule val ui = createComposeRule()

    @Test fun changingSystemAvailabilityClearsFlagsAndDisposedListenersCannotReenableBlur() {
        var context: Context? = null
        ui.setContent { val current = LocalContext.current; SideEffect { context = current } }
        ui.runOnIdle {
            val window = requireNotNull(Dialog(context!!).window)
            val callbacks = mutableListOf<(Boolean) -> Unit>()
            var subscriptions = 0
            val source = BlurAvailability { callback ->
                subscriptions++; callbacks += callback; callback(false)
                AutoCloseable { subscriptions-- }
            }
            val controller = DialogBlurController(window, source)
            controller.start(); controller.start()
            assertEquals(1, subscriptions)
            callbacks.last()(true)
            assertEquals(Build.VERSION.SDK_INT >= 31, controller.enabled)
            if (Build.VERSION.SDK_INT >= 31) {
                assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
                assertEquals(36, window.attributes.blurBehindRadius)
            }
            callbacks.last()(false)
            assertFalse(controller.enabled)
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                assertEquals(0, window.attributes.blurBehindRadius)
            }
            controller.close()
            assertEquals(0, subscriptions)
            callbacks.last()(true)
            assertFalse(controller.enabled)
            controller.start()
            assertEquals(1, subscriptions)
            callbacks.first()(true)
            assertFalse(controller.enabled)
            controller.close()
            assertEquals(0, subscriptions)
        }
    }

    @Test fun dialogAndNestedCardsNeverSampleTheParentWindow() {
        ui.setContent {
            KernelAiTheme { GlassScene {
                Column {
                    Text("主窗口内容")
                    GlassDialog(true, {}, title = "兼容性检查") {
                        check(LocalPanelBackdrop.current == null)
                        check(LocalGlassBackdrop.current == null)
                        GlassCard { Text("弹窗文字保持清晰") }
                    }
                }
            } }
        }
        ui.onNodeWithText("兼容性检查").assertIsDisplayed()
        ui.onNodeWithText("弹窗文字保持清晰").assertIsDisplayed()
        ui.onNodeWithTag("glass-dialog").assertIsDisplayed()
    }

    @Test fun largeFontDarkDialogKeepsTheLastActionReachable() {
        ui.setContent {
            KernelAiTheme(KernelAiAppSettings(colorMode = KernelAiColorMode.DARK)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    GlassScene { GlassDialog(true, {}, title = "大字体设置") {
                        repeat(20) { Text("第 ${it + 1} 项设置说明") }
                        com.kernelai.app.ui.components.TextButton({}) { Text("完成兼容性检查") }
                    } }
                }
            }
        }
        ui.onNodeWithText("完成兼容性检查").performScrollTo().assertIsDisplayed()
    }
}
