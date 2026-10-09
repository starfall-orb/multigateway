package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderPlacement
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderScreen

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h1000dp-xhdpi")
class ProviderFolderContainerTest {
    @get:Rule val compose = createComposeRule()

    private val group = ProviderGroup("g", "Folder", sortOrder = 1)
    private val providers = listOf(
        LlmProviderInfo("root", "Root", ProviderType.OPENAI, baseUrl = "", sortOrder = 0),
        LlmProviderInfo("member-a", "Member A", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0),
        LlmProviderInfo("member-b", "Member B", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 1),
        LlmProviderInfo("tail", "Tail", ProviderType.OPENAI, baseUrl = "", sortOrder = 2)
    )

    private fun showFolder(
        source: List<LlmProviderInfo> = providers,
        writes: MutableList<ProviderPlacement> = mutableListOf()
    ) {
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = source,
                    providerGroups = listOf(group),
                    isGridView = true,
                    onSaveProvider = {},
                    onSaveModels = { _, _ -> },
                    onReorderModels = { _, _ -> },
                    onDeleteProvider = {},
                    onReorderProviders = {},
                    onBack = {},
                    onPlaceProvider = { placement ->
                        writes += placement
                        Result.success(Unit)
                    }
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun folderIsAStandaloneRootTileAndDoesNotExpandIntoTheMainGrid() {
        showFolder()

        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_group_icon_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_member-a", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("provider_member-b", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("provider_root", useUnmergedTree = true).assertExists()
    }

    @Test
    fun clickingFolderOpensPhoneStyleDialogWithItsProviders() {
        showFolder()

        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_provider_member-a", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_folder_provider_member-b", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("provider_root", useUnmergedTree = true).assertExists()

        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun draggingAProviderOverFolderDoesNotChangeMembership() {
        val writes = mutableListOf<ProviderPlacement>()
        showFolder(
            source = listOf(
                LlmProviderInfo("source", "Source", ProviderType.OPENAI, baseUrl = "", sortOrder = 0),
                LlmProviderInfo("member", "Member", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0)
            ),
            writes = writes
        )
        val list = compose.onNodeWithTag("provider_list")
        val sourceBounds = compose.onNodeWithTag("provider_source").fetchSemanticsNode().boundsInRoot
        val folderBounds = compose.onNodeWithTag("provider_group_g").fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot

        list.performTouchInput {
            down(sourceBounds.center - listBounds.topLeft)
            advanceEventTime(700)
            moveTo(folderBounds.center - listBounds.topLeft)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()

        assertTrue(writes.isEmpty())
        compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true).assertDoesNotExist()
    }
}
