package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
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
@Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
class ProviderAutoscrollRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private fun provider(id: String, group: String? = null, order: Int = 0) =
        LlmProviderInfo(id, id, ProviderType.OPENAI, baseUrl = "", groupId = group, sortOrder = order)

    private val manyRoot = (0..39).map { provider("p$it", order = it) }
    private val longFolder = listOf(provider("outside", order = 0)) +
        (0..35).map { provider("member-$it", "long", it) }

    private fun show(
        providers: List<LlmProviderInfo>,
        groups: List<ProviderGroup>,
        grid: Boolean,
        writes: MutableList<ProviderPlacement> = mutableListOf()
    ) {
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = providers,
                    providerGroups = groups,
                    isGridView = grid,
                    onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {},
                    onPlaceProvider = { writes += it; Result.success(Unit) }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun scrollPosition(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** Dispatch each step before advancing frames: performTouchInput batches events until its block returns. */
    private fun hold(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun moveFinger(point: Offset) {
        compose.onRoot().performTouchInput { moveTo(point) }
        hold(50)
    }

    @Test
    fun manualScrollStillWorksWithTheDragGestureInstalled() {
        show(manyRoot, emptyList(), grid = true)
        val before = scrollPosition("provider_list")
        compose.onNodeWithTag("provider_list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Manual scroll must succeed", scrollPosition("provider_list") > before)
    }

    @Test fun rootGridAutoscrollsWhileDraggingAtTheEdgesInGrid() = checkRootAutoscroll(grid = true)
    @Test fun rootListAutoscrollsWhileDraggingAtTheEdgesInList() = checkRootAutoscroll(grid = false)

    private fun checkRootAutoscroll(grid: Boolean) {
        val writes = mutableListOf<ProviderPlacement>()
        show(manyRoot, emptyList(), grid, writes)
        val list = compose.onNodeWithTag("provider_list").fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("provider_p0").fetchSemanticsNode().boundsInRoot.center
        val before = scrollPosition("provider_list")

        compose.onRoot().performTouchInput { down(source) }
        hold(600)
        compose.onNodeWithTag("dragged_provider").assertExists()
        moveFinger(Offset(source.x, list.bottom - 20f))
        repeat(30) { hold(100) }
        val forward = scrollPosition("provider_list")
        assertTrue("Dragging at the bottom edge must scroll: before=$before after=$forward", forward > before)

        moveFinger(Offset(source.x, list.top + 20f))
        repeat(30) { hold(100) }
        assertTrue("Dragging at the top edge must scroll back", scrollPosition("provider_list") < forward)

        compose.onRoot().performTouchInput { cancel() }
        compose.waitForIdle()
        assertTrue("Cancelling must not save anything", writes.isEmpty())
    }

    @Test
    fun openFolderScrollsItsMembersWhileDraggingAtItsEdge() {
        val writes = mutableListOf<ProviderPlacement>()
        show(longFolder, listOf(ProviderGroup("long", "Long folder", 1)), grid = true, writes = writes)
        compose.onNodeWithTag("provider_group_long", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val grid = compose.onNodeWithTag("provider_folder_grid_long", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val source = compose.onNodeWithTag("provider_folder_provider_member-0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center
        val before = scrollPosition("provider_folder_grid_long")

        compose.onRoot().performTouchInput { down(source) }
        hold(600)
        moveFinger(Offset(source.x, grid.bottom - 12f))
        repeat(20) { hold(100) }
        val after = scrollPosition("provider_folder_grid_long")
        compose.onRoot().performTouchInput { cancel() }
        compose.waitForIdle()

        assertTrue("Folder members must autoscroll: before=$before after=$after", after > before)
        assertTrue(writes.isEmpty())
    }
}
