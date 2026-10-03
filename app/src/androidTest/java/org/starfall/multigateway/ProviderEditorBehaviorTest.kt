package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.ProviderEditScreen
import org.starfall.multigateway.ui.providers.ModelEditScreen

class ProviderEditorBehaviorTest {
    @get:Rule val compose = createComposeRule()
    private val provider = LlmProviderInfo("p", "Primary", ProviderType.OPENAI,
        baseUrl = "https://example.com/v1",
        config = ProviderConfiguration(modelConfigs = mapOf("custom-model" to ModelConfiguration())))

    @Test fun providerTabsSwipeAndModelCardOpensEditor() {
        compose.setContent {
            MaterialTheme {
                ProviderEditScreen(provider, false,
                    onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDismiss = {}, onSave = {})
            }
        }
        compose.onNodeWithText("Type").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test connection").assertIsDisplayed()
        compose.onNodeWithText("custom-model").assertIsDisplayed().performClick()
        compose.onNodeWithText("Edit Model").assertIsDisplayed()
    }

    @Test fun providerTitleReflectsNameEdits() {
        compose.setContent {
            MaterialTheme {
                ProviderEditScreen(provider, false,
                    onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDismiss = {}, onSave = {})
            }
        }
        compose.onNode(hasSetTextAction() and hasText("Primary")).performTextReplacement("Renamed")
        compose.onNode(hasText("Renamed") and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun modelOffIsSavedAndAutoCanRestoreDefault() {
        var saved: ModelConfiguration? = null
        compose.setContent {
            MaterialTheme {
                ModelEditScreen(provider, "custom-model", setOf("custom-model"),
                    onSave = { _, config -> saved = config }, onBack = {})
            }
        }
        compose.onNodeWithText("On").performScrollTo().performClick()
        compose.onNodeWithText("Medium").assertIsDisplayed()
        compose.onNodeWithText("Off").performClick()
        compose.onNodeWithText("Medium").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertTrue(saved!!.reasoningDisabled) }
        compose.onNodeWithText("Auto").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertNull(saved!!.reasoningEffort) }
    }
}
