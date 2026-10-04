package org.starfall.multigateway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.ModelPickerSheet
import org.starfall.multigateway.ui.components.InlineVideoPreview
import org.starfall.multigateway.ui.components.mediaThumbnail
import java.io.File

class DeviceRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelPickerDoesNotDismissWhenFlingReachesTop() {
        var dismissed = false
        val configs = (0..79).associate { "test-model-$it" to ModelConfiguration() }
        val provider = LlmProviderInfo("test", "Test Provider", ProviderType.OPENAI,
            baseUrl = "https://example.com/v1",
            config = ProviderConfiguration(modelConfigs = configs))
        compose.setContent { MaterialTheme {
            ModelPickerSheet(listOf(provider), selectedProviderId = "test", selectedModelId = "test-model-40",
                conversationReasoningEffort = null, onSelectModel = { _, _ -> },
                onSetReasoningEffort = {}, onDismiss = { dismissed = true })
        } }
        val list = compose.onNodeWithTag("model-picker-list")
        list.performScrollToIndex(40)
        repeat(12) {
            list.performTouchInput { swipeDown(durationMillis = 120) }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("List fling dismissed the sheet", dismissed) }
        }
        list.performScrollToIndex(0)
        repeat(3) {
            list.performTouchInput { swipeDown(durationMillis = 100) }
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("Top overscroll dismissed the sheet", dismissed) }
        }
        list.assertIsDisplayed()
    }

    @Test fun portraitPlaybackControlsStayVisibleAndVideoAllowsChatScrolling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Recorded on the test device with screenrecord at 180x640 before this test.
        val video = File(context.filesDir, "portrait-test.mp4")
        assertTrue("Missing device-recorded portrait fixture", video.isFile)
        val thumbnail = runBlocking { mediaThumbnail(context, video.path, "video/mp4", 640) }
        assertNotNull(thumbnail)
        var scrollPosition = { 0 }
        compose.setContent { MaterialTheme {
            val scroll = rememberScrollState()
            scrollPosition = { scroll.value }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                InlineVideoPreview(video.path, thumbnail, Modifier.testTag("portrait-video"))
                Spacer(Modifier.height(1500.dp))
                Text("Bottom of chat")
            }
        } }
        compose.onNodeWithContentDescription("Play video").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val frame = compose.onNodeWithTag("portrait-video").fetchSemanticsNode().boundsInRoot
        val seek = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        seek.assertIsDisplayed()
        compose.onNodeWithContentDescription("Open fullscreen video").assertIsDisplayed()
        val controls = seek.fetchSemanticsNode().boundsInRoot
        assertTrue("Seek controls extend below the video frame", controls.bottom <= frame.bottom + 1)
        compose.onNodeWithTag("portrait-video").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("Video consumed vertical chat scrolling", scrollPosition() > 0) }
    }
}
