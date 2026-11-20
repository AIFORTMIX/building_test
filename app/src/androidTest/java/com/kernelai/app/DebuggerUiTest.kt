package com.kernelai.app

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.ui.navigation.*
import com.kernelai.app.ui.screen.debugger.DebuggerScreen
import com.kernelai.app.ui.theme.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DebuggerUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun userCanAttachReadRealRegistersAndDetachThroughTheGlassUi() {
        DebugRootHost().use { host -> DebugFixture().use { fixture ->
            ui.setContent { CompositionLocalProvider(LocalAppNavigator provides AppNavigator(Route.Main)) {
                KernelAiTheme(KernelAiAppSettings(colorMode = KernelAiColorMode.DARK)) { DebuggerScreen(host.tools) }
            } }
            ui.onNodeWithTag("debug-pid").performTextInput(fixture.pid.toString())
            ui.onNodeWithText("附加并暂停").performScrollTo().performClick()
            ui.waitUntil(15000) { runCatching { ui.onNodeWithTag("debug-state").assertTextEquals("已暂停全部线程"); true }.getOrDefault(false) }
            ui.onNodeWithText("寄存器").performScrollTo().performClick()
            ui.waitUntil(15000) { runCatching { ui.onNodeWithTag("debug-result").assertTextContains("registers", substring = true); true }.getOrDefault(false) }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.getExternalFilesDir(null), "test-screenshots/dynamic-debugger-dark.png").apply { parentFile!!.mkdirs() }
            file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            ui.onNodeWithText("脱离").performScrollTo().performClick()
            ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("debug-state").assertTextEquals("未附加 / 已脱离"); true }.getOrDefault(false) }
            org.junit.Assert.assertEquals(0, fixture.tracerPid())
        } }
    }
}
