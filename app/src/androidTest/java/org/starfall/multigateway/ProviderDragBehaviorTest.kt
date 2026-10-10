package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderPlacement
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderScreen

/** Phone-style drag and drop: reorder, drop into a folder, drag out of an open folder. */
class ProviderDragBehaviorTest {
    @get:Rule val compose = createComposeRule()

    private val group = ProviderGroup("g", "Folder", sortOrder = 1)

    private fun setScreen(
        providers: List<LlmProviderInfo>,
        writes: MutableList<ProviderPlacement> = mutableListOf()
    ) {
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = providers,
                    providerGroups = listOf(group),
                    isGridView = true,
                    onSaveProvider = {},
                    onSaveModels = { _, _ -> },
                    onReorderModels = { _, _ -> },
                    onDeleteProvider = {},
                    onReorderProviders = {},
                    onBack = {},
                    onPlaceProvider = {
                        writes += it
                        Result.success(Unit)
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun folderOpensAsDialogInsteadOfExpandingInsideTheGrid() {
        setScreen(
            listOf(
                LlmProviderInfo("root", "Root", ProviderType.OPENAI, baseUrl = "", sortOrder = 0),
                LlmProviderInfo("member", "Member", ProviderType.OPENAI, baseUrl = "", groupId = "g")
            )
        )

        compose.onNodeWithTag("provider_member", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("provider_folder_provider_member", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun droppingRootProviderOnFolderMovesItInWithoutOpeningTheDialog() {
        val writes = mutableListOf<ProviderPlacement>()
        setScreen(
            listOf(
                LlmProviderInfo("root", "Root", ProviderType.OPENAI, baseUrl = "", sortOrder = 0),
                LlmProviderInfo("member", "Member", ProviderType.OPENAI, baseUrl = "", groupId = "g")
            ),
            writes
        )
        val source = compose.onNodeWithTag("provider_root").fetchSemanticsNode().boundsInRoot.center
        val folder = compose.onNodeWithTag("provider_group_g").fetchSemanticsNode().boundsInRoot.center

        compose.onRoot().performTouchInput {
            down(source)
            advanceEventTime(700)
            moveTo(folder)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()

        assertTrue(writes.single().groupId == "g")
        compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true).assertDoesNotExist()
    }
}
