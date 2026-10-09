package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.ProviderGroup
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.providers.ProviderScreen

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h1000dp-xhdpi")
class ProviderFolderContainerTest {
    @get:Rule val compose = createComposeRule()

    private fun showFolder(before: Int, members: Int = 3, grid: Boolean = true) {
        val providers = (0 until before).map {
            LlmProviderInfo("root-$it", "Root $it", ProviderType.OPENAI, baseUrl = "", sortOrder = it)
        } + (0 until members).map {
            LlmProviderInfo("member-$it", "Member $it", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = it)
        } + LlmProviderInfo("tail", "Tail", ProviderType.OPENAI, baseUrl = "", sortOrder = before + 1)
        compose.setContent {
            MaterialTheme {
                ProviderScreen(providers = providers, providerGroups = listOf(ProviderGroup("g", "Folder", before)),
                    isGridView = grid, onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {})
            }
        }
        compose.waitForIdle()
    }

    private fun slot(key: String): Rect = compose.onNodeWithTag("provider_slot_$key", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    @Test fun folderAtSlotTwoHasDetachedHeaderAndThreeMembersReserveSlotsThreeToSix() {
        showFolder(before = 1)
        val before = slot("provider_root-0")
        val header = slot("group_g")
        val a = slot("provider_member-0")
        val b = slot("provider_member-1")
        val c = slot("provider_member-2")
        val tail = slot("provider_tail")
        assertEquals(before.top, header.top, 1f)
        assertTrue(header.left >= before.right)
        assertTrue(a.top >= header.bottom)
        assertEquals(a.top, b.top, 1f)
        assertEquals(a.left, c.left, 1f)
        assertTrue(c.top >= a.bottom)
        assertTrue("The next root item must not occupy the empty slot 6", tail.top >= c.bottom)
        assertEquals(a.left, tail.left, 1f)
        compose.onNodeWithTag("provider_group_icon_g", useUnmergedTree = true).assertExists()

        // Collapsing restores the original two-column root layout; expanding restores the block.
        compose.onNodeWithTag("provider_group_g").performClick()
        compose.onNodeWithTag("provider_group_g").performClick()
        assertEquals(a.top, slot("provider_member-0").top, 1f)
        assertEquals(tail.top, slot("provider_tail").top, 1f)
    }

    @Test fun folderAtSlotThreeIncludesItsHeaderInSlotsThreeToSix() {
        showFolder(before = 2)
        val header = slot("group_g")
        val a = slot("provider_member-0")
        val b = slot("provider_member-1")
        val c = slot("provider_member-2")
        assertEquals(header.top, a.top, 1f)
        assertTrue(a.left >= header.right)
        assertEquals(header.left, b.left, 1f)
        assertEquals(b.top, c.top, 1f)
        assertTrue(slot("provider_tail").top >= c.bottom)
    }

    @Test fun oddNumberOfFolderSlotsKeepsTheLastEmptyCellInside() {
        showFolder(before = 2, members = 2)
        val last = slot("provider_member-1")
        val tail = slot("provider_tail")
        assertTrue(tail.top >= last.bottom)
        assertEquals(last.left, tail.left, 1f)
    }

    @Test fun listFolderKeepsItsHeadingLogoAndMembersInsideOneBlock() {
        showFolder(before = 1, grid = false)
        val heading = slot("heading_g")
        val logo = slot("group_g")
        val first = slot("provider_member-0")
        assertTrue(heading.bottom <= logo.top)
        assertEquals(logo.top, first.top, 1f)
        assertTrue(first.left >= logo.right)
        assertTrue(slot("provider_tail").top >= slot("provider_member-2").bottom)
    }

    @Test fun droppingOnAClosedFolderCommitsTheProviderToThatFolder() {
        val writes = mutableListOf<org.starfall.multigateway.data.model.ProviderPlacement>()
        val source = LlmProviderInfo("source", "Source", ProviderType.OPENAI, baseUrl = "", sortOrder = 0)
        val group = ProviderGroup("g", "Folder", sortOrder = 1)
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = listOf(source),
                    providerGroups = listOf(group),
                    collapsedSectionsState = setOf(group.id),
                    isGridView = true,
                    onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {},
                    onPlaceProvider = {
                        writes += it
                        Result.success(Unit)
                    }
                )
            }
        }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("provider_list")
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val sourceBounds = compose.onNodeWithTag("provider_source").fetchSemanticsNode().boundsInRoot
        val folderBounds = compose.onNodeWithTag("provider_group_g").fetchSemanticsNode().boundsInRoot
        val sourcePoint = sourceBounds.center - listBounds.topLeft
        val folderPoint = folderBounds.center - listBounds.topLeft
        list.performTouchInput {
            down(sourcePoint)
            advanceEventTime(700)
            moveTo(folderPoint)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        assertEquals(1, writes.size)
        assertEquals("source", writes.single().providerId)
        assertEquals("g", writes.single().groupId)
    }

    @Test fun droppingOnAnOpenFolderDoesNotRebuildTheDragMidGesture() {
        val writes = mutableListOf<org.starfall.multigateway.data.model.ProviderPlacement>()
        val source = LlmProviderInfo("source", "Source", ProviderType.OPENAI, baseUrl = "", sortOrder = 0)
        val member = LlmProviderInfo("member", "Member", ProviderType.OPENAI, baseUrl = "", groupId = "g", sortOrder = 0)
        val group = ProviderGroup("g", "Folder", sortOrder = 1)
        compose.setContent {
            MaterialTheme {
                ProviderScreen(
                    providers = listOf(source, member), providerGroups = listOf(group), isGridView = true,
                    onSaveProvider = {}, onSaveModels = { _, _ -> }, onReorderModels = { _, _ -> },
                    onDeleteProvider = {}, onReorderProviders = {}, onBack = {},
                    onPlaceProvider = { writes += it; Result.success(Unit) }
                )
            }
        }
        compose.waitForIdle()
        val sourceNode = compose.onNodeWithTag("provider_source").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("provider_list").fetchSemanticsNode().boundsInRoot
        val folderNode = compose.onNodeWithTag("provider_group_g").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("provider_list").performTouchInput {
            down(sourceNode.center - root.topLeft)
            advanceEventTime(700)
            moveTo(folderNode.center - root.topLeft)
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        assertEquals(1, writes.size)
        assertEquals("g", writes.single().groupId)
    }

}
