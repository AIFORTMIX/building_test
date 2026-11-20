package com.kernelai.app

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.ui.navigation.*
import com.kernelai.app.ui.screen.disasm.DisasmScreen
import com.kernelai.app.ui.theme.KernelAiTheme
import com.kernelai.app.analysis.ElfFile
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class StaticAnalysisUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun userCanParseAnActualElfAndDecodeItsInstructions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "analysis-ui-${UUID.randomUUID()}.so"
        val file = File(context.filesDir, "workspaces/files/default/$name").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("analysis-fixture.so").use { source -> file.outputStream().use { source.copyTo(it) } }
        val address = ElfFile(file).use { elf ->
            elf.query("functions", 0, 100, "qingwei_fixture_sum")["entries"]!!.jsonArray.first().jsonObject["address"]!!.jsonPrimitive.content
        }
        try {
            ui.setContent { CompositionLocalProvider(LocalAppNavigator provides AppNavigator(Route.Main)) { KernelAiTheme { DisasmScreen() } } }
            ui.onNodeWithTag("binary-path").performTextInput(name)
            ui.onNodeWithText("解析文件").performScrollTo().performClick()
            ui.waitUntil(15000) { runCatching { ui.onNodeWithTag("binary-result").assertTextContains("ELF64", substring = true); true }.getOrDefault(false) }
            ui.onNodeWithText("反汇编").performScrollTo().performClick()
            // Offset zero is an ELF header, not executable code; the UI must explain the failure.
            ui.waitUntil(10000) { ui.onAllNodesWithText("此映射段没有执行权限，不将数据误当指令").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("切换地址").performScrollTo().performClick()
            ui.onNodeWithTag("binary-address").performTextReplacement(address)
            ui.onNodeWithText("反汇编").performScrollTo().performClick()
            ui.waitUntil(15000) { runCatching { ui.onNodeWithTag("binary-result").assertTextContains("Capstone 5.0.6", substring = true); true }.getOrDefault(false) }
            val output = File(context.getExternalFilesDir(null), "test-screenshots/static-elf-analysis.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { file.delete() }
    }
}
