package org.starfall.multigateway

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.ToolFiles
import org.starfall.multigateway.ui.chat.*
import org.starfall.multigateway.ui.components.MediaPreviewDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChatMediaUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mediaModesKeepComposerSizeAndSendDirectly() {
        var submitted: DirectMediaRequest? = null
        var textSubmissions = 0
        val provider = LlmProviderInfo("media", "Media", ProviderType.OPENAI, baseUrl = "https://example.com/v1",
            config = ProviderConfiguration(modelIds = listOf("image", "video"), modelConfigs = mapOf(
                "image" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION),
                "video" to ModelConfiguration(modelType = ModelType.VIDEO_GENERATION))))
        compose.setContent {
            MaterialTheme {
                UserInputArea(false, { _, _ -> textSubmissions++; true }, { submitted = it; true },
                    { _, _, _ -> false }, null, {}, {}, "text", listOf(provider),
                    selectedProviderId = "text-provider", onSelectModel = { _, _ -> },
                    conversationReasoningEffort = null, onSetReasoningEffort = {}, onStartConversationSummary = { false })
            }
        }
        val original = compose.onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot.size
        listOf("Image" to ModelType.IMAGE_GENERATION, "Video" to ModelType.VIDEO_GENERATION).forEach { (label, kind) ->
            compose.onNodeWithContentDescription("Attachments and tools").performClick()
            compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            val size = compose.onNodeWithTag("chat-input").fetchSemanticsNode().boundsInRoot.size
            assertEquals(original.width, size.width, 1f)
            assertEquals(original.height, size.height, 1f)
            compose.onNode(hasSetTextAction()).performTextInput("A quiet lake")
            compose.onNodeWithContentDescription("Generate $label").performClick()
            compose.runOnIdle {
                assertEquals(kind, submitted?.kind)
                assertEquals(label.lowercase(), submitted?.modelId)
                assertEquals("A quiet lake", submitted?.prompt)
                assertEquals(0, textSubmissions)
            }
        }
        compose.onNodeWithContentDescription("Video mode options").performClick()
        compose.onNodeWithText("Back to Chat").performClick()
        compose.onNodeWithContentDescription("Send message").assertExists()
    }

    @Test fun generatedImageIsVisibleInChatAndOpensViewerWithoutTextResponse() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        try {
            val message = StoredMessage("assistant", ChatRole.MODEL, listOf(MessageVersion(toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(name)),
                ToolActivity("duplicate", "other", status = "success", files = listOf(name))
            ))))
            compose.setContent {
                MaterialTheme { AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {}) }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Generated image").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithText("View image").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Image preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Image preview").assertDoesNotExist()
        } finally { store.delete(listOf(name)) }
    }

    @Test fun audioAttachmentOpensWithPlayPauseAndSeekControls() {
        val file = File.createTempFile("audio-preview", ".wav", context.cacheDir)
        // Ten seconds of silent PCM: exercise a real decoder without audible test output.
        val dataSize = 8000 * 2 * 10
        file.writeBytes(ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataSize)
        }.array())
        try {
            compose.setContent { MaterialTheme { AttachmentStrip(listOf(file.path), removable = false) } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Play media").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Play media").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Play audio").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Play audio").performClick()
            compose.onNodeWithContentDescription("Pause audio").assertExists().performClick()
            compose.onNodeWithContentDescription("Play audio").assertExists()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertExists()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithContentDescription("Play audio").assertDoesNotExist()
        } finally { file.delete() }
    }

    @Test fun unavailableVideoShowsErrorAndCanBeClosed() {
        compose.setContent {
            MaterialTheme { MediaPreviewDialog("${context.cacheDir}/missing-video.mp4", "Video", "video/mp4", {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Unable to play this video.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Close").assertIsEnabled()
    }
}
