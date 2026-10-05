package org.starfall.multigateway

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        var outlineColor = Color.Unspecified
        compose.setContent {
            MaterialTheme {
                outlineColor = MaterialTheme.colorScheme.outline
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
        assertFolderOutline(3, outlineColor)
        compose.onNodeWithText(compose.activity.getString(R.string.common_all)).assertDoesNotExist()
        compose.onNodeWithContentDescription(collapse).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Primary").assertDoesNotExist()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Root").assertIsDisplayed()
        assertFolderOutline(1, outlineColor)
        compose.runOnIdle {
            assertEquals(setOf("folder"), groups.last())
            assertEquals(setOf("grouped", "root"), providers.last())
        }
        compose.onNodeWithContentDescription(expand).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
        assertTreeRowsAligned()
        assertFolderOutline(3, outlineColor)
        compose.runOnIdle { assertEquals(emptySet<String>(), groups.last()); assertEquals(emptySet<String>(), providers.last()) }

        // Reopening individual branches must keep their children aligned at every depth.
        compose.onNodeWithTag("model-picker-group_folder").performClick()
        compose.onNodeWithTag("model-picker-provider_grouped").assertDoesNotExist()
        compose.onNodeWithTag("model-picker-group_folder").performClick()
        assertTreeRowsAligned()
        compose.onNodeWithTag("model-picker-provider_grouped").performClick()
        compose.onNodeWithTag("model-picker-model_grouped_alpha").assertDoesNotExist()
        compose.onNodeWithTag("model-picker-provider_grouped").performClick()
        assertTreeRowsAligned()
        compose.onNodeWithTag("model-picker-provider_root").performClick()
        compose.onNodeWithTag("model-picker-model_root_gamma").assertDoesNotExist()
        compose.onNodeWithTag("model-picker-provider_root").performClick()
        assertTreeRowsAligned()
        compose.onNodeWithContentDescription(collapse).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("alpha")
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription(collapse).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(expand).performClick()
        compose.onNode(hasText("alpha") and !hasSetTextAction()).assertIsDisplayed()
        val groupBounds = compose.onNodeWithTag("model-picker-group_folder").fetchSemanticsNode().boundsInRoot
        val modelBounds = compose.onNodeWithTag("model-picker-model_grouped_alpha").fetchSemanticsNode().boundsInRoot
        assertEquals(groupBounds.left, modelBounds.left, 0.5f)
        assertEquals(groupBounds.right, modelBounds.right, 0.5f)
    }

    private fun assertFolderOutline(rowCount: Int, color: Color) {
        repeat(rowCount) { index ->
            val pixels = compose.onNodeWithTag("model-picker-frame_$index").captureToImage().toPixelMap()
            assertEquals("Folder border on row $index", color.red, pixels[0, pixels.height / 2].red, 0.08f)
            assertEquals("Folder border on row $index", color.green, pixels[0, pixels.height / 2].green, 0.08f)
            assertEquals("Folder border on row $index", color.blue, pixels[0, pixels.height / 2].blue, 0.08f)
        }
        val rootPixels = compose.onNodeWithTag("model-picker-frame_$rowCount").captureToImage().toPixelMap()
        val rootEdge = rootPixels[0, rootPixels.height / 2]
        assertTrue("Root provider must remain outside the folder border", rootEdge != color)
    }

    private fun assertTreeRowsAligned() {
        val groupBounds = compose.onNodeWithTag("model-picker-group_folder").fetchSemanticsNode().boundsInRoot
        listOf(
            "model-picker-provider_grouped", "model-picker-model_grouped_alpha",
            "model-picker-provider_root", "model-picker-model_root_gamma"
        ).forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Left edge of $tag", groupBounds.left, bounds.left, 0.5f)
            assertEquals("Right edge of $tag", groupBounds.right, bounds.right, 0.5f)
        }
    }
}
