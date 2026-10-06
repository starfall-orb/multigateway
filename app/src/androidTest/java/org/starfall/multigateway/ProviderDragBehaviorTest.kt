package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderRootOrderItem
import org.starfall.multigateway.data.model.ProviderType
import kotlin.math.abs
import org.starfall.multigateway.ui.providers.ProviderScreen

class ProviderDragBehaviorTest {
    @Test fun gridPreviewChangesMembershipAndFolderFrameBeforeRelease() {
        val items = mutableStateOf(listOf(
            LlmProviderInfo("p1", "Outside", ProviderType.OPENAI, baseUrl = "", sortOrder = 0),
            LlmProviderInfo("a", "A", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0),
            LlmProviderInfo("b", "B", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 1)))
        val placements = mutableListOf<org.starfall.multigateway.data.model.ProviderPlacement>()
        compose.setContent { MaterialTheme {
            ProviderScreen(providers = items.value, providerGroups = listOf(ProviderGroup("g", "Folder", 1)),
                isGridView = true, onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDeleteProvider = {}, onReorderProviders = {}, onBack = {}, onPlaceProvider = { placement ->
                    placements += placement
                    items.value = items.value.map { p ->
                        val group = if (p.id == placement.providerId) placement.groupId else p.groupId
                        p.copy(groupId = group, sortOrder = placement.groupOrders[group]?.indexOf(p.id)
                            ?: placement.rootOrder.indexOfFirst { !it.isGroup && it.id == p.id })
                    }.sortedBy { it.sortOrder }
                    Result.success(Unit)
                })
        } }
        compose.waitForIdle()
        val heading = bounds("provider_group_g")
        assertEquals(bounds("provider_p1").width, bounds("provider_a").width, 1f)
        dragTo(bounds("provider_a").center) {
            assertTrue("The frame must expand into the former root slot", bounds("provider_group_g").left < heading.left)
            assertTrue("Preview must not write storage", placements.isEmpty())
        }
        assertEquals(1, placements.size)
        assertEquals("g", placements.single().groupId)
        assertEquals(listOf("a", "p1", "b"), placements.single().groupOrders["g"])
    }

    @Test fun gridFolderFillsPreviousRowAndSharesLastRowWithFollowingProvider() {
        val items = listOf(
            LlmProviderInfo("before", "Before", ProviderType.OPENAI, baseUrl = "", sortOrder = 0),
            LlmProviderInfo("a", "A", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0),
            LlmProviderInfo("b", "B", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 1),
            LlmProviderInfo("c", "C", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 2),
            LlmProviderInfo("after", "After", ProviderType.OPENAI, baseUrl = "", sortOrder = 2)
        )
        compose.setContent { MaterialTheme {
            ProviderScreen(providers = items, providerGroups = listOf(ProviderGroup("g", "Folder", 1)),
                isGridView = true, onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDeleteProvider = {}, onReorderProviders = {}, onBack = {})
        } }
        compose.waitForIdle()
        assertEquals(bounds("provider_before").top, bounds("provider_group_g").top, 1f)
        assertTrue(bounds("provider_group_g").left > bounds("provider_before").right)
        assertEquals(bounds("provider_a").top, bounds("provider_b").top, 1f)
        assertEquals(bounds("provider_c").top, bounds("provider_after").top, 1f)
        assertTrue(bounds("provider_after").left > bounds("provider_c").right)
        compose.onNodeWithTag("provider_group_icon_g").performClick()
        compose.onNodeWithTag("provider_a").assertDoesNotExist()
        compose.onNodeWithTag("provider_before").assertExists()
        compose.onNodeWithTag("provider_after").assertExists()
    }

