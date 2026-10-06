package org.starfall.multigateway

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.DiscoveredModel
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderModelCatalogSheet

class BottomSheetScrollBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fetchModelsKeepsScrollingTowardTheEndWithoutAReverseSwipe() {
        val models = (0 until 150).map { DiscoveredModel(id = "scroll-model-$it", displayName = "Catalog model $it") }
        compose.setContent { MaterialTheme {
            ProviderModelCatalogSheet(
                provider = LlmProviderInfo("test", "Test", ProviderType.OPENAI, baseUrl = "https://example.test"),
                selectedModels = emptySet(), onFetchModels = { models }, onCatalogLoaded = {},
                onToggle = {}, onSetSelection = { _, _ -> }, onDismiss = {}
            )
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Catalog model 0").fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNodeWithTag("provider-model-catalog-list")
        repeat(40) {
            val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val position = before.value()
            val couldScroll = position < before.maxValue()
            list.performTouchInput { swipeUp(durationMillis = 180) }
            compose.waitForIdle()
            val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (couldScroll) assertTrue("Forward swipes must keep advancing until the real bottom",
                after.value() > position || after.value() >= after.maxValue())
        }
        compose.onNodeWithText("Catalog model 149").assertIsDisplayed()
        // Touching the bottom must not leave a stretch state that blocks later gestures.
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 180) }; compose.waitForIdle() }
        repeat(40) { list.performTouchInput { swipeDown(durationMillis = 180) }; compose.waitForIdle() }
        compose.onNodeWithText("Catalog model 0").assertIsDisplayed()
    }
}
