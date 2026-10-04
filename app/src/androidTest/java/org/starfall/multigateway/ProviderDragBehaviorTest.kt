package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderRootOrderItem
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderScreen

class ProviderDragBehaviorTest {
    @Test fun onlyFirstProviderInListFolderIsIndentedAndFolderCanToggleRepeatedly() {
        show(grid = false, grouped = true, bothGrouped = true)
        val first = bounds("provider_p1")
        val second = bounds("provider_p2")
        assertTrue(first.left > second.left)
        assertTrue(second.width > first.width)
        repeat(3) {
            compose.onNodeWithText("Folder").performClick()
            compose.onNodeWithTag("provider_p1").assertDoesNotExist()
            compose.onNodeWithText("Folder").performClick()
            compose.onNodeWithTag("provider_p1").assertExists()
            compose.onNodeWithTag("provider_p2").assertExists()
        }
    }
    @get:Rule val compose = createComposeRule()
    private val providers = mutableStateOf(emptyList<LlmProviderInfo>())
    private val moves = mutableListOf<Pair<String, String?>>()
    private val rootOrders = mutableListOf<List<ProviderRootOrderItem>>()
    private val memberOrders = mutableListOf<List<String>>()

    private fun show(grid: Boolean, grouped: Boolean, collapsed: Boolean = false, bothGrouped: Boolean = false, includeFolders: Boolean = true) {
        providers.value = listOf(
            LlmProviderInfo("p1", "First", ProviderType.OPENAI, baseUrl = "", sortOrder = 1,
                groupId = if (grouped) "g1" else null),
            LlmProviderInfo("p2", "Second", ProviderType.OPENAI, baseUrl = "", sortOrder = 3,
                groupId = if (bothGrouped) "g1" else null)
        )
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = providers.value,
                    providerGroups = if (includeFolders) listOf(ProviderGroup("g1", "Folder", 0), ProviderGroup("g2", "Other", 2)) else emptyList(),
                    collapsedSectionsState = if (collapsed) setOf("g1", "g2") else setOf("g2"),
                    isGridView = grid,
                    onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onBack = {},
                    onMoveProviderToGroup = { id, group ->
                        moves += id to group
                        providers.value = providers.value.map { if (it.id == id) it.copy(groupId = group) else it }
                    },
                    onReorderProviders = { memberOrders += it },
                    onReorderRootItems = { rootOrders += it }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun dragTo(point: Offset, whileDragging: () -> Unit = {}) {
        val source = compose.onNodeWithTag("provider_p1")
        val sourceBounds = bounds("provider_p1")
        source.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(point - sourceBounds.topLeft)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("dragged_provider").assertExists()
        whileDragging()
        source.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("dragged_provider").assertDoesNotExist()
    }

    @Test fun rootProviderDropsIntoCollapsedFolderInList() = checkRootIntoFolder(grid = false)
    @Test fun rootProviderDropsIntoCollapsedFolderInGrid() = checkRootIntoFolder(grid = true)
    @Test fun rootProviderDropsIntoExpandedFolderInList() = checkRootIntoFolder(grid = false, collapsed = false)
    @Test fun rootProviderDropsIntoExpandedFolderInGrid() = checkRootIntoFolder(grid = true, collapsed = false)

    private fun checkRootIntoFolder(grid: Boolean, collapsed: Boolean = true) {
        show(grid, grouped = false, collapsed = collapsed)
        val folder = bounds("provider_group_g1")
        dragTo(folder.center) {
            assertEquals("Folder must not dodge the provider", folder, bounds("provider_group_g1"))
            assertTrue(rootOrders.isEmpty())
        }
        assertEquals(listOf("p1" to "g1"), moves)
        assertTrue(rootOrders.isEmpty())
        if (collapsed) compose.onNodeWithTag("provider_p1").assertDoesNotExist()
        else assertEquals("g1", providers.value.first { it.id == "p1" }.groupId)
    }

    @Test fun providerLeavesExpandedFolderInList() = checkUngroup(grid = false)
    @Test fun providerLeavesExpandedFolderInGrid() = checkUngroup(grid = true)

    private fun checkUngroup(grid: Boolean) {
        show(grid, grouped = true)
        val folder = bounds("provider_group_g1")
        val last = bounds("provider_p2")
        dragTo(Offset(last.center.x, last.bottom + 24f)) {
            assertEquals(folder, bounds("provider_group_g1"))
            assertTrue(memberOrders.isEmpty())
            assertTrue("Dragged card must be visible outside the clipped folder",
                bounds("dragged_provider").center.y > folder.bottom)
        }
        assertEquals(listOf("p1" to null), moves)
        assertNull(providers.value.first { it.id == "p1" }.groupId)
    }

    @Test fun providerTransfersBetweenFoldersWithoutReorderingRoot() {
        show(grid = true, grouped = true)
        val other = bounds("provider_group_g2")
        dragTo(other.center) { assertEquals(other, bounds("provider_group_g2")) }
        assertEquals(listOf("p1" to "g2"), moves)
        assertTrue(rootOrders.isEmpty())
    }

    @Test fun droppingInsideOwnFolderKeepsMembership() {
        show(grid = false, grouped = true)
        val source = bounds("provider_p1")
        dragTo(source.center + Offset(0f, 8f))
        assertTrue(moves.isEmpty())
        assertTrue(memberOrders.isEmpty())
    }

    @Test fun rootProvidersCanStillBeReorderedOnDrop() {
        show(grid = false, grouped = false, includeFolders = false)
        val targetBefore = bounds("provider_p2")
        dragTo(targetBefore.center) {
            assertTrue("Other provider must move before release", bounds("provider_p2").top < targetBefore.top)
            assertTrue("Persist order only on release", rootOrders.isEmpty())
        }
        assertTrue(moves.isEmpty())
        assertEquals(listOf("p2", "p1"), rootOrders.single().map { it.id })
    }

    @Test fun folderYieldsOnlyAfterOneSecondOutsideDwell() {
        show(grid = false, grouped = false, collapsed = true)
        val folder = bounds("provider_group_g2")
        dragTo(Offset(folder.right + 8f, folder.center.y)) {
            assertEquals("Folder must initially stay in place", folder, bounds("provider_group_g2"))
            compose.waitUntil(3_000) { bounds("provider_group_g2").top < folder.top - 10f }
            assertTrue(rootOrders.isEmpty())
        }
        assertTrue("Outside dwell is reorder, not membership", moves.isEmpty())
        assertEquals(listOf("g1", "g2", "p1", "p2"), rootOrders.single().map { it.id })
    }

    @Test fun providersCanStillBeReorderedWithinFolderInGrid() {
        show(grid = true, grouped = true, bothGrouped = true)
        val before = bounds("provider_p2")
        dragTo(before.center) {
            assertTrue("Folder member must move before release", bounds("provider_p2").top < before.top)
            assertTrue(memberOrders.isEmpty())
        }
        assertTrue(moves.isEmpty())
        assertEquals(listOf("p2", "p1"), memberOrders.single())
    }

    @Test fun cancellingOverFolderDoesNotChangeMembershipOrOrder() {
        show(grid = true, grouped = false, collapsed = true)
        val source = compose.onNodeWithTag("provider_p1")
        val target = bounds("provider_group_g1").center - bounds("provider_p1").topLeft
        source.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(target)
            cancel()
        }
        compose.waitForIdle()
        assertTrue(moves.isEmpty())
        assertTrue(rootOrders.isEmpty())
        compose.onNodeWithTag("dragged_provider").assertDoesNotExist()
    }
}
