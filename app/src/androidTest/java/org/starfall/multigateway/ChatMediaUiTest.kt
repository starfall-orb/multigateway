package org.starfall.multigateway

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.starfall.multigateway.ui.tools.MediaFileCard
import org.starfall.multigateway.ui.components.ChatFilePreview
import org.starfall.multigateway.ui.components.InlineVideoPreview
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChatMediaUiTest {
    @Test fun sendFileActivityIsHiddenWhileRunningAndAfterCompletion() {
        compose.setContent { MaterialTheme {
            ToolActivityCards(listOf(ToolActivity("running", "send_file"), ToolActivity("done", "send_file", status = "success")))
        } }
        compose.onNodeWithText("send_file").assertDoesNotExist()
        compose.onNodeWithText("send file").assertDoesNotExist()
    }

    @Test fun genericFilesCanBeDownloadedAndSelectedForNextMessage() {
        var selected = false
        compose.setContent { MaterialTheme {
            var checked by remember { mutableStateOf(false) }
            ChatFilePreview("/document.pdf", "document.pdf", "application/pdf", null, false,
                selectedForChat = checked, onToggleAttachment = { checked = !checked; selected = checked }, onOpen = {})
        } }
        compose.onNodeWithContentDescription("Download file").assertIsEnabled()
        compose.onNodeWithContentDescription("Attach file to next message").performClick()
        compose.runOnIdle { assertTrue(selected) }
        compose.onNodeWithContentDescription("Remove file from next message").performClick()
        compose.runOnIdle { assertFalse(selected) }
    }

    @Test fun portraitMediaIsBoundedAndVideoFullscreenCanBeOpenedAndClosed() {
        val bitmap = Bitmap.createBitmap(16, 1600, Bitmap.Config.ARGB_8888)
        compose.setContent { MaterialTheme { InlineVideoPreview("${context.cacheDir}/missing.mp4", bitmap.asImageBitmap()) } }
        val bounds = compose.onNodeWithContentDescription("Video preview").fetchSemanticsNode().boundsInRoot
        val maximum = context.resources.configuration.screenHeightDp * context.resources.displayMetrics.density / 2
        assertTrue(bounds.height <= maximum + 1)
        compose.onNodeWithContentDescription("Open fullscreen video").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Exit fullscreen video").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }
    @Test fun returnedVideoHasNoFileLabelsAndPlaybackErrorsStayInline() {
        val store = ToolFiles(context)
        val invalidVideo = ByteArray(32).also { "ftyp".toByteArray().copyInto(it, 4) }
        val name = runBlocking { store.save(invalidVideo.inputStream(), "video/mp4") }
        try {
            compose.setContent { MaterialTheme { MediaFileCard(store, name, imageOnly = true) } }
            compose.onNodeWithText(name).assertDoesNotExist()
            compose.onNodeWithText("▶ View video").assertDoesNotExist()
            compose.onNodeWithContentDescription("Play video").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Unable to play this video.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithContentDescription("Close viewer").assertDoesNotExist()
        } finally { store.delete(listOf(name)) }
    }
    @Test fun readButtonTogglesToStopAndShowsModelForActiveVersion() {
        val message = StoredMessage("a", ChatRole.MODEL, listOf(MessageVersion(
            content = "Read this answer.", modelId = "model-id", modelDisplayName = "Actual model")))
        compose.setContent {
            var reading by remember { mutableStateOf(false) }
            MaterialTheme {
                AssistantMessageCard(message, false, {}, {}, {}, {}, { reading = !reading }, {}, isReading = reading)
            }
        }
        compose.onNodeWithText("Actual model").assertExists()
        compose.onNodeWithContentDescription("Read aloud").performClick()
        compose.onNodeWithContentDescription("Stop reading").performClick()
        compose.onNodeWithContentDescription("Read aloud").assertExists()
    }
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
        compose.onNodeWithContentDescription("Exit Video generation mode").performClick()
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
            compose.onNodeWithContentDescription("Generated image").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Image preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Close viewer").performClick()
            compose.onNodeWithContentDescription("Image preview").assertDoesNotExist()
        } finally { store.delete(listOf(name)) }
    }

    @Test fun audioAttachmentPlaysInlineWithPlayPauseAndSeekControls() {
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
                compose.onAllNodesWithContentDescription("Play audio").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Play audio").performClick()
            compose.onNodeWithContentDescription("Pause audio").assertExists().performClick()
            compose.onNodeWithContentDescription("Play audio").assertExists()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertExists()
            compose.onNodeWithContentDescription("Download file").assertExists()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
        } finally { file.delete() }
    }

    @Test fun unavailableVideoShowsErrorAndCanBeClosed() {
        compose.setContent {
            MaterialTheme { MediaPreviewDialog("${context.cacheDir}/missing-video.mp4", "Video", "video/mp4", {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Unable to play this video.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Close viewer").assertIsEnabled()
    }

    @Test fun selectedGeneratedImageIsAttachedToNextMessageAndClearedAfterSend() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        var sentFiles: List<String>? = null
        try {
            val message = StoredMessage("assistant", ChatRole.MODEL, listOf(MessageVersion(toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(name))
            ))))
            compose.setContent {
                var attachments by remember { mutableStateOf<List<String>>(emptyList()) }
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        AssistantMessageCard(message, false, {}, {}, {}, {}, {}, {},
                            selectedImageAttachments = attachments,
                            onToggleChatImage = { ref -> attachments = if (ref in attachments) attachments - ref else attachments + ref })
                        UserInputArea(false, { _, files -> sentFiles = files; true }, { false }, { _, _, _ -> false },
                            null, {}, {}, "text", emptyList(), selectedProviderId = "p", onSelectModel = { _, _ -> },
                            conversationReasoningEffort = null, onSetReasoningEffort = {}, onStartConversationSummary = { false },
                            attachments = attachments, onAttachmentsChange = { attachments = it },
                            modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Attach image to next message").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Attach image to next message").performClick()
            compose.onNodeWithContentDescription("Remove image from next message").assertIsOn().performClick()
            compose.onNodeWithContentDescription("Attach image to next message").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Describe this image")
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.runOnIdle { assertEquals(listOf(store.resolve(name)!!.path), sentFiles) }
            compose.onNodeWithContentDescription("Attach image to next message").assertIsOff()
        } finally { store.delete(listOf(name)) }
    }

    @Test fun generatedMediaStaysBetweenTextBeforeAndAfterToolCall() {
        val store = ToolFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val name = runBlocking { store.save(bytes.inputStream(), "image/png") }
        try {
            val before = "Before the tool call.\n\n"
            val after = "After the tool result."
            val version = MessageVersion(content = before + after, toolActivity = listOf(
                ToolActivity("image", "generate_image", status = "success", files = listOf(name), contentOffset = before.length)
            ))
            compose.setContent { MaterialTheme {
                AssistantMessageCard(StoredMessage("assistant", ChatRole.MODEL, listOf(version)), false, {}, {}, {}, {}, {}, {})
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Generated image").fetchSemanticsNodes().isNotEmpty()
            }
            val beforeBounds = compose.onNodeWithText("Before the tool call.").fetchSemanticsNode().boundsInRoot
            val imageBounds = compose.onNodeWithContentDescription("Generated image").fetchSemanticsNode().boundsInRoot
            val afterBounds = compose.onNodeWithText(after).fetchSemanticsNode().boundsInRoot
            assertTrue(beforeBounds.bottom <= imageBounds.top)
            assertTrue(imageBounds.bottom <= afterBounds.top)
        } finally { store.delete(listOf(name)) }
    }
}
