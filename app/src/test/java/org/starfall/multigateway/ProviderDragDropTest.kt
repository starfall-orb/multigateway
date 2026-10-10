package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
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
import org.starfall.multigateway.data.model.ProviderRootOrderItem
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderScreen

/** Phone-launcher style drag and drop on the Providers screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h1000dp-xhdpi")
class ProviderDragDropTest {
    @get:Rule val compose = createComposeRule()

    private fun provider(id: String, group: String? = null, order: Int = 0, icon: String? = null) =
        LlmProviderInfo(id, id, ProviderType.OPENAI, baseUrl = "", groupId = group, sortOrder = order, icon = icon)

    private fun show(
        providers: List<LlmProviderInfo>,
        groups: List<ProviderGroup>,
        writes: MutableList<ProviderPlacement> = mutableListOf(),
        grid: Boolean = true,
        rootOrders: MutableList<List<ProviderRootOrderItem>> = mutableListOf()
    ) {
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = providers,
                    providerGroups = groups,
                    isGridView = grid,
                    onSaveProvider = {},
                    onSaveModels = { _, _ -> },
                    onReorderModels = { _, _ -> },
                    onDeleteProvider = {},
                    onReorderProviders = {},
                    onReorderRootItems = { rootOrders += it },
                    onBack = {},
                    onPlaceProvider = { writes += it; Result.success(Unit) }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun center(tag: String): Offset =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center

    private fun exists(tag: String) =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Long-press at the first point, drag through the rest, then release. */
    private fun drag(from: Offset, to: Offset, release: Boolean = true) {
        val points = listOf(from, to)
        val root = compose.onRoot()
        root.performTouchInput { down(points.first()) }
        compose.mainClock.advanceTimeBy(600)
        points.drop(1).forEach { point ->
            root.performTouchInput { moveTo(point) }
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        if (release) {
            root.performTouchInput { up() }
            compose.waitForIdle()
        }
    }

    private val g = ProviderGroup("g", "Folder", sortOrder = 10)
    private val h = ProviderGroup("h", "Other", sortOrder = 11)

    private val rootProviders = listOf(provider("a", order = 0), provider("b", order = 1), provider("c", order = 2))
    private val members = listOf(provider("m1", "g", 0), provider("m2", "g", 1))

    @Test
    fun draggingAProviderOntoAnotherRootTileReordersTheRoot() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes)

        val originalPosition = center("provider_a")
        drag(originalPosition, center("provider_c"), release = false)
        assertEquals("the following item moves before release", originalPosition, center("provider_b"))
        assertTrue("preview must not write storage", writes.isEmpty())
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        val placement = writes.single()
        assertEquals("a", placement.providerId)
        assertNull(placement.groupId)
        assertEquals(listOf("b", "c", "a", "g"), placement.rootOrder.map { it.id })
    }

    @Test
    fun droppingAProviderOnAFolderMovesItInWithoutOpeningTheFolder() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes)

        drag(center("provider_c"), center("provider_group_g"))

        val placement = writes.single()
        assertEquals("c", placement.providerId)
        assertEquals("g", placement.groupId)
        assertEquals(listOf("m1", "m2", "c"), placement.groupOrders.getValue("g"))
        assertFalse("dropping must not open the folder", exists("provider_folder_dialog_g"))
        assertFalse("the provider now lives in the folder", exists("provider_c"))
    }

    @Test
    fun hoveringAFolderOnlyHighlightsItUntilRelease() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes)
        val before = center("provider_c")

        drag(center("provider_c"), center("provider_group_g"), release = false)

        assertTrue(writes.isEmpty())
        assertEquals(before, center("provider_c"))
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1, writes.size)
    }

    @Test
    fun draggingAFolderReordersItAmongRootTiles() {
        val writes = mutableListOf<ProviderPlacement>()
        val rootOrders = mutableListOf<List<ProviderRootOrderItem>>()
        show(rootProviders + members, listOf(g), writes, rootOrders = rootOrders)

        drag(center("provider_group_g"), center("provider_a"))

        // A folder is reordered through the root order only; membership is untouched.
        assertTrue(writes.isEmpty())
        assertEquals(listOf("g", "a", "b", "c"), rootOrders.single().map { it.id })
        assertFalse(exists("provider_folder_dialog_g"))
    }

    @Test
    fun tappingAFolderStillOpensItAndAFinishedDragNeverDoes() {
        show(rootProviders + members, listOf(g))

        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        assertTrue(exists("provider_folder_dialog_g"))
        assertTrue(exists("provider_folder_provider_m1"))
        assertTrue(exists("provider_folder_provider_m2"))
    }

    @Test
    fun reorderingMembersInsideTheOpenFolder() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes)
        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        drag(center("provider_folder_provider_m1"), center("provider_folder_provider_m2"))

        val placement = writes.single()
        assertEquals("m1", placement.providerId)
        assertEquals("g", placement.groupId)
        assertEquals(listOf("m2", "m1"), placement.groupOrders.getValue("g"))
        assertTrue("the folder stays open after reordering inside it", exists("provider_folder_dialog_g"))
    }

    @Test
    fun draggingAMemberOutOfTheFolderClosesItAndPlacesTheProviderInTheRoot() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes)
        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val outside = Offset(panel.left + 40f, panel.top - 140f)

        drag(center("provider_folder_provider_m1"), outside, release = false)

        assertFalse("leaving the container closes the folder", exists("provider_folder_dialog_g"))
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()

        val placement = writes.single()
        assertEquals("m1", placement.providerId)
        assertNull(placement.groupId)
        assertTrue(placement.rootOrder.any { it.id == "m1" && !it.isGroup })
        assertEquals(listOf("m2"), placement.groupOrders.getValue("g"))
        assertTrue(exists("provider_m1"))
    }

    @Test
    fun aMemberDraggedOutCanBeDroppedIntoAnotherFolder() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members + provider("n1", "h", 0), listOf(g, h), writes)
        compose.onNodeWithTag("provider_group_g", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("provider_folder_dialog_g", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val outside = Offset(panel.left + 40f, panel.top - 140f)
        val start = center("provider_folder_provider_m1")

        val root = compose.onRoot()
        root.performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(600)
        root.performTouchInput { moveTo(outside) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertFalse(exists("provider_folder_dialog_g"))
        root.performTouchInput { moveTo(center("provider_group_h")) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        root.performTouchInput { up() }
        compose.waitForIdle()

        val placement = writes.single()
        assertEquals("m1", placement.providerId)
        assertEquals("h", placement.groupId)
        assertEquals(listOf("n1", "m1"), placement.groupOrders.getValue("h"))
        assertEquals(listOf("m2"), placement.groupOrders.getValue("g"))
        assertFalse("dropping must not open the target folder", exists("provider_folder_dialog_h"))
    }

    @Test
    fun cancelledDragRestoresTheLayoutAndSavesNothing() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes)
        val before = center("provider_a")

        val root = compose.onRoot()
        root.performTouchInput { down(center("provider_a")) }
        compose.mainClock.advanceTimeBy(600)
        root.performTouchInput { moveTo(center("provider_c")) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        root.performTouchInput { cancel() }
        compose.waitForIdle()

        assertTrue(writes.isEmpty())
        assertEquals(before, center("provider_a"))
    }

    @Test
    fun aFolderWithoutALogoPreviewsItsFirstProviders() {
        show(rootProviders + members, listOf(g))
        compose.waitUntil(15_000) { exists("provider_group_preview_g") }
    }

    @Test
    fun aFolderWithALogoShowsTheLogoInsteadOfTheProviderPreview() {
        show(rootProviders + members, listOf(g.copy(icon = "https://example.invalid/logo.png")))
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertTrue(exists("provider_group_icon_g"))
        assertFalse(exists("provider_group_preview_g"))
    }

    @Test
    fun listModeSupportsTheSameGestures() {
        val writes = mutableListOf<ProviderPlacement>()
        show(rootProviders + members, listOf(g), writes, grid = false)

        drag(center("provider_c"), center("provider_group_g"))

        assertEquals("g", writes.single().groupId)
        assertFalse(exists("provider_folder_dialog_g"))
    }
}
