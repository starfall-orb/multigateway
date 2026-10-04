package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    @Test fun streamingSegmentsAndPassbackFollowReasoning() {
        var saved: ModelConfiguration? = null
        compose.setContent { MaterialTheme {
            ModelEditScreen(provider, "custom-model", setOf("custom-model"),
                onSave = { _, config -> saved = config }, onBack = {})
        } }
        compose.onNodeWithText("Passback Thinking").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Off") and hasAnyAncestor(hasTestTag("reasoning-mode"))).performScrollTo().performClick()
        compose.onNodeWithText("Passback Thinking").assertDoesNotExist()
        compose.onNode(hasText("Auto") and hasAnyAncestor(hasTestTag("reasoning-mode"))).performScrollTo().performClick()
        compose.onNodeWithText("Passback Thinking").assertExists()
        listOf("On" to true, "Off" to false, "Default" to null).forEach { (label, value) ->
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("streaming-mode")))
                .performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("Save").performClick()
            compose.runOnIdle { assertEquals(value, saved?.supportStream) }
        }
    }

    @Test fun providerInitialsAndModelBadgesUpdateWithType() {
        var config by androidx.compose.runtime.mutableStateOf(ModelConfiguration(contextWindowTokens = 128_000))
        compose.setContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                org.starfall.multigateway.ui.components.EntityIcon(null, text =
                    org.starfall.multigateway.ui.components.providerInitials("My Provider Name"))
                org.starfall.multigateway.ui.chat.ModelCapabilityBadges(config)
            }
        } }
        compose.onNodeWithText("MP").assertIsDisplayed()
        compose.onNodeWithText("Chat").assertIsDisplayed()
        compose.onNodeWithText("128k").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Chat").fetchSemanticsNode().boundsInRoot.right <
            compose.onNodeWithText("128k").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image").assertExists()
        compose.onNodeWithContentDescription("Thinking").assertExists()
        compose.onNodeWithContentDescription("Tools").assertExists()
        compose.runOnIdle { config = config.copy(contextWindowTokens = 1_000_000) }
        compose.onNodeWithText("1M").assertIsDisplayed()
        compose.runOnIdle { config = config.copy(modelType = ModelType.IMAGE_GENERATION) }
        compose.onNodeWithText(ModelType.IMAGE_GENERATION.displayName).assertIsDisplayed()
        compose.onNodeWithText("1M").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Thinking").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tools").assertDoesNotExist()
    }

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
        compose.onNodeWithText("Models").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test connection").performClick()
        compose.onNode(hasText("image-only") and hasAnyAncestor(isDialog())).assertDoesNotExist()
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
        compose.onNodeWithText("Models").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Open model catalog")[0].performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Catalog Model").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Owned by: Vendor").assertDoesNotExist()
        compose.onNodeWithText("Added").assertDoesNotExist()
        compose.onNodeWithText("Catalog Model").performClick()
        compose.onNodeWithText("Owned by: Vendor").assertIsDisplayed()
        compose.onNodeWithText("Created: 1704067200").assertDoesNotExist()
        compose.onNodeWithText("Created: 2024-", substring = true).assertExists()
        compose.onNodeWithContentDescription("Select model catalog-test-model").performClick()
        compose.runOnIdle { assertTrue("catalog-test-model" in saved) }
        compose.onNode(hasText("Catalog Model") and hasContentDescription("Collapse")).performClick()
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
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test connection").assertIsDisplayed()
        compose.onNodeWithText("custom-model", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
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
        compose.onNode(hasText("On") and hasAnyAncestor(hasTestTag("reasoning-mode"))).performScrollTo().performClick()
        compose.onNodeWithText("Medium").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Off") and hasAnyAncestor(hasTestTag("reasoning-mode"))).performScrollTo().performClick()
        compose.onNodeWithText("Medium").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertTrue(saved!!.reasoningDisabled) }
        compose.onNodeWithText("Auto").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertNull(saved!!.reasoningEffort) }
    }
}
