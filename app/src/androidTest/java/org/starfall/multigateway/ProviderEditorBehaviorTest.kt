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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProviderEditorBehaviorTest {
    @Test fun connectionFieldsRestoreUrlSelectAuthAndToggleKeyVisibility() {
        var saved: LlmProviderInfo? = null
        compose.setContent { MaterialTheme {
            ProviderEditScreen(provider, false, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = { saved = it })
        } }
        compose.onNodeWithContentDescription("Restore default URL").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Base URL")).assertTextContains("https://api.openai.com/v1")
        compose.onNodeWithContentDescription("Select authentication type").performScrollTo().performClick()
        compose.onNodeWithText("URL Query").performClick()
        compose.onNode(hasSetTextAction() and hasText("URL Query")).performScrollTo().performTextReplacement("test-key")
        compose.onNodeWithContentDescription("Show API key").performClick()
        compose.onNode(hasSetTextAction() and hasText("URL Query")).assertTextContains("test-key")
        compose.onNodeWithContentDescription("Hide API key").performClick()
        compose.onNodeWithContentDescription("Show API key").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals("https://api.openai.com/v1", saved?.baseUrl)
            assertEquals(AuthMethod.QUERY_PARAM, saved?.auth?.method)
            assertEquals("test-key", saved?.auth?.value)
        }
    }
    @Test fun connectionDialogTestsOnlyTextModelsAndRemovesFailedModels() {
        val calls = mutableListOf<String>()
        var saved = emptyMap<String, ModelConfiguration>()
        val models = mapOf("text-ok" to ModelConfiguration(), "text-bad" to ModelConfiguration(),
            "image-only" to ModelConfiguration(modelType = ModelType.IMAGE_GENERATION))
        compose.setContent { MaterialTheme {
            ProviderEditScreen(provider.copy(config = ProviderConfiguration(modelConfigs = models)), false,
                onSaveModels = { _, value -> saved = value }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = {}, onTestConnection = { _, id ->
                    calls += id
                    if (id == "text-bad") Result.failure(IllegalStateException("Unavailable model")) else Result.success("OK")
                })
        } }
        compose.onNodeWithContentDescription("Test connection").performClick()
        compose.onNodeWithText("image-only").assertDoesNotExist()
        compose.onNodeWithContentDescription("Test all text models").performClick()
        compose.waitUntil(5_000) { calls.size == 2 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove unavailable models").performClick()
        compose.runOnIdle {
            assertEquals(setOf("text-ok", "text-bad"), calls.toSet())
            assertEquals(setOf("text-ok", "image-only"), saved.keys)
        }
        compose.onNodeWithText("text-bad").assertDoesNotExist()
    }

    @Test fun modelCatalogShowsApiMetadataAndCheckboxSelection() {
        var saved = emptyMap<String, ModelConfiguration>()
        compose.setContent { MaterialTheme {
            ProviderEditScreen(provider.copy(config = ProviderConfiguration()), false,
                onSaveModels = { _, value -> saved = value }, onReorderModels = { _, _ -> },
                onDismiss = {}, onSave = {}, onFetchModels = {
                    listOf(DiscoveredModel("catalog-test-model", displayName = "Catalog Model", metadata = buildJsonObject {
                        put("owned_by", "Vendor")
                        put("context_window", 32000)
                        put("created", 1704067200)
                    }))
                })
        } }
        compose.onNodeWithContentDescription("Open model catalog").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Catalog Model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Owned by: Vendor").assertDoesNotExist()
        compose.onNodeWithText("Added").assertDoesNotExist()
        compose.onNodeWithText("Catalog Model").performClick()
        compose.onNodeWithText("Owned by: Vendor").assertIsDisplayed()
        compose.onNodeWithText("Created: 1704067200").assertDoesNotExist()
        compose.onNodeWithText("Created: 2024-", substring = true).assertExists()
        compose.onNodeWithContentDescription("Select model catalog-test-model").performClick()
        compose.runOnIdle { assertTrue("catalog-test-model" in saved) }
        compose.onNodeWithText("Catalog Model").performClick()
        compose.onNodeWithText("Owned by: Vendor").assertDoesNotExist()
        compose.runOnIdle { assertTrue("catalog-test-model" in saved) }
        compose.onNodeWithContentDescription("Select model catalog-test-model").performClick()
        compose.runOnIdle { assertFalse("catalog-test-model" in saved) }
    }
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
