package org.starfall.multigateway

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.chat.ModelPickerSheet

class ModelPickerToggleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun toggleControlsGroupedAndRootProvidersIncludingSearchResults() {
        val group = ProviderGroup("folder", "Work", 0)
        fun provider(id: String, name: String, model: String, groupId: String? = null) = LlmProviderInfo(
            id = id, name = name, type = ProviderType.OPENAI, baseUrl = "https://example.test", groupId = groupId,
            config = ProviderConfiguration(modelIds = listOf(model), modelConfigs = mapOf(model to ModelConfiguration()))
        )
        val groups = mutableListOf<Set<String>>()
        val providers = mutableListOf<Set<String>>()
        compose.setContent {
            MaterialTheme {
                ModelPickerSheet(
                    providers = listOf(provider("grouped", "Primary", "alpha", group.id), provider("root", "Root", "gamma")),
                    providerGroups = listOf(group), selectedProviderId = "", selectedModelId = "",
                    conversationReasoningEffort = null, onSelectModel = { _, _ -> }, onSetReasoningEffort = {}, onDismiss = {},
                    onCollapsedGroupIdsChange = { groups.add(it) }, onCollapsedProviderIdsChange = { providers.add(it) }
                )
            }
        }
        val collapse = compose.activity.getString(R.string.collapse_all_model_sections)
        val expand = compose.activity.getString(R.string.expand_all_model_sections)
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.common_all)).assertDoesNotExist()
        compose.onNodeWithContentDescription(collapse).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Primary").assertDoesNotExist()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Root").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(setOf("folder"), groups.last())
            assertEquals(setOf("grouped", "root"), providers.last())
        }
        compose.onNodeWithContentDescription(expand).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptySet<String>(), groups.last()); assertEquals(emptySet<String>(), providers.last()) }
        compose.onNodeWithContentDescription(collapse).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("alpha")
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription(collapse).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(expand).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
    }
}