    @Test fun providersToolbarSearchFiltersCollapsedFoldersAndRestoresThemWhenClosed() {
        show(grid = false, grouped = true, collapsed = true)
        compose.onNodeWithText("Providers").assertIsDisplayed()
        val search = compose.onNodeWithContentDescription("Search providers")
        val layout = compose.onNodeWithContentDescription("Switch to Grid View")
        val folder = compose.onNodeWithContentDescription("Add provider group")
        val add = compose.onNodeWithContentDescription("Add Provider")
        assertTrue(search.fetchSemanticsNode().boundsInRoot.right <= layout.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(layout.fetchSemanticsNode().boundsInRoot.right <= folder.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(folder.fetchSemanticsNode().boundsInRoot.right <= add.fetchSemanticsNode().boundsInRoot.left)
        search.performClick()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        layout.assertDoesNotExist()
        folder.assertDoesNotExist()
        add.assertDoesNotExist()
        compose.onNodeWithTag("provider_search").performTextReplacement("first")
        compose.onNodeWithTag("provider_p1").assertExists()
        compose.onNodeWithTag("provider_p2").assertDoesNotExist()
        compose.onNodeWithTag("provider_group_g2").assertDoesNotExist()
        compose.onNodeWithTag("provider_search").performTextReplacement("no-such-provider")
        compose.onNodeWithText("No matching providers or folders").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithTag("provider_search").assertDoesNotExist()
        compose.onNodeWithTag("provider_group_g1").assertExists()
        compose.onNodeWithTag("provider_p1").assertDoesNotExist()
        compose.onNodeWithTag("provider_p2").assertExists()
        compose.runOnIdle { assertTrue(rootOrders.isEmpty()); assertTrue(memberOrders.isEmpty()) }
    }

    @Test fun onlyFirstProviderInListFolderIsIndentedAndFolderCanToggleRepeatedly() {
        show(grid = false, grouped = true, bothGrouped = true)
        val first = bounds("provider_p1")
        val second = bounds("provider_p2")
        assertTrue(first.left > second.left)
        assertTrue(second.width > first.width)
        assertEquals(bounds("provider_group_icon_g1").height, first.height, 1f)
        val longName = "A very long provider name that must remain inside the folder logo row ".repeat(5)
        compose.runOnIdle {
            providers.value = providers.value.map { if (it.id == "p1") it.copy(name = longName) else it }
        }
        compose.waitForIdle()
        assertEquals(bounds("provider_group_icon_g1").height, bounds("provider_p1").height, 1f)
        assertEquals(second.height, bounds("provider_p2").height, 1f)
        val textLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("provider_title_p1")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        assertEquals(1, textLayouts.size)
        assertTrue(textLayouts.single().lineCount <= 2)
        assertTrue(textLayouts.single().hasVisualOverflow)
        assertFalse(textLayouts.single().isLineEllipsized(textLayouts.single().lineCount - 1))
        val unbrokenName = "LongProviderNameWithoutSpaces".repeat(8)
        compose.runOnIdle {
            providers.value = providers.value.map { if (it.id == "p1") it.copy(name = unbrokenName) else it }
        }
        compose.waitForIdle()
        textLayouts.clear()
        compose.onNodeWithTag("provider_title_p1")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        assertEquals(1, textLayouts.single().lineCount)
        assertEquals(unbrokenName, textLayouts.single().layoutInput.text.text)
        assertTrue(textLayouts.single().hasVisualOverflow)
        assertFalse(textLayouts.single().isLineEllipsized(0))
        assertEquals(bounds("provider_group_icon_g1").height, bounds("provider_p1").height, 1f)
        // Dense glyphs make the rendered fade measurable independently of letter spacing.
        compose.runOnIdle {
            providers.value = providers.value.map { if (it.id == "p1") it.copy(name = "█".repeat(100)) else it }
        }
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("provider_title_p1").captureToImage().toPixelMap()
        fun contrast(color: Color): Float = abs(color.red - cardColor.red) +
            abs(color.green - cardColor.green) + abs(color.blue - cardColor.blue)
        val frontContrast = contrast(pixels[pixels.width / 4, pixels.height / 2])
        val tailContrast = contrast(pixels[pixels.width - 1, pixels.height / 2])
        assertTrue("The beginning of the title should remain visible", frontContrast > 0.5f)
        assertTrue("The overflowing tail should fade into the card background", tailContrast < frontContrast * 0.35f)
        repeat(3) {
            compose.onNodeWithText("Folder").performClick()
            compose.onNodeWithTag("provider_p1").assertDoesNotExist()
            compose.onNodeWithText("Folder").performClick()
            compose.onNodeWithTag("provider_p1").assertExists()
            compose.onNodeWithTag("provider_p2").assertExists()
        }
    }
    @Test fun libraryAutoscrollCrossesFolderBoundaryAndCancellationRestoresListOrder() = checkEdgeTransfer(false)
    @Test fun libraryAutoscrollCrossesFolderBoundaryAndCancellationRestoresGridOrder() = checkEdgeTransfer(true)

    private fun checkEdgeTransfer(grid: Boolean) {
        val initial = listOf(LlmProviderInfo("outside", "Outside", ProviderType.OPENAI, baseUrl = "", sortOrder = 0)) +
            (0..35).map { LlmProviderInfo("member-$it", "Member $it", ProviderType.OPENAI,
                baseUrl = "", groupId = "long", sortOrder = it) } +
            LlmProviderInfo("tail", "Tail", ProviderType.OPENAI, baseUrl = "", sortOrder = 2)
        val writes = mutableListOf<org.starfall.multigateway.data.model.ProviderPlacement>()
        compose.setContent { MaterialTheme {
            ProviderScreen(providers = initial, providerGroups = listOf(ProviderGroup("long", "Long folder", 1)),
                isGridView = grid, onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                onDeleteProvider = {}, onReorderProviders = {}, onBack = {}, onPlaceProvider = {
                    writes += it; Result.success(Unit)
                })
        } }
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("provider_list")
        val root = surface.fetchSemanticsNode().boundsInRoot
        val source = bounds("provider_outside").center - root.topLeft
        fun scrollPosition() = surface.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scrollPosition()
        compose.mainClock.autoAdvance = false
        surface.performTouchInput {
            down(source)
            advanceEventTime(650)
            moveTo(Offset(source.x, height - 20f))
        }
        repeat(15) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        val forward = scrollPosition()
        assertTrue("Library must scroll through folder members while the finger stays at the edge", forward > before)
        assertTrue("Autoscroll preview must not persist", writes.isEmpty())
        surface.performTouchInput { moveTo(Offset(source.x, 20f)) }
        repeat(15) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
        assertTrue("Reversing at the edge must reverse autoscroll", scrollPosition() < forward)
        surface.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("Cancellation must not save membership or order", writes.isEmpty())
        surface.performScrollToNode(hasTestTag("provider_outside"))
        compose.onNodeWithTag("provider_outside").assertIsDisplayed()
        compose.onNodeWithTag("dragged_provider").assertDoesNotExist()
        val outside = bounds("provider_outside")
        val folder = bounds("provider_group_long")
        assertTrue("Cancelled provider must return before its original folder", outside.top <= folder.top)
    }

    @get:Rule val compose = createComposeRule()
    private val providers = mutableStateOf(emptyList<LlmProviderInfo>())
    private val moves = mutableListOf<Pair<String, String?>>()
    private val rootOrders = mutableListOf<List<ProviderRootOrderItem>>()
    private val memberOrders = mutableListOf<List<String>>()
    private var cardColor = Color.Unspecified
    private var gridMode = false

    private fun show(grid: Boolean, grouped: Boolean, collapsed: Boolean = false, bothGrouped: Boolean = false,
        includeFolders: Boolean = true, rootProviderBetweenFolders: Boolean = false) {
        gridMode = grid
        providers.value = listOf(
            LlmProviderInfo("p1", "First", ProviderType.OPENAI, baseUrl = "", sortOrder = 1,
                groupId = if (grouped) "g1" else null),
            LlmProviderInfo("p2", "Second", ProviderType.OPENAI, baseUrl = "", sortOrder = if (rootProviderBetweenFolders) 1 else 3,
                groupId = if (bothGrouped) "g1" else null)
        )
        compose.setContent {
            MaterialTheme {
                cardColor = MaterialTheme.colorScheme.surfaceContainerLow
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

    private fun bounds(tag: String): Rect {
        val own = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        if (!gridMode || !tag.startsWith("provider_group_") || tag.startsWith("provider_group_icon_")) return own
        val groupId = tag.removePrefix("provider_group_")
        val members = providers.value.filter { it.groupId == groupId }.flatMap {
            compose.onAllNodesWithTag("provider_${it.id}").fetchSemanticsNodes().map { node -> node.boundsInRoot }
        }
        val parts = members + own
        return Rect(parts.minOf { it.left }, parts.minOf { it.top }, parts.maxOf { it.right }, parts.maxOf { it.bottom })
    }

    @Test fun providerListRetainsScrollPositionAfterReturningFromEditor() = checkReturnPosition(grid = false)
    @Test fun providerGridRetainsScrollPositionAfterReturningFromEditor() = checkReturnPosition(grid = true)

    private fun checkReturnPosition(grid: Boolean) {
        show(grid, grouped = false, includeFolders = false)
        compose.runOnIdle {
            providers.value = (0 until 40).map { index ->
                LlmProviderInfo("scroll_$index", "Scroll Provider $index", ProviderType.OPENAI,
                    baseUrl = "", sortOrder = index)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("provider_list").performScrollToIndex(20)
        compose.onNodeWithTag("provider_list")
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
        compose.waitForIdle()
        val before = bounds("provider_scroll_20")
        compose.onNodeWithTag("provider_scroll_20").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider_list").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("provider_scroll_20").assertIsDisplayed()
        assertEquals(before, bounds("provider_scroll_20"))
        // Reopening the editor should leave the same saved position intact again.
        compose.onNodeWithTag("provider_scroll_20").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.common_back)).performClick()
        compose.waitForIdle()
        assertEquals(before, bounds("provider_scroll_20"))
    }

    @Test fun providerBetweenFoldersAndFollowingFolderAnimateTogetherOnCollapseAndExpand() {
        show(grid = false, grouped = true, rootProviderBetweenFolders = true)
        compose.mainClock.autoAdvance = false
        try {
            repeat(2) {
                val providerBefore = bounds("provider_p2").top
                val folderBefore = bounds("provider_group_g2").top
                compose.onNodeWithText("Folder").performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(80)
                compose.waitForIdle()
                val providerDuring = bounds("provider_p2").top
                val folderDuring = bounds("provider_group_g2").top
                compose.mainClock.advanceTimeBy(3_000)
                compose.waitForIdle()
                val providerAfter = bounds("provider_p2").top
                val folderAfter = bounds("provider_group_g2").top
                assertTrue("Provider should pass through an intermediate position",
                    providerDuring > minOf(providerBefore, providerAfter) + 1f &&
                        providerDuring < maxOf(providerBefore, providerAfter) - 1f)
                assertTrue("Following folder should move smoothly instead of teleporting",
                    folderDuring > minOf(folderBefore, folderAfter) + 1f &&
                        folderDuring < maxOf(folderBefore, folderAfter) - 1f)
                assertEquals("Provider and folder should move together",
                    providerDuring - providerBefore, folderDuring - folderBefore, 2f)
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

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
        if (grid) assertEquals(1, rootOrders.size) else assertTrue(rootOrders.isEmpty())
        if (collapsed && !grid) compose.onNodeWithTag("provider_p1").assertDoesNotExist()
        else assertEquals("g1", providers.value.first { it.id == "p1" }.groupId)
    }

    @Test fun providerLeavesExpandedFolderInList() = checkUngroup(grid = false)
    @Test fun providerLeavesExpandedFolderInGrid() = checkUngroup(grid = true)

    @Test fun liftedProviderRemainsVisibleAndTracksFingerOutsideFolderInList() = checkLiftedCard(false)
    @Test fun liftedProviderRemainsVisibleAndTracksFingerOutsideFolderInGrid() = checkLiftedCard(true)

    private fun checkLiftedCard(grid: Boolean) {
        show(grid, grouped = true)
        val source = compose.onNodeWithTag("provider_p1")
        val start = bounds("provider_p1")
        val folder = bounds("provider_group_g1")
        val last = bounds("provider_p2")
        val destination = Offset(start.center.x, last.bottom + 24f)
        source.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(destination - start.topLeft)
        }
        compose.waitForIdle()
        val lifted = bounds("dragged_provider")
        assertEquals(destination.x, lifted.center.x, 1f)
        assertEquals(destination.y, lifted.center.y, 1f)
        assertTrue(lifted.center.y > folder.bottom)
        val image = compose.onNodeWithTag("dragged_provider").captureToImage().toPixelMap()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals("Card must actually be drawn outside the folder, not just have unclipped bounds",
            cardColor.toArgb(), image[image.width / 2, image.height - (8 * density).toInt()].toArgb())
        val next = destination + Offset(12f, -20f)
        source.performTouchInput { moveTo(next - start.topLeft) }
        compose.waitForIdle()
        assertEquals(next.x, bounds("dragged_provider").center.x, 1f)
        assertEquals(next.y, bounds("dragged_provider").center.y, 1f)
        if (grid) assertTrue(bounds("provider_group_g1").width <= folder.width)
        else assertEquals(folder, bounds("provider_group_g1"))
        source.performTouchInput { cancel() }
        compose.waitForIdle()
        compose.onNodeWithTag("dragged_provider").assertDoesNotExist()
        compose.onNodeWithTag("provider_p1").assertIsDisplayed()
        assertTrue(moves.isEmpty())
    }

    private fun checkUngroup(grid: Boolean) {
        show(grid, grouped = true)
        val folder = bounds("provider_group_g1")
        val last = bounds("provider_p2")
        dragTo(Offset(last.center.x, last.bottom + 24f)) {
            if (grid) assertTrue(bounds("provider_group_g1").width <= folder.width)
            else assertEquals(folder, bounds("provider_group_g1"))
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
        dragTo(other.center) {
            assertTrue("Do not persist during the preview", moves.isEmpty())
            compose.onNodeWithTag("provider_group_icon_g2").assertExists()
        }
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
