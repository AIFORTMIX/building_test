package com.kernelai.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernelai.app.ui.preview.*
import com.kernelai.app.ui.theme.KernelAiTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class MediaPreviewTest {
    @get:Rule val ui = createComposeRule()
    @Test fun remoteVideoDisplaysAnActualFirstFrameWithoutPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }
        val server = MockWebServer(); server.start()
        try {
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(video))) }
            val source = server.url("/first-frame.mp4").toString()
            ui.setContent { KernelAiTheme { VideoThumbnail(source, "测试视频", onOpen = {}) } }
            ui.waitUntil(20_000) { ui.onAllNodesWithContentDescription("视频首帧：测试视频").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("视频首帧：测试视频").assertIsDisplayed()
            Assert.assertTrue(server.requestCount >= 1)
        } finally { server.shutdown() }
    }
    @Test fun audioCanPlayPauseSeekAndStopInline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "artifacts/test-audio.wav").apply { parentFile!!.mkdirs() }
        val size = 8000 * 2 * 8
        val wave = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        file.writeBytes(wave.array())
        ui.setContent { KernelAiTheme { Column { MediaPlayerPanel(android.net.Uri.fromFile(file).toString(), audioOnly = true) } } }
        ui.onNodeWithTag("media-play").performClick()
        ui.waitUntil(15_000) { ui.onNodeWithTag("media-player").fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("播放中") }
        ui.onNodeWithTag("media-play").performClick()
        ui.waitUntil(5_000) { ui.onNodeWithTag("media-player").fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("已暂停") }
        ui.onNodeWithTag("media-seek").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width * .06f, height / 2f), androidx.compose.ui.geometry.Offset(width * .60f, height / 2f), 400) }
        ui.waitUntil(5_000) { Regex("00:0[3-6] / 00:08").containsMatchIn(ui.onNodeWithTag("media-player").fetchSemanticsNode().config[SemanticsProperties.StateDescription]) }
        ui.onNodeWithText("停止").performClick()
        ui.waitUntil(5_000) { ui.onNodeWithTag("media-player").fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("00:00 / 00:08") }
    }
}

@RunWith(AndroidJUnit4::class)
class MediaActivityPreviewTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun launch(file: File, kind: PreviewKind): ActivityScenario<ArtifactPreviewActivity> = ActivityScenario.launch(Intent(context, ArtifactPreviewActivity::class.java)
        .putExtra("source", file.path).putExtra("title", file.name).putExtra("kind", kind.name).putExtra("local", true))
    @Test fun imageAndMarkdownAndPlainTextOpenInFullScreen() {
        val directory = File(context.filesDir, "artifacts").apply { mkdirs() }
        val image = File(directory, "fullscreen-fixture.png").apply { writeBytes(previewPng()) }
        launch(image, PreviewKind.IMAGE).use { ui.onNodeWithTag("fullscreen-image").assertIsDisplayed() }
        val markdown = File(directory, "fullscreen-fixture.md").apply { writeText("# 全屏 Markdown 验证\n\n**正文格式已解析**") }
        launch(markdown, PreviewKind.MARKDOWN).use {
            ui.waitUntil(10_000) { ui.onAllNodesWithText("全屏 Markdown 验证").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("fullscreen-markdown").assertIsDisplayed()
        }
        val text = File(directory, "fullscreen-fixture.txt").apply { writeText("普通文本可在 App 内阅读") }
        launch(text, PreviewKind.TEXT).use {
            ui.waitUntil(10_000) { ui.onAllNodesWithText("普通文本可在 App 内阅读").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("fullscreen-text").assertIsDisplayed()
        }
    }
    @Test fun webPageOpensInsideTheApp() {
        val server = MockWebServer(); server.start()
        try {
            repeat(4) { server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>In-app browser fixture</body></html>")) }
            val intent = Intent(context, ArtifactPreviewActivity::class.java).putExtra("source", server.url("/web").toString()).putExtra("kind", PreviewKind.WEB.name)
            ActivityScenario.launch<ArtifactPreviewActivity>(intent).use {
                ui.waitUntil(10_000) { server.requestCount > 0 }
                ui.onNodeWithTag("in-app-web").assertIsDisplayed()
                Assert.assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, it.state)
            }
        } finally { server.shutdown() }
    }
}
