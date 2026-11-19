package com.kernelai.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.data.model.*
import com.kernelai.app.ui.screen.chat.*
import com.kernelai.app.ui.theme.*
import com.kernelai.app.ui.viewmodel.ChatUiState
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReasoningUsageTest {
    @get:Rule val ui = createComposeRule()
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "test-screenshots/$name.png").apply {
            parentFile!!.mkdirs(); outputStream().use { ui.onNodeWithTag("glass-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun actualReasoningAutoFoldsAfterCompletionAndCanBeReopenedWithoutHidingProse() {
        val message = mutableStateOf(ChatMessage(id = "reasoning-fixture", role = MessageRole.ASSISTANT, content = "正文始终可见", reasoning = "第一步核实进程身份\n\n第二步检查映射版本", isStreaming = true, phase = AssistantPhase.THINKING))
        ui.setContent { KernelAiTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ChatMessageBubble(message.value) } } }
        ui.waitUntil(10000) { ui.onAllNodesWithText("第二步检查映射版本").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("正文始终可见").assertExists()
        ui.runOnIdle { message.value = message.value.copy(isStreaming = false, phase = AssistantPhase.COMPLETE) }
        ui.onNodeWithContentDescription("展开思考").assertExists()
        ui.onNodeWithText("第二步检查映射版本").assertDoesNotExist()
        ui.onNodeWithText("正文始终可见").assertExists()
        ui.onNodeWithContentDescription("展开思考").performClick()
        ui.onNodeWithText("第二步检查映射版本").assertExists()
    }
    @Test fun usageOpensAsDetailedSheetAndMissingCacheUsageIsHonest() {
        ui.setContent { KernelAiTheme { Column(Modifier.fillMaxSize()) {
            UsageIndicator(ChatUiState(contextWindow = 1000000, context = ContextBreakdown(1600, 16100, 46200, 0, 13100), usageTotals = UsageTotals(1150000, 16500, requests = 7)))
        } } }
        ui.onNodeWithTag("usage-indicator").performClick()
        ui.onNodeWithText("预计下次请求").assertExists()
        ui.onNodeWithText("累计输入").assertExists()
        ui.onNodeWithText("未返回").assertExists()
        ui.onAllNodesWithText("8%", useUnmergedTree = true).assertCountEquals(2)
        screenshot("usage-040")
    }
    @Test fun markdownFencedAnswerRendersDirectlyWithoutSyntheticMarkdownArtifact() {
        ui.setContent { KernelAiTheme { Column(Modifier.fillMaxSize()) {
            MarkdownMessage("```markdown\n# 直接显示正文\n\n**格式保留**\n```", false, chatForeground())
        } } }
        ui.waitUntil(10000) { ui.onAllNodesWithText("直接显示正文").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("格式保留").assertExists()
        ui.onNodeWithText("AI产物.md").assertDoesNotExist()
        ui.onNodeWithText("复制代码").assertDoesNotExist()
    }
}
