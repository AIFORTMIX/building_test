package com.kernelai.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun floatingNavigationStaysAvailableOnEveryMainPage() {
        ui.waitUntil(15000) { ui.onAllNodesWithTag("nav-0").fetchSemanticsNodes().isNotEmpty() }
        for (page in listOf(1, 2, 3, 0)) {
            ui.onNodeWithTag("nav-$page").performClick()
            ui.waitUntil(10000) { runCatching { ui.onNodeWithTag("nav-$page").assertIsSelected(); true }.getOrDefault(false) }
            for (item in 0..3) ui.onNodeWithTag("nav-$item").assertIsDisplayed()
        }
        ui.onNodeWithContentDescription("新对话").assertIsDisplayed()
        ui.onNodeWithContentDescription("对话操作").assertIsDisplayed()
        ui.onNodeWithText("复制消息").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.getExternalFilesDir(null), "test-screenshots/glass-main-navigation.png").apply { parentFile!!.mkdirs() }
        image.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithContentDescription("对话列表").performClick()
        ui.onNodeWithTag("glass-dialog").assertIsDisplayed()
        ui.onNodeWithText("关闭").performClick()
    }
}
